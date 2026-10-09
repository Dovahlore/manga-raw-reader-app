# MangaReader —— EPUB 漫画阅读器（Android）

一个本地漫画翻译阅读器：导入 EPUB → 书库（封面=首页）→ 阅读（日漫右→左 / 普通左→右）→
每页翻译 → 右下角切原图/译文图 → 自动预翻后 3 页。翻译走后端 `mit-app-api`（8020）。

## 功能

- **书库**：封面网格；右下 `+` 菜单两种导入（复制进 app 专属目录 `filesDir/library/<id>/`，含抽出的页图）：
  - 导入文件：EPUB / MOBI / 漫画压缩包（zip/rar）。**合集包自动拆分**——递归按「单层图片文件夹 = 一本书」：
    外面套几层合集/包裹文件夹都能穿透，每个只含图片的文件夹各成一本，散图单独一本；单本包行为不变。
    拆出的每本按页内容哈希去重。
  - 导入文件夹：单层纯图片文件夹（解压后的合集文件夹直接选它）= 一本书；**含子文件夹直接拒绝**。
  长按封面删除：本地文件 + 本地译文缓存 + 服务端该书全部记录一起删。
- **书库筛选**：书库与翻译进度分为两个页签；点击书库页签右侧小三角可切换 `全部 | 本地 | 云端`，每本书左上角带同步状态角标——`仅本地` / `☁✓ 已同步` / `☁ 仅云端`。进度页带封面，本地与云端任务可分别收起，并按服务端真实 Job 状态显示运行或排队。
- **云同步**：书菜单「同步到云端」把书打成 zip（book.src + pages + manifest.json，**译文不打进 zip**）传到后端；
  「取消同步」删云端（含翻译结果）；仅云端书可「下载到本地」还原（页图 + 从服务端拉最新译文 + 所属收藏夹）。
  导入时按源文件 SHA-256 去重。译文以服务端 `books/pages` 为准，任何设备新翻/重翻都实时落到云端（14 天清理跳过已同步书）。
- **阅读**：日漫模式右→左横滑，普通模式左→右；顶栏显示 `x / 总数`。
- **翻译**：底栏「翻译本页」→ 走 `POST /v1/pages/translate?async_mode=1` → 轮询 `GET /v1/jobs/{id}`；
  完成后译文图下载到本地缓存（`filesDir/translated/<书id>/<页>.webp`）。右下角按钮切原图/译文图；已同步书有「☁ 刷新」按钮拉取别处新翻的页。
- **自动模式**：顶栏开关，开启后进入阅读器/翻页时预翻「当前页起后 3 页」。
- **设置**：服务器地址（http/https + 域名 + 端口均可）+ API Key（**兼作云同步账号**）+ 测试连接。

## 后端配合

后端就是仓库里的 `mit-app-api`（端口 8020）。两件事要和 App 对上：

1. **鉴权 + 账号**：`app.env` 里 `MIT_API_TOKEN` 已填（`aFm7-O4B5VD457MKVDNpDK9QFDkjIHbd`），
   App 的「设置 → API Key」填同一串。**API Key 就是账号**：服务端用它的 SHA-256 当 owner——
   同一个 Key 的多台设备互通，不同 Key 互不可见。留空则服务端不校验（回退单一 `default` 账号）。
   多账号：把 `MIT_API_TOKEN` 改成逗号分隔的多个 Key，每个 Key 一个账号。
2. **云同步的翻译永久保留**：已同步的书（`cloud_books`）豁免 14 天清理，书 zip 与翻译结果永久保留；
   未同步的翻译结果仍按 14 天自动清理。
3. **服务器地址**：默认已指向本机 `http://192.168.0.90:8020`（见 `ServerConfig.kt`）。
   - 真机与电脑同一局域网即可直连（后端已绑 0.0.0.0；若连不上多半是 **Windows 防火墙**没放行 8020 入站，加一条放行）。
   - 换电脑/端口：App 里「设置」页改。
   - Android **模拟器**：把地址改成 `http://10.0.2.2:8020`（10.0.2.2 = 宿主机）。
   - 公网：`https://你的域名`（需自行反代/TLS，`MIT_API_TOKEN` 必开）。

## 构建运行

**APK 已在本机打好**：`android/app/build/outputs/apk/debug/app-debug.apk`（约 10.2MB，debug 签名）。
直接拷到手机安装即可（手机允许"未知来源安装"）。

**本机命令行重新打包**（已装好工具链在 `D:\android-toolchain` + SDK 在 `D:\android-sdk`）：

```powershell
cd "D:\mayuq\OneDrive - bupt.edu.cn\Projects\manga-image-translator\android"
$env:JAVA_HOME='D:\android-toolchain\jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:\android-sdk'; $env:ANDROID_SDK_ROOT='D:\android-sdk'
D:\android-toolchain\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon
```

也可以直接用 Android Studio（Ladybug 或更新）打开本目录构建。

> 依赖版本（`build.gradle.kts`）：AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM 2024.09.00。
> 若 Gradle sync 报版本相关错误，多半是本地 Gradle/JDK 版本与上面不一致，按 Studio 提示对齐即可。

## 目录

```
android/app/src/main/java/com/mit/reader/
  ReaderApp.kt           Application：初始化 ServerConfig / 仓库 / API
  MainActivity.kt        入口（Compose）
  ReaderViewModel.kt     阅读态 + 翻译 job 轮询 + 自动预翻
  data/EpubParser.kt     EPUB(spine 顺序)抽图
  data/MobiParser.kt     MOBI/AZW(PDB+PalmDOC)抽图
  data/Book.kt           Book / Folder / 阅读进度 / serverId(云端书 id)
  data/LibraryRepository.kt  书库 + 本地译文缓存 + 云同步(zip 打包/下载还原) + index.json
  data/TranslationApi.kt app_api 客户端（OkHttp，带 X-API-Token + 云接口）
  data/ServerConfig.kt   服务器地址 / API Key（兼账号，SharedPreferences）
  ui/AppNav.kt           路由
  ui/LibraryScreen.kt    书库
  ui/ReaderScreen.kt     阅读器
  ui/SettingsScreen.kt   设置
```
