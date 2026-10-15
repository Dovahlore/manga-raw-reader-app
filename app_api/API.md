# mit-app-api 接口速查（Postman 用）

- 基地址：`http://127.0.0.1:8020`
- **认证（已开启）**：所有 `/v1/*` 请求头必须带 `X-API-Token: <token>`，token = `app.env` 里的 `MIT_API_TOKEN`
  （当前值 `（已轮换，新值见本地 app.env，勿提交明文）`）。没带或带错 → **401**。想关掉鉴权就把 `app.env` 里
  `MIT_API_TOKEN=` 留空并重启 app-api。
- Swagger（可以直接在浏览器里点着试）：<http://127.0.0.1:8020/docs>（右上角 `Authorize` 填 token）
- 现成 Collection：`docs/postman/mit-app-api.postman_collection.json` → Postman `Import`，
  先在集合变量里把 `token` 填上
- **Postman 里必须处理的坑**：你开着 Clash，Postman 默认走系统代理，会去访问代理而不是本机。
  在 `Settings → Proxy` 关掉 `Use system proxy configuration`，或者把 `127.0.0.1` 加进 bypass 列表。

通用状态码：`400` 参数问题 / `401` 缺鉴权 / `404` page_id 不存在 / `410` 原图不在本地（重译） / `422` 表单类型不对 / `502` 引擎侧故障（detail 里会写明原因）。

---

## 0. 健康与能力

### GET `/v1/health`
```bash
curl http://127.0.0.1:8020/v1/health
```
```json
{"status":"ok","engine":{"url":"http://engine:8000","ok":true,"queue_size":0},
 "db":{"ok":true},"redis":{"ok":true},"data_dir":"/data"}
```

### GET `/v1/capabilities`
返回默认管线配置（deepseek/CHS、ctd、mocr、lama_large）与可用翻译器列表。
```bash
curl http://127.0.0.1:8020/v1/capabilities
```

---

## 1. ★ 核心：翻译一页 `POST /v1/pages/translate`

**`multipart/form-data`**（Postman 选 `Body → form-data`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `image` | File | ✅ | 整页图片（png/jpg/webp…） |
| `book_id` | text | | 书 ID；不传则自动落 `_adhoc_<图片sha1>` 桶 |
| `page_index` | text(整数) | | 页序，用于排序与「取上几页作上下文」 |
| `title` | text | | 书名（写进 books 表） |
| `order_dir` | text | | `ltr` / `rtl`（阅读方向） |
| `config` | text(JSON) | | 覆盖默认管线，会与默认**深合并**，例：`{"translator":{"target_lang":"ENG"}}` |
| `context` | text(JSON) | | 客户端自己维护的上文：`[{"src":"ニャー","dst":"喵"}]`，传了就优先于服务端历史 |
| `force` | text | | `true` = 忽略 L1 缓存强制重跑管线 |
| `include_background` | text | | `true` = 每块额外返回抹字底图 base64（很大），并把 `image_b64` 一并返回 |
| `async_mode` | text | | `true` = 立即返回 `{job_id,status,job_url}`，后台跑管线，App 轮询 `GET /v1/jobs/{job_id}` 看进度（做进度条用） |

```bash
curl -X POST http://127.0.0.1:8020/v1/pages/translate \
  -F "image=@D:/pics/page-010.jpg" \
  -F "book_id=demo-book" -F "page_index=10" -F "title=ヤニねこ Vol.01" -F "order_dir=rtl"
```

响应（`blocks[]` 是校对界面要的数据）：
```json
{
  "page_id": 79,
  "cache_key": "<图片sha1>:<配置hash>",
  "cached": false,
  "status": "done",
  "elapsed_ms": 4963,
  "context_used": "none",
  "orig_sha1": "d872a5315d54e92dd83bbd2de1b47c593af7df71",
  "book_id": "demo-book",
  "page_index": 10,
  "blocks": [
    {"bbox":[176,201,461,319], "angle":0.0, "prob":0.9999,
     "fg":[0,2,0], "bg":[0,2,0],
     "src":"これは漫画の", "dst":"这是漫画的",
     "text":{"ja":"これは漫画の","CHS":"这是漫画的"}}
  ],
  "image_url": "/v1/pages/79/image",
  "image_b64": null
}
```
- `cached=true` 表示命中 L1 结果缓存（几十毫秒返图），此时**不重跑管线**
- **缓存分层**：图片按内容 hash 落本地磁盘（`_runtime/data/app/cache/result/*.png`），
  结构化结果 JSON 放 **Redis**（键 `mit:result:<图sha1>:<配置hash>`，TTL 默认 7 天，
  `app.env` 里 `MIT_CACHE_TTL` 可改；0 = 永不过期）。Redis 挂了自动降级为"无缓存直接跑管线"
- `context_used`：`client`（你传的）/ `db`（服务端按 book_id 取的前几页）/ `none`
- 无文字的页（整页插图）会正常返回 **200 + `blocks: []`**，图就是原图
- **`orig_sha1` / `book_id` / `page_index` 是"页身份回显"，用来一眼确认服务器收到的到底是哪一页**：

  ```bash
  # 请求里带 book_id + page_index，并回显原图 sha1
  curl -X POST http://127.0.0.1:8020/v1/pages/translate \
    -F "image=@page-060.jpg" -F "book_id=debug" -F "page_index=60"
  # 想核对服务器收到的图和你手里的文件是否同一张：
  curl -s "http://127.0.0.1:8020/v1/pages/<page_id>/image?orig=1" -o server_orig.jpg
  certutil -hashfile server_orig.jpg SHA1        # 和响应里的 orig_sha1 比
  ```
  不传 `book_id` 时会落进 `_adhoc_<图片sha1前缀>` 桶，`book_id` 字段就能看出这一点。

