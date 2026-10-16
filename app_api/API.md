# mit-app-api 接口速查（Postman 用）

- 基地址：`http://127.0.0.1:8020`
- **认证（已开启）**：除下方「公开端点」外，所有 `/v1/*` 请求头必须带 `X-API-Token`，值 = `app.env` 里的
  `MIT_API_TOKEN`。没带或带错 → **401**。`MIT_API_TOKEN` 支持逗号分隔多 Key（一把 Key 一个账号）。
  想关掉鉴权：`MIT_API_TOKEN` 留空**且**显式设 `MIT_ALLOW_EMPTY_TOKEN=1` 再重启——缺一样服务会拒绝启动。
- **公开端点（无 X-API-Token，App 更新通道的既成契约）**：`GET /v1/app/update`、
  `GET /v1/app/releases/{id}/download`、`GET /dashboard`（看板 HTML）、`GET /`。
- **管理端点**：`GET /v1/admin/overview` 在 `X-API-Token` 之外还要求 `X-Admin-Token` 头匹配
  `MIT_ADMIN_TOKEN`（未配置该变量时放行，启动日志有警告）。
- Swagger（可以直接在浏览器里点着试）：<http://127.0.0.1:8020/docs>（右上角 `Authorize` 填 token）
- 现成 Collection：`docs/postman/mit-app-api.postman_collection.json` → Postman `Import`，
  先在集合变量里把 `token` 填上
- **Postman 里必须处理的坑**：你开着 Clash，Postman 默认走系统代理，会去访问代理而不是本机。
  在 `Settings → Proxy` 关掉 `Use system proxy configuration`，或者把 `127.0.0.1` 加进 bypass 列表。

通用状态码：`400` 参数问题 / `401` 缺鉴权 / `403` 管理令牌不对 / `404` 不存在 / `409` 书 id 已属他人 /
`410` 原图不在本地（重译）/ `413` 请求体超限（单页 50MB、整书 4GB、APK 1GB、云同步 zip 2GB）/
`422` 表单类型不对 / `502` 引擎侧故障（detail 里会写明原因）/ `503` 同书建任务锁忙（稍后重试）。

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

**第一步（必做）：先注册书。** 带 `book_id` 翻译时 books 表必须已有该 id 的行，否则 jobs 外键直接 500：

```bash
curl -X POST http://127.0.0.1:8020/v1/books -H "Content-Type: application/json"   -d '{"id":"demo-book","title":"ヤニねこ Vol.01","page_count":206,"order_dir":"rtl"}'
```

**第二步：**`multipart/form-data`（Postman 选 `Body → form-data`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `image` | File | ✅ | 整页图片（png/jpg/webp…），单页请求体上限 50MB（413） |
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
  "cache_key": "<owner>:<图片sha1>:<配置hash>",
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
- `cached=true` 表示命中 L1 结果缓存（几十毫秒返图），此时**不重跑管线也不重复计费**
- **缓存分层**：图片按内容 hash 落本地磁盘（`_runtime/data/app/cache/result/*.png`），
  结构化结果 JSON 放 **Redis**（键 `mit:result:<owner>:<图sha1>:<配置hash>`，**按账号隔离**，
  TTL 默认 **14 天**，`app.env` 里 `MIT_CACHE_TTL` 可改；0 = 永不过期）。
  Redis 挂了自动降级为"无缓存直接跑管线"
- `context_used`：`client`（你传的）/ `db`（服务端按 book_id 取的前几页）/ `none`
- 无文字的页（整页插图）会正常返回 **200 + `blocks: []`**，图就是原图
- **`orig_sha1` / `book_id` / `page_index` 是"页身份回显"**，用来一眼确认服务器收到的到底是哪一页：

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
| `GET /v1/pages/{page_id}/image` | 译文图（PNG/WebP，长缓存头） |
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

`application/json`（body 可整段省略；**总是重跑管线**，不看缓存；落库前过白图/尺寸校验，坏图 502）

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
> 重译成功后会作废该页新旧两个配置的 L1 缓存键，不会被旧缓存回滚。

---

## 4. 书与页管理

| 请求 | Body | 说明 |
|---|---|---|
| `POST /v1/books` | `{"id":"demo-book","title":"…","format":"epub","page_count":206,"order_dir":"rtl"}` | 建/更新书 |
| `GET /v1/books` | —— | 书列表 + 已译页数（最近 200 本） |
| `GET /v1/books/{book_id}/pages` | —— | 该书每页的状态/耗时/失败原因；`?after_index=N` 增量拉取（只回 page_index 更大的页），单次最多 5000 行 |
| `DELETE /v1/books/{book_id}` | —— | 删书：级联删页/块/上下文/任务 + 磁盘页文件（App 删书用） |
| `GET /v1/jobs/{job_id}` | —— | 任务进度：表单 `async_mode=true` 发任务后轮询，`done` 后带 `page_id` |
| `GET /v1/usage` | —— | 当前账号用量：`user_id` + `{token_used, page_count, last_active_at}` |

