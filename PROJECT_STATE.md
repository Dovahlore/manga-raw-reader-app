# 项目状态（压缩交接文档）

> 用途：替代长对话上下文。新会话直接读本文件即可继续，不必回溯历史。
> 最后更新：2026-10-08（离线删书/取消同步容错 + 收藏夹双向同步 + 书名截断省略号）

## 1. 目标

本地漫画翻译服务：**日文漫画 → 中文**，供自研 App 调用（App 负责 EPUB/MOBI 渲染与阅读器，服务负责翻译与出图）。

- 仓库（fork）：`D:\mayuq\OneDrive - bupt.edu.cn\Projects\manga-image-translator`
  - remote: `git@github.com:Dovahlore/manga-image-translator.git`
  - 基线：上游 main `95227a2`（2026-07-20）+ 本项目大量补丁（见 §4）
- 机器：Windows + Docker Desktop，**RTX 5070 Laptop 8GB（Blackwell / sm_120）**

## 2. 运行中的服务（4 个容器，一套 compose 起全）

| 容器 | 宿主端口 | 说明 |
|---|---|---|
| `mit-engine` | 8010 | 引擎（检测/OCR/翻译/抹字/嵌字，GPU 常驻）；Swagger `/docs` |
| `mit-app-api` | **8020** | ★ App 接口（MySQL+Redis+缓存+书页管理）；Swagger `/docs` |
| `mit-app-db` | 127.0.0.1:33063 | MySQL，库 `mit`，用户 `mit` |
| `mit-app-redis` | 127.0.0.1:56379 | Redis（requirepass） |

- **编排/启动**：`docker-compose.full.yml`（compose 项目名 `mit`，网络 `mit_default`）——
  引擎 + MySQL + Redis + App 接口一个文件全包（旧的 `docker-compose.app.yml` 别名已删除）。
- **app-api 通过服务名 `engine:8000` 访问引擎**，不再绕 `host.docker.internal:8010`（少一跳 NAT）
- 口令统一 `Alexmercer2000@`，配置在 `项目根/app.env`；引擎密钥 `项目根/secret.env`（**都已 gitignore**）
- **数据/模型全部在项目内**（`_runtime/` 已 gitignore，约 5GB）：
  | 项目内路径 | 内容 | 容器内 |
  |---|---|---|
  | `_runtime\models` | 模型权重（约 4.8GB）+ HF 缓存 | `engine:/app/models` |
  | `_runtime\data\app` | `books/<book_id>/<页>/{orig,out}.png+result.json`、`cache/result/` | `app-api:/data` |
  | `_runtime\data\mysql` / `redis` | MySQL 数据目录 / Redis AOF | 各自 `/var/lib/mysql`、`/data` |
  | `_runtime\work\{input,output}` | 批处理输入输出、`test_page.png` | `engine:/input`、`/output` |
  | `_runtime\legacy\mit-work` | 旧 `D:\mit-work` 的实验残留（可随时删） | —— |
  - 想整棵搬到别处：`app.env` 里设 `MIT_RUNTIME=D:/mit`（compose）或 `-ModelsDir/-WorkRoot`（mit-docker.ps1）
- D:\ 根目录**不再有** mit 相关目录（原 `D:\models\mit`、`D:\mit-data`、`D:\mit-work` 已迁入项目并删除）

## 3. 关键脚本