---

## 2. 取图 / 取结构化结果

| 请求 | 说明 |
|---|---|
| `GET /v1/pages/{page_id}/image` | 译文图（PNG，长缓存头） |
| `GET /v1/pages/{page_id}/image?orig=1` | 原图 |
| `GET /v1/pages/{page_id}/json` | 该页的块级结果（从 DB 读，`src_text/dst_text`） |

```bash
curl -o out.png http://127.0.0.1:8020/v1/pages/79/image          # Postman 里选 "Send and Download"
curl http://127.0.0.1:8020/v1/pages/79/image?orig=1 -o orig.png
curl http://127.0.0.1:8020/v1/pages/79/json
```
```json
{"page":{"id":79,"book_id":"demo-book","page_index":10,"status":"done","attempts":1,
         "error":null,"elapsed_ms":4963,"orig_sha1":"..."},
 "blocks":[{"idx":0,"minx":176,"miny":201,"maxx":461,"maxy":319,"angle":0.0,
            "prob":0.9999,"src_text":"これは漫画の","dst_text":"这是漫画的"}]}
```

---

## 3. 重译 `POST /v1/pages/{page_id}/retranslate`

`application/json`（body 可整段省略；**总是重跑管线**，不看缓存）

```json
{
  "config":  {"translator": {"translator": "deepseek", "target_lang": "CHS"},
              "detector":   {"detector": "default", "detection_size": 1536},
              "render":     {"font_size_offset": -6}},
  "context": [{"src": "ニャー", "dst": "喵"}]
}
```
```bash
curl -X POST http://127.0.0.1:8020/v1/pages/79/retranslate \
  -H "Content-Type: application/json" \
  -d '{"config":{"render":{"font_size_offset":-6}}}'
```
响应：`{"page_id":79,"status":"done","attempts":2,"blocks":[...],"out_path":"..."}`
> 常用场景：字太大 → 传 `render.font_size_offset` 负值；框不准 → 换 `detector.detector`；换语言 → `translator.target_lang`。

---

## 4. 书与页管理

| 请求 | Body | 说明 |
|---|---|---|
| `POST /v1/books` | `{"id":"demo-book","title":"…","format":"epub","page_count":206,"order_dir":"rtl"}` | 建/更新书 |
| `GET /v1/books` | —— | 书列表 + 已译页数（最近 200 本） |
| `GET /v1/books/{book_id}/pages` | —— | 该书每页的状态/耗时/失败原因 |
| `DELETE /v1/books/{book_id}` | —— | 删书：级联删页/块/上下文/任务 + 磁盘页文件（App 删书用） |
| `GET /v1/jobs/{job_id}` | —— | 任务进度：`async_mode=1` 发任务后轮询，`done` 后带 `page_id` |
| `GET /v1/usage` | —— | 当前账号用量：`user_id` + `{token_used, page_count, last_active_at}` |

```bash
curl -X POST http://127.0.0.1:8020/v1/books -H "Content-Type: application/json" \
  -d '{"id":"demo-book","title":"ヤニねこ Vol.01","page_count":206,"order_dir":"rtl"}'
curl http://127.0.0.1:8020/v1/books
curl http://127.0.0.1:8020/v1/books/demo-book/pages
```

---

## 5. 云同步（/v1/cloud/*，多账号按 API Key 隔离）

| 请求 | Body/参数 | 说明 |
|---|---|---|
| `POST /v1/cloud/books` | multipart：`file`(zip) + `title`/`folder`/`mode`/`hash`/`fingerprint`/`page_count` | 同步一本书；同账号同 `hash` 去重，返回已存在 id |
| `GET /v1/cloud/books` | —— | 云端书列表（含 folder / hash / size） |
| `GET /v1/cloud/books/lookup?hash=…` | —— | 按 hash 查是否已同步 |
| `GET /v1/cloud/books/{id}/download` | —— | 下载 zip |
| `DELETE /v1/cloud/books/{id}` | —— | 取消同步：删 zip + 记录 + 它的翻译结果（级联） |
| `GET/POST/DELETE /v1/cloud/folders[/{id}]` | `{"name":…}` | 收藏夹增删查（删夹不解散书，只解除归属） |

> 所有 `/v1/*` 都要 `X-API-Token`（**API Key 即账号**：服务端用 `users.api_key_hash = SHA-256(Key)` 找用户，
> 各表 `owner` 存 `users.id`；同一个 Key 多设备互通，不同 Key 互不可见）。`MIT_API_TOKEN` 支持逗号分隔多 Key。
> 云端书与其翻译结果**永久保留**（14 天自动清理会跳过已同步书），只有取消同步才删。

---

## 6. 引擎侧（8010，排查用，App 正常不用直接调）

| 请求 | 说明 |
|---|---|
| `GET http://127.0.0.1:8010/` | 网页 UI（人工翻译/校对） |
| `POST http://127.0.0.1:8010/translate/with-form/json` | 只拿结构化结果（multipart：`image` + `config`） |
| `POST http://127.0.0.1:8010/translate/with-form/page` | 一条龙：base64 译文图 + 结构化结果（App 用的是这个） |
| `POST http://127.0.0.1:8010/translate/with-form/image` | 只拿译文图 |
| `POST http://127.0.0.1:8010/queue-size` | 排队长度 |

```bash
curl -X POST http://127.0.0.1:8010/translate/with-form/json \
  -F "image=@D:/pics/page-010.jpg" \
  -F 'config={"translator":{"translator":"deepseek","target_lang":"CHS"}}'
```
> ⚠️ PowerShell 里 `curl.exe -F 'config={...}'` 的双引号会被吃掉，Postman 没这个问题；
> 用 PowerShell 手工测就改用 `-F "config={\"translator\":{...}}"` 或直接用 Postman。