```bash
curl -X POST http://127.0.0.1:8020/v1/books -H "Content-Type: application/json" \
  -d '{"id":"demo-book","title":"ヤニねこ Vol.01","page_count":206,"order_dir":"rtl"}'
curl http://127.0.0.1:8020/v1/books
curl "http://127.0.0.1:8020/v1/books/demo-book/pages?after_index=99"
```

---

## 5. 云同步（/v1/cloud/*，多账号按 API Key 隔离）

| 请求 | Body/参数 | 说明 |
|---|---|---|
| `POST /v1/cloud/books` | multipart：`file`(zip ≤2GB) + `title`/`folder`/`mode`/`hash`/`fingerprint`/`page_count` | 同步一本书；同账号同 `hash` 去重；书 id 已属他人 → 409 |
| `GET /v1/cloud/books` | —— | 云端书列表（含 folder / hash / size） |
| `GET /v1/cloud/books/lookup?hash=…` | —— | 按 hash 查是否已同步 |
| `GET /v1/cloud/books/{id}` → `GET /v1/cloud/books/{id}/cover` | —— | 下载 zip / 取封面 |
| `GET /v1/cloud/books/{id}/download` | —— | 下载 zip |
| `DELETE /v1/cloud/books/{id}` | —— | 取消同步：删 zip + 记录 + 它的翻译结果（级联） |
| `GET/POST/DELETE /v1/cloud/folders[/{id}]` | `{"name":…}` | 收藏夹增删查（删夹不解散书，只解除归属） |

> 除公开端点外所有 `/v1/*` 都要 `X-API-Token`（**API Key 即账号**：服务端用
> `users.api_key_hash = SHA-256(Key)` 找用户，各表 `owner` 存 `users.id`；同一个 Key 多设备互通，
> 不同 Key 互不可见）。云端书与其翻译结果**永久保留**（14 天自动清理会跳过已同步书），只有取消同步才删。

---

## 6. 任务与全书翻译

| 请求 | Body/参数 | 说明 |
|---|---|---|
| `POST /v1/books/translate-all` | multipart：`book_id`✅、`images[]`✅、`page_indices`(JSON)、`config`、`force`、`title`、`order_dir`、`page_count` | 整书上传翻译（请求体 ≤4GB），返回 `book_job_id`，轮询 pages 看进度 |
| `POST /v1/books/{book_id}/translate-from-zip` | form：`config`、`force`、`page_indices` | 已同步书服务端自取图整书翻 |
| `POST /v1/books/{book_id}/pages/{page_index}/translate` | form：`force`、`config` | 单页服务端自取图翻译（async job，返回 `job_id`） |
| `GET /v1/books/{book_id}/active-page-jobs` | —— | 该书进行中的单页任务列表 |
| `POST /v1/jobs/{job_id}/cancel` | —— | 取消任务（只对 queued/running 生效） |
| `POST /v1/books/{book_id}/cancel` | —— | 按书取消全部活动任务 |
| `GET /v1/admin/overview` | 头：`X-Admin-Token` | 全局看板数据（管理用；需 `MIT_ADMIN_TOKEN`） |

> 同一本书并发建任务会被 GET_LOCK 串行化：查重命中返回已有 job（`duplicate:true`），锁忙返回 503 稍后重试。
> 全书逐页失败有熔断：连接类错误单页重试至多 2 次，连续 3 页失败中止整本 job。

---

## 7. 阅读进度与同步

| 请求 | Body | 说明 |
|---|---|---|
| `GET /v1/reading-progress` | —— | 本账号全部阅读进度 |
| `PUT /v1/reading-progress/{book_hash}` | `{"page":int,"last_read_at":int}` | LWW 写进度；书未注册时自动补最小 books 行重试一次，进度不再静默丢 |
| `POST /v1/sync` | `{"books":[…],"progress":[…],"deletes":[…],"cancels":[…],"cloud_deletes":[…],"folder_moves":[…],"book_ids":[…]}` | 一次性双向同步（App 专用批量接口） |

---

## 8. App 自动更新与看板

| 请求 | 鉴权 | 说明 |
|---|---|---|
| `GET /v1/app/update?platform=&channel=&abi=&version_code=` | 公开 | 检查更新（返回最新版与 download_path） |
| `GET /v1/app/releases/{id}/download` | 公开 | 下载 APK（路径严格限制在 releases 目录内） |
| `POST /v1/app/releases` | X-API-Token | 上传发布 APK（multipart，≤1GB；幂等覆盖同 channel/platform/abi/version_code） |
| `GET /dashboard` | 公开 | 看板 HTML（数据接口 `/v1/admin/overview` 另需管理令牌） |

---

## 9. 引擎侧（8010，排查用，App 正常不用直接调）

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
> 用 PowerShell 手工测就改用 `-F "config={"translator":{...}}"` 或直接用 Postman。