```powershell
cd "D:\mayuq\OneDrive - bupt.edu.cn\Projects\manga-image-translator"

# —— 全栈（推荐）：引擎 + DB + Redis + App 接口一起 ——
.\mit-app.ps1 up|down|status|build|ps|logs|engine-logs|engine-restart|test|sql|redis
#   等价于： docker compose --env-file app.env -f docker-compose.full.yml up -d

# —— 引擎单独的操作（只起/停 engine 这一个服务；其余是体检与批处理）——
.\mit-docker.ps1 up|down|restart|logs|status|build|cli|api|shell|probe|gpu-test|net-test
.\mit-smoketest.ps1 [-ConfigFile ... -OutDir ...]                                   # 引擎冒烟

# 接口测试 / 排障（devscripts/，都是纯 python + requests，不需 GPU）
python devscripts\app_api_tests.py        # App 接口端到端 15 项（健康/缓存/上下文/重译/错误处理/并发/跨端污染）
python devscripts\app_api_tests2.py --phase 1   # 需要真跑管线的用例（上下文落库、config 深合并、include_background）
python devscripts\diag_engine_state.py    # 引擎共享状态泄漏 A/B（非流式 → 网页端流式 → 非流式）
python devscripts\epub_extract.py book.epub out\    # 按 spine 真实顺序抽 epub 页
python devscripts\epub_translate_test.py --pages 10,20 --book-id x   # 走 App 接口批量翻
python devscripts\analyze_font_size.py    # 逐块量「渲染字号 / 原文字号」，找字超大的块
python devscripts\make_test_image.py      # 重新生成 _runtime\work\input\test_page.png
python devscripts\app_contract_test.py    # 模拟 Android App 的完整调用序列（鉴权/async job 轮询/下载/删书）
python devscripts\cloud_contract_test.py  # 云同步接口 8 项（上传/列表/查重/下载/收藏夹/账号隔离/取消同步级联）
python devscripts\owner_isolation_test.py KEY_A KEY_B   # 多账号隔离 15 项（两账号同书/books/pages/cloud 互不可见）
python devscripts\cleanup_test_artifacts.py [--apply]   # 清测试书桶 + 占位符缓存（含 MySQL 行）
```
- 引擎重建：`.\mit-app.ps1 build`（走 compose）或 `.\mit-docker.ps1 build -BaseImage docker.m.daocloud.io/pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime`
- 文档：`DOCKER_SETUP.md`、`app_api/API.md`、`android/README.md`
- **Android 阅读器 App**（`android/`，Kotlin + Compose）：书库/封面/导入(EPUB+MOBI)、日漫 RTL+普通 LTR 阅读、
  每页翻译(job 轮询)、右下角切原/译文、自动预翻后 3 页、设置页(地址+API Key=账号)、删书端到端。
  **云同步**：书库三页签 `全部|本地|云端` + 三态角标（仅本地 / ☁✓ 已同步 / ☁ 仅云端）；
  「同步到云端」打 zip 上传、「取消同步」删云端、「下载到本地」还原（含译文缓存与收藏夹）、导入按 SHA-256 去重。
  **离线容错**：删本地书 / 取消同步 / 删云端书都不依赖后台服务——本地先删、云端删除记入补删队列，联网自动补发。
  **收藏夹双向同步**：本地↔云端收藏夹并集补建（`GET/POST/DELETE /v1/cloud/folders`），删夹/改名走补删队列；
  下载云端书自动落进对应收藏夹（本地未归夹时按云端夹归位），同步 zip 的 manifest 也带 folder 兜底。
  书名 / 收藏夹名单行显示，超长按字符截断加「…」（不按单词换行）。
  **合集导入**：zip/rar 递归按「单层图片文件夹 = 一本书」拆分（穿透外层包裹/合集文件夹，反斜杠路径也认）；右下 `+` 另可「导入文件夹」
  （单层纯图片文件夹 = 一本，含子文件夹拒绝）；拆出的书按页内容哈希去重，扫描登记 source_books 避免重读大包。
  后端就是本仓库 app_api(8020)，`MIT_API_TOKEN` 鉴权 + 14 天保留（云端书永久）。用 Android Studio 打开 `android/` 构建

## 4. 已打的上游补丁（都在工作区，**未提交**）

引擎侧：

| 文件 | 修的问题 |
|---|---|
| `server/nonce_state.py`（新） / `server/main.py` / `server/instance.py` | 网关→worker 缺 `X-Nonce` → 401 Nonce does not match |
| `server/sent_data_internal.py` | 用 JSON 解析 worker 的 pickle 响应 → `'utf-8' codec ... 0x80` |
| `manga_translator/mode/share.py` | pickle 白名单过窄（补 `shapely.*` / `manga_translator.*`）；`MIT_CONTEXT_SIZE` 环境变量 |
| `manga_translator/utils/generic.py` | HF 硬编码直链 → `HF_URL_MIRROR` 改写（走 hf-mirror） |
| `manga_translator/translators/sakura.py` | `str + list` 的 TypeError 掩盖真实错误 |
| `manga_translator/translators/glossary_mixin.py`（新） | 术语表能力抽成 mixin |
| `common_gpt.py` / `deepseek.py` / `groq.py` / `custom_openai.py` | 这 4 处**手搓 messages 绕过父类注入**，术语表+上下文此前静默失效 |
| `manga_translator/manga_translator.py` | 上下文注入白名单只认 chatgpt（**单页 + 批量两条路径**都已放开）；支持 `config.context_text` |
| `manga_translator/config.py` | 新增 `context_text` 字段（App 直传上下文，避免服务端串书） |
| `server/main.py` | 新增一条龙接口 `POST /translate/with-form/page`（一次管线返回译文图 base64 + 结构化结果） |
| `manga_translator/mode/share.py` | **`/simple_execute` 路由重置 `self.manga._is_streaming_mode = False`** —— 该标记挂在常驻实例上，网页端流式翻一次就置 True，之前不清，导致之后所有非流式调用（App/脚本）只拿到 1×1 白图占位符 |
| `server/to_json.py` | `ctx.text_regions or []` 兜底 —— 无文字页（整页插图）以前 `TypeError: 'NoneType' object is not iterable` → 引擎 500 → 调用方 502 |

App 侧（新服务）：

| 文件 | 说明 |
|---|---|
| `app_api/{main,db,settings}.py`、`app_api/schema.sql`、`app_api/requirements.txt` | mit-app-api 实现 |
| `Dockerfile.app` | 基于本地 `python:3.12-slim`，只装 fastapi/uvicorn/pymysql/cryptography/redis/httpx |
| `docker-compose.full.yml` | **全栈编排**：引擎 + MySQL + Redis + App 接口（项目名 `mit`）；数据都在项目内 `_runtime/` |
| `mit-app.ps1` | 全栈一键脚本 |
| `app_api/main.py` | **引擎结果校验 `engine_image_ok()`**（读 PNG/JPEG 头，不引 Pillow）：1×1 占位符或尺寸与原图不符 → 502 且**不写缓存/不写 out.png**；缓存条目本身是白图则**自愈**（删条目 + 重跑管线）——以前白图会被当正常结果永久留在 L1 缓存里 |
| `app_api/main.py` | **缓存命中分支补齐 `page_context` 写入** —— 以前只有"真跑管线"的页才登记上下文，命中缓存的页会让同书下一页 `context_used` 静默退化成 `none` |
| `app_api/main.py` | **响应回显页身份** `orig_sha1`（本次请求图片字节 sha1）+ `book_id` + `page_index`（`/translate` 两条分支与 `/retranslate` 都有）——用来一眼区分"传错页"和"结果串页"；配合 `GET /v1/pages/{id}/image?orig=1` 算 sha1 即可核对服务器收到的到底是哪张图 |

## 5. App 接口契约（已实现并验证）

```
POST /v1/pages/translate                     # 核心：一页图进 → 译文图 + 结构化结果
     multipart: image(必填), book_id?, page_index?, title?, order_dir?,
                config?(JSON), context?(JSON [{src,dst}]), force?, include_background?
     → { page_id, cached, elapsed_ms, context_used,
         orig_sha1, book_id, page_index,     # ← 页身份回显（核对"服务器收到哪一页"）
         blocks:[{bbox,angle,src,dst,text}], image_url:"/v1/pages/{id}/image" }
GET  /v1/pages/{id}/image?orig=1             # 译文图 / 原图（长缓存头）
GET  /v1/pages/{id}/json                     # 结构化结果
POST /v1/pages/{id}/retranslate              # 重译：{"config":{...},"context":[...],"force":true}
POST /v1/books  、GET /v1/books  、GET /v1/books/{id}/pages 、DELETE /v1/books/{id}  # 删书=级联删+清磁盘
GET  /v1/health  、GET /v1/capabilities      # 后者含可用翻译器/默认配置

# —— 云同步（账号 = API Key → users.id；未配 Key 时 'default'；书 zip + 翻译结果永久保留）——
POST   /v1/cloud/books             # multipart: file(zip) + hash(必填) + title/folder/mode/fingerprint/page_count
                                   #   按 owner+hash 去重：已存在直接返回原 id（existed=true）
GET    /v1/cloud/books             # 当前账号云端书列表（id/title/mode/page_count/hash/folder/...）
GET    /v1/cloud/books/lookup?hash=...   # 查重：{book_id, existed, title}
GET    /v1/cloud/books/{id}/download     # 下云端 zip（book.src + pages + translated + manifest.json）
DELETE /v1/cloud/books/{id}             # 取消同步：删 zip + 记录 + 翻译结果（books 级联）+ 磁盘
GET    /v1/cloud/folders 、POST /v1/cloud/folders 、DELETE /v1/cloud/folders/{id}
```
- **鉴权已开**：`/v1/*` 都要 `X-API-Token`（= `app.env` 的 `MIT_API_TOKEN`，当前 `aFm7-O4B5VD457MKVDNpDK9QFDkjIHbd`），带错→401；关掉就把 app.env 里留空并重启
- **保留期**：翻译结果（pages/块/上下文 + 磁盘图片与 L1 缓存）超 `MIT_RETENTION_DAYS`（默认 14 天）自动清理；App 本地有译文缓存。
  **已同步到云端（`cloud_books`）的书豁免 14 天清理**（书 zip 与翻译结果永久保留，取消同步才删）；
  未同步的翻译结果仍按 14 天清理。App 端已同步的书用 `cloudId` 作为服务端 `book_id`（见 `Book.serverId`）
- 全部参数可选：不传 `book_id/page_index` 自动落 `_adhoc_<图片sha1>` 桶
- 上下文优先级：**App 传的 context > 服务端按 book_id 分桶的历史 > 无**
- **存储分层**：DB 只存长期要查的（`books/pages/page_blocks/page_context/jobs` 5 表，
  4 个外键 + 级联删除；`page_blocks` 主键是 `(page_id, idx)` 复合键）；
  图片落磁盘 `_runtime/data/app/{books,cache/result}`；L1 结果缓存的结构化 JSON 放 **Redis**
  （`mit:result:<图sha1>:<配置hash>`，TTL=`MIT_CACHE_TTL` 默认 7 天）
- **任务/进度**：`POST /v1/pages/translate?async_mode=1` 立即返回 `{job_id,status,job_url}`，
  App 轮询 `GET /v1/jobs/{job_id}`（queued→running→done/failed，done 带 `page_id`）；
  同步路径（默认）也会写 jobs 并在响应里带 `job_id`

## 6. 实测数据（可复现）

| 项 | 结果 |
|---|---|
| 首次单页翻译（DeepSeek + 检测/OCR/抹字/嵌字） | **27.6s**（引擎重启后冷启动首几页约 30~45s，模型要重载） |
| 同样的页再请求（L1 结果缓存） | **~200ms**，`cached=true`（Redis 命中，约 150×） |
| 术语表生效证据 | 术语表 `テスト→测验`，输出由「测试。」变「测验。」 |
| 跨页上下文证据 | 第 2 页 token 478 → 567（+89 ≈ 上下文字符数） |
| 断网可跑 | `docker run --network none` 日→英全链路成功 |
| 换语言重译 | 中→英→中 切换后 src/dst 标注正确 |
| **接口测试轮（15 项）** | `app_api_tests.py`：14/15 通过（唯一 FAIL 是"该页已被缓存"的断言问题，非产品缺陷）；无文字页从 502 → **200 + 空 blocks**；网页端用过之后 App 仍拿到真图 |
| 跨端污染 A/B | 修复前：非流式真图 → 网页端流式一次 → 非流式 **1×1 白图**；修复后同一序列全程真图 880×1280 |
| 缓存自愈 | 6 个被污染成 1×1 的缓存条目在补丁后首次访问时被丢弃并重跑，最终残留 0 个 |
| ebook 抽样实测（15 页） | 120.8s / 15 页，均 8.5s/页，缓存命中 0.1s；对白页译文质量好，版权/后记等密集小字页 OCR 原文即糊 |

## 7. 环境适配要点（换机器必看）

- **Blackwell(sm_120) 必须 CUDA 12.8+**：基镜像 `pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime`（torch 2.8.0+cu128）。上游默认 CUDA 11.8 镜像在 50 系上拿不到 GPU
- Docker Hub（registry-1.docker.io）连不上 → 镜像走 `docker.m.daocloud.io/...`；构建时**指定 `-BaseImage` 可跳过联网预检**（BuildKit 用缓存解析）
- pip 走清华源（Dockerfile 里的 `PIP_INDEX_URL`）；`rusty-manga-image-translator` 来自 GitHub Pages，装不上整包 import 会失败
- **HF 三件套**：`HF_ENDPOINT=https://hf-mirror.com`（transformers）、`HF_URL_MIRROR=https://hf-mirror.com`（代码里硬编码直链）、`HF_HUB_DISABLE_XET=1`（关 xet 桥，国内必崩）
- DeepSeek：容器内 `NO_PROXY` 已含 `api.deepseek.com`；key 在 `项目根/secret.env`（旧的 `D:\mit-work\secret.env` 已迁入）

## 8. 工程陷阱（踩过的）

1. **PowerShell 5.1 需要 UTF-8 BOM**：`.ps1` 若被工具改写丢掉 BOM，中文注释会被当 GBK 读 → 语法错。用 `[IO.File]::WriteAllText($p,$t,(New-Object Text.UTF8Encoding($true)))` 补
2. **`Set-Location` 不改 .NET 的 CWD**：`[IO.File]::ReadAllText("相对路径")` 会找错；一律用绝对路径
3. **不要给这些脚本的输出接管道**（`| Select-String`）：脚本内 `$ErrorActionPreference='Stop'`，docker 往 stderr 写进度会被当终止错误
4. `"$var:/path"` 必须写 `${var}:/path`（否则被当成作用域变量）
5. `mysql:latest` 是 9.x：不要传 `--default-authentication-plugin`（已移除）；PyMySQL 需配 `cryptography`
6. 组件端口避开已有服务：33062/6379/8010 已占用，故选 33063/56379/8020
7. **引擎的"共享实例状态"是三个坑的共同根因**：`_is_streaming_mode`（网页端流式置位后污染所有非流式调用 → 1×1 白图）、`translator_cache`（翻译器实例复用）、`_build_prev_context`（进程内历史跨请求残留）。凡是挂在常驻实例上、按请求变化的开关，都要在每条入口路由上按请求重置；不要依赖"上一条请求留下的值"
8. 排查这类问题的顺序：**先分清「白图」和「空页」** —— 1×1 纯白 PNG 是占位符标记（管线其实跑完了，真图在引擎 `result/<folder>/final.png`），整页空白才是没检测到文字；日志里 `No text regions! - Skipping` 是后者
9. 用 `devscripts/diag_engine_state.py` 之前**不要**先跑网页端，否则 A/B 的基准就被污染了
10. **compose 的绑定挂载路径含空格/中文没问题**（项目路径是 `...\OneDrive - bupt.edu.cn\Projects\...`），`docker compose config` 能直接看到渲染后的 `source:`，出问题先跑它
11. 搬 MySQL 数据目录**必须先 `docker compose down`**；同一个盘内 `Move-Item` 是秒级重命名，跨盘才会真拷
12. 批量搬目录时**目标父目录要先建**，否则 `Move-Item` 会报 `Could not find a part of the path` 而跳过（本次踩过：`_runtime\data\app\books|cache` 漏建）

## 9. 未完成 / 下一步

- **✅ 云同步 + 多账号隔离已全链路落地（后端 + Android，均已验证）**：
  - 后端：`users` 表（`id` + `api_key_hash` 唯一 + `name` + `token_used` + `page_count` + `last_active_at`）做「用户 id ↔ API Key」映射；
    **账号 = API Key → users.id**（`MIT_API_TOKEN` 支持逗号分隔多 Key，一个 Key 一个账号）；`books`/`jobs`/`cloud_*` 的 `owner` 列存 users.id，
    `/v1/books*`、`/v1/pages*`、`/v1/jobs*`、`/v1/cloud*` 全部按 owner 隔离（跨账号 404）。新增 `GET /v1/usage` 看当前账号用量。
    保留期清理的 SELECT 与 DELETE **都排除 `cloud_books`**（已 A/B 验证：未同步 14 天删、已同步保留且图 HTTP 200）。
    `cloud_contract_test.py` 8/8、`owner_isolation_test.py` 15/15 通过（含「两账号传同一本书 → 各自独立 id、互不可见/下载/删除」）。
  - Android：书库三页签 + 三态角标、同步/取消同步/下载还原、导入 SHA-256 去重、设置页「API Key=账号」、
    已同步书用 `cloudId` 作为服务端 `book_id`（`Book.serverId`）；**译文不打进 zip**（直接以服务端 books/pages 为准，实时同步），
    下载/打开时从服务端拉最新译文，阅读器有「☁ 刷新」按钮。APK 已重打 `app-debug.apk`（约 10.2MB）并 adb 装机。
- **✅ 本轮已修（4 个，均已重建容器并验证）**：① 网页端流式 → App 拿 1×1 白图（`share.py`）；② 无文字页引擎 500 → App 502（`to_json.py`）；③ App 不校验引擎图、把白图缓存成永久结果（`app_api/main.py`，含自愈）；④ 缓存命中不写 `page_context` 导致上下文退化（`app_api/main.py`）
- （**待办**）**渲染字号偏大**：约 150 块里 22 块译文比原文大 1.8~4.7 倍，集中在"又高又窄"的竖排框。机制：渲染器直接画 `region.font_size`，而它 = 检测框短边（合并区域取各行最小值），**不是真实字号**，`default` 渲染器又不做缩放到框内。可调 `render.font_size_offset`（负值）/`render.font_size`（固定）、降 `detector.unclip_ratio`（2.3 → 1.4~1.6）、或把 App 默认 detector 从 `ctd`（气泡级框）换回 `default`（行级框）
- （**待办，已确认但按用户要求搁置**）`target_lang=ENG` 时引擎返回 `{"ENG": "是姐姐啊"}` —— key 是 ENG 但内容是中文，即翻译器没按 target_lang 走（疑与进程内历史/提示模板相关）；当前只保证 DeepSeek→中文
- （**未开始**）**上下文压缩**：把老页压成"人名/称呼/剧情"滚动摘要 + 近期对白原样，按字符预算裁剪 —— 省 token 又保住长程一致性。此前误加的表与配置（`book_summary`、`SUMMARY_*`、`DEEPSEEK_*`）**已全部回退**，代码与运行容器一致，库里也只有最初的 6 张表
- **L3 OCR 复用**：换翻译器重译目前仍重跑整条管线（~25s）；要做需引擎加"传入 text_regions"的入口
- **token/耗时统计入库**（成本可见）：`pages.tokens` 字段已留，暂未写入
- **EPUB/MOBI 服务端摄取**：目前由 App 渲染页图上传（推荐先这样）；`devscripts/epub_extract.py` 可做本地按序抽页
- **提交代码**：`app_api/*`、`docker-compose.full.yml`（新，全栈）、`docker-compose.app.yml`（转 include 别名）、`Dockerfile.app`、`mit-app.ps1`、`mit-docker.ps1`、`mit-smoketest.ps1`、`DOCKER_SETUP.md`、`server/nonce_state.py`、`devscripts/*`（本轮新增 8 个脚本）、`.gitignore`（加了 `_runtime/`、`secret.env`），以及 §4 里一串改过的 py 均未提交
- 登录/配额：当前仅内网自用；对外用需加 `MIT_API_TOKEN` + Tailscale/反代

## 10. 一句话现状

引擎（GPU、离线可跑）+ 中文链路（DeepSeek，术语表与跨页上下文均已接通）+ App 接口（MySQL/Redis/缓存/重译，一页 7~15s、缓存 ~0.2s）+ **云同步（书与翻译永久保留、多账号隔离）** 全部可用；
Android App（EPUB/MOBI 阅读器 + 翻译 + 云同步）已打包。剩余已知问题两个：部分文本块渲染字号偏大、`target_lang=ENG` 不生效（均不影响 DeepSeek→中文）。差的是提交存档与几个增强项。
