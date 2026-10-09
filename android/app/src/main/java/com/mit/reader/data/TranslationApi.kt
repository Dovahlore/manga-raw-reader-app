package com.mit.reader.data

import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class TranslateResp(val pageId: Int, val jobId: String?, val cached: Boolean)
data class JobStatus(val status: String, val pageId: Int?, val error: String?)
data class ServerPage(
    val id: Int,
    val pageIndex: Int,
    val status: String,
    val configHash: String = "",
    val updatedAt: String = "",
)
data class ServerBook(
    val id: String,
    val title: String?,
    val pageCount: Int?,
    val donePages: Int,
    val failedPages: Int,
    val activeJobs: Int = 0,
    val jobStatus: String? = null,
)
data class CloudUploadResp(val bookId: String, val existed: Boolean)
data class CloudBook(
    val id: String,
    val title: String?,
    val mode: String,
    val pageCount: Int?,
    val hash: String?,
    val fingerprint: String?,
    val size: Long?,
    val folder: String?,
)
data class CloudFolder(val id: Int, val name: String, val bookCount: Int)
data class AppUpdate(
    val latest: Boolean,
    val versionCode: Int = 0,
    val versionName: String = "",
    val changelog: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val force: Boolean = false,
    val minVersionCode: Int = 0,
    val downloadPath: String = "",
)
data class ReadingProgressResp(
    val bookId: String,
    val page: Int?,
    val lastReadAt: Long?,
)
data class UsageInfo(
    val userId: String,
    val tokenUsed: Long,
    val pageCount: Int,
    val lastActiveAt: String?,
)

/**
 * app_api 客户端。地址 / API Key 每次都从 ServerConfig 现取（改完设置立刻生效）。
 * 鉴权：请求头 X-API-Token（ServerConfig.apiKey 为空则不带，服务端此时不校验）。
 */
class TranslationApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // 下载专用：大文件（180MB 漫画包 / 云端 zip）可能几十秒没数据（服务端限速/缓冲），
    // 30s 读超时太短会误判失败。这里读超时放宽到 5 分钟，连接超时仍 30s 快速失败。
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val base get() = ServerConfig.baseUrl

    private fun Request.Builder.authed(): Request.Builder =
        apply {
            ServerConfig.apiKey.takeIf { it.isNotBlank() }?.let { header("X-API-Token", it) }
        }

    /** 上传一页翻译。async=true 立刻拿 jobId 去轮询；false 同步等结果（慢）。 */
    suspend fun translate(image: File, bookId: String, pageIndex: Int, async: Boolean, force: Boolean = false): TranslateResp =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image", image.name, image.asRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("book_id", bookId)
                .addFormDataPart("page_index", pageIndex.toString())
                .apply {
                    if (async) addFormDataPart("async_mode", "true")
                    if (force) addFormDataPart("force", "true")
                }
                .build()
            val req = Request.Builder().url("$base/v1/pages/translate").authed().post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
                val j = JSONObject(text)
                TranslateResp(
                    pageId = j.optInt("page_id", -1),
                    jobId = j.optString("job_id").takeIf { it.isNotEmpty() },
                    cached = j.optBoolean("cached", false),
                )
            }
        }

    suspend fun job(jobId: String): JobStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/jobs/$jobId").authed().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
            val j = JSONObject(text)
            JobStatus(
                status = j.optString("status"),
                pageId = if (j.isNull("page_id")) null else j.optInt("page_id"),
                error = j.optString("error").takeIf { it.isNotEmpty() },
            )
        }
    }

    /** 拉服务端全部书的翻译汇总（done/failed 页数），给书库「翻译进度」页用。 */
    suspend fun listBooks(): List<ServerBook> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/books").authed().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
                val j = JSONObject(text)
                val arr = j.optJSONArray("books") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ServerBook(
                        id = o.getString("id"),
                        title = if (o.isNull("title")) null else o.optString("title").takeIf { it.isNotBlank() },
                        pageCount = if (o.isNull("page_count")) null else o.optInt("page_count"),
                        donePages = o.optInt("done_pages", 0),
                        failedPages = o.optInt("failed_pages", 0),
                        activeJobs = o.optInt("active_jobs", 0),
                        jobStatus = if (o.isNull("job_status")) null else o.optString("job_status").takeIf { it.isNotBlank() },
                    )
                }
            }
        }

    /** 查某本书在服务端已有的页（page_id/page_index/status），用于全书翻译时跳过已翻好的页。 */
    suspend fun bookPages(bookId: String): List<ServerPage> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/books/$bookId/pages").authed().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
                val j = JSONObject(text)
                val arr = j.optJSONArray("pages") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ServerPage(
                        id = o.getInt("id"),
                        pageIndex = o.getInt("page_index"),
                        status = o.getString("status"),
                        configHash = o.optString("config_hash", ""),
                        updatedAt = o.optString("updated_at", ""),
                    )
                }
            }
        }

    /** 全书翻译：一次把若干页图传上去，服务端按顺序后台跑。返回 book_job_id（停止用）。 */
    suspend fun translateAll(
        bookId: String,
        title: String?,
        orderDir: String?,
        pageCount: Int,
        pageIndices: List<Int>,
        images: List<File>,
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("book_id", bookId)
            .addFormDataPart("page_count", pageCount.toString())
            .addFormDataPart("page_indices", JSONArray(pageIndices).toString())
        title?.takeIf { it.isNotBlank() }?.let { builder.addFormDataPart("title", it) }
        orderDir?.let { builder.addFormDataPart("order_dir", it) }
        for (img in images) {
            builder.addFormDataPart("images", img.name, img.asRequestBody("image/jpeg".toMediaType()))
        }
        val req = Request.Builder().url("$base/v1/books/translate-all").authed().post(builder.build()).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text).optString("book_job_id").takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("无 book_job_id")
        }
    }

    /** 全书翻译（服务端自取图）：已同步的书从云端 zip 取图，不上传页图。返回 book_job_id。
     *  pageIndices 传 null/空 = 翻译全部页。 */
    suspend fun translateAllFromZip(
        bookId: String,
        title: String?,
        orderDir: String?,
        pageIndices: List<Int>? = null,
        force: Boolean = false,
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (!pageIndices.isNullOrEmpty()) {
            body.addFormDataPart("page_indices", JSONArray(pageIndices).toString())
        }
        title?.takeIf { it.isNotBlank() }?.let { body.addFormDataPart("title", it) }
        orderDir?.let { body.addFormDataPart("order_dir", it) }
        if (force) body.addFormDataPart("force", "true")
        val req = Request.Builder().url("$base/v1/books/$bookId/translate-from-zip").authed().post(body.build()).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text).optString("book_job_id").takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("无 book_job_id")
        }
    }

    /** 取消服务端后台任务（停止全书翻译）。 */
    suspend fun cancelJob(jobId: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/jobs/$jobId/cancel").authed()
                .post(ByteArray(0).toRequestBody(null)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    /** 取消某本书的所有后台任务（覆盖进程被杀后遗留的重复 job）。 */
    suspend fun cancelBookJobs(bookId: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/books/$bookId/cancel").authed()
                .post(ByteArray(0).toRequestBody(null)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    /** 单页翻译（服务端自取图）：已同步的书不上传图片，返回 jobId 轮询。 */
    suspend fun translateFromServer(bookId: String, pageIndex: Int, force: Boolean = false): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            if (force) body.addFormDataPart("force", "true")
            val req = Request.Builder().url("$base/v1/books/$bookId/pages/$pageIndex/translate").authed().post(body.build()).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
                JSONObject(text).optString("job_id").takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("无 job_id")
            }
        }

    suspend fun deleteBook(bookId: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/books/$bookId").authed().delete().build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    /** 当前账号用量：token / 翻页数 / 最后活跃时间。 */
    suspend fun usage(): UsageInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/usage").authed().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
            val j = JSONObject(text)
            val u = j.optJSONObject("usage")
            UsageInfo(
                userId = j.optString("user_id", ""),
                tokenUsed = u?.optLong("token_used", 0) ?: 0L,
                pageCount = u?.optInt("page_count", 0) ?: 0,
                lastActiveAt = u?.optString("last_active_at")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** 检查 App 新版本：GET /v1/app/update（实时查库，无鉴权）。返回最新可用版本信息。 */
    suspend fun checkUpdate(versionCode: Int, abi: String): AppUpdate =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "$base/v1/app/update?platform=android&channel=stable&abi=$abi&version_code=$versionCode"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
                val j = JSONObject(text)
                AppUpdate(
                    latest = j.optBoolean("latest", false),
                    versionCode = j.optInt("version_code", 0),
                    versionName = j.optString("version_name"),
                    changelog = j.optString("changelog"),
                    size = j.optLong("size", 0),
                    sha256 = j.optString("sha256"),
                    force = j.optBoolean("force", false),
                    minVersionCode = j.optInt("min_version_code", 0),
                    downloadPath = j.optString("download_path"),
                )
            }
        }

    /** 拉取当前账号所有云端书的阅读进度。 */
    suspend fun getReadingProgressList(): List<ReadingProgressResp> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/reading-progress").authed().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val arr = JSONObject(text).optJSONArray("progress") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ReadingProgressResp(
                        bookId = o.getString("book_id"),
                        page = if (o.isNull("page")) null else o.optInt("page"),
                        lastReadAt = if (o.isNull("last_read_at")) null else o.optLong("last_read_at"),
                    )
                }
            }
        }

    /** 上传某本云端书的阅读进度（LWW）。 */
    suspend fun putReadingProgress(bookId: String, page: Int, lastReadAt: Long): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = JSONObject().apply {
                put("page", page)
                put("last_read_at", lastReadAt)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/v1/reading-progress/$bookId").authed().put(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    /** 测连通：GET /v1/health，返回状态说明。 */
    suspend fun ping(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$base/v1/health").authed().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) "OK: ${text.take(120)}" else "HTTP ${resp.code}: ${text.take(120)}"
            }
        }.getOrElse { if (it is java.io.IOException) "未连接" else "连接失败: ${it.message}" }
    }

    // ---------------------------------------------------------------- 云同步

    /** 同步一本本地书：上传 zip（book.src + pages + translated + manifest），按 owner+hash 去重。
     *  onProgress: (已上传字节, 总字节)。 */
    suspend fun cloudUpload(
        zip: File,
        title: String?,
        folder: String?,
        mode: String,
        hash: String,
        fingerprint: String?,
        pageCount: Int,
        oldBookId: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): CloudUploadResp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val total = zip.length()
        val fileBody = object : RequestBody() {
            override fun contentType() = "application/zip".toMediaType()
            override fun contentLength() = total
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(64 * 1024)
                zip.inputStream().use { ins ->
                    var sent = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress?.invoke(sent, total)
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", zip.name, fileBody)
            .addFormDataPart("hash", hash)
            .addFormDataPart("mode", mode)
            .addFormDataPart("page_count", pageCount.toString())
        title?.takeIf { it.isNotBlank() }?.let { body.addFormDataPart("title", it) }
        folder?.takeIf { it.isNotBlank() }?.let { body.addFormDataPart("folder", it) }
        fingerprint?.takeIf { it.isNotBlank() }?.let { body.addFormDataPart("fingerprint", it) }
        oldBookId?.takeIf { it.isNotBlank() }?.let { body.addFormDataPart("old_book_id", it) }
        val req = Request.Builder().url("$base/v1/cloud/books").authed().post(body.build()).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            val j = JSONObject(text)
            CloudUploadResp(j.getString("book_id"), j.optBoolean("existed", false))
        }
    }

    /** 按内容 hash 查云端是否已有同内容书，返回 cloudId（无则 null）。 */
    suspend fun cloudLookup(hash: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (hash.isBlank()) return@withContext null
            val req = Request.Builder().url("$base/v1/cloud/books/lookup?hash=$hash").authed().build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val j = JSONObject(resp.body?.string().orEmpty())
                    if (j.isNull("book_id")) null else j.optString("book_id").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }

    /** 拉当前账号的云端书列表。 */
    suspend fun cloudList(): List<CloudBook> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/cloud/books").authed().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
            val arr = JSONObject(text).optJSONArray("books") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CloudBook(
                    id = o.getString("id"),
                    title = if (o.isNull("title")) null else o.optString("title").takeIf { it.isNotBlank() },
                    mode = o.optString("mode", "manga"),
                    pageCount = if (o.isNull("page_count")) null else o.optInt("page_count"),
                    hash = if (o.isNull("hash")) null else o.optString("hash").takeIf { it.isNotBlank() },
                    fingerprint = if (o.isNull("fingerprint")) null else o.optString("fingerprint").takeIf { it.isNotBlank() },
                    size = if (o.isNull("size")) null else o.optLong("size"),
                    folder = if (o.isNull("folder")) null else o.optString("folder").takeIf { it.isNotBlank() },
                )
            }
        }
    }

    /** 云端文件夹列表（同步时的 folder 名会在此体现）。 */
    suspend fun cloudFolders(): List<CloudFolder> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/cloud/folders").authed().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
            val arr = JSONObject(text).optJSONArray("folders") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CloudFolder(o.getInt("id"), o.optString("name", "未命名"), o.optInt("book_count", 0))
            }
        }
    }

    /** 云端新建收藏夹（owner+name 唯一，重名幂等）。离线/失败返回 false，由调用方排队补建。 */
    suspend fun cloudFolderCreate(name: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = JSONObject().put("name", name).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/v1/cloud/folders").authed().post(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    /** 删云端收藏夹（夹下云端书回到未分类，书本身不删）。 */
    suspend fun cloudFolderDelete(folderId: Int): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/cloud/folders/$folderId").authed().delete().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    /** 下载云端书 zip（还原到本地用）。onProgress: (已下载字节, 总字节)。 */
    suspend fun cloudDownload(cloudId: String, out: File, onProgress: ((Long, Long) -> Unit)? = null) =
        download("$base/v1/cloud/books/$cloudId/download", out, onProgress)

    /** 云端书封面 URL / 下载（未下载本地时也能显示封面）。 */
    fun cloudCoverUrl(cloudId: String) = "$base/v1/cloud/books/$cloudId/cover"
    suspend fun cloudDownloadCover(cloudId: String, out: File) =
        download(cloudCoverUrl(cloudId), out)

    /** 更新云端书归属收藏夹（folder=null 或空 = 移出未分类）。 */
    suspend fun cloudUpdateFolder(cloudId: String, folder: String?): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = JSONObject().put("folder", folder ?: "").toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/v1/cloud/books/$cloudId/folder").authed().post(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    /** 取消同步：删云端 zip + 记录 + 它的翻译结果（服务端级联）。 */
    suspend fun cloudDelete(cloudId: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/cloud/books/$cloudId").authed().delete().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }

    fun translatedUrl(pageId: Int) = "$base/v1/pages/$pageId/image"
    fun originalUrl(pageId: Int) = "$base/v1/pages/$pageId/image?orig=1"

    suspend fun download(
        url: String,
        out: File,
        onProgress: ((Long, Long) -> Unit)? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            // 自己服务器的资源（译文图 / 云端书下载 / 封面）必须带鉴权头；外部 URL（如 Kmoe）不带
            if (url.startsWith(base)) builder.authed()
            extraHeaders.forEach { (k, v) -> if (v.isNotBlank()) builder.header(k, v) }
            val req = builder.build()
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("空响应")
                val total = body.contentLength()
                body.byteStream().use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            written += n
                            onProgress?.invoke(written, total)
                        }
                    }
                }
            }
        }

    /** 断点续传下载：out 为 .part 文件；resumeFrom=已安全落盘的字节数。
     *  服务端支持 Range 就 206 续传（先把 out 截到 resumeFrom 再追加），否则 200 从头覆盖；416 视为已下完。
     *  shouldStop 返回 true 时抛 CancellationException（暂停/取消）。返回最终写入字节数。 */
    suspend fun downloadResumable(
        url: String,
        out: File,
        resumeFrom: Long,
        extraHeaders: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
        shouldStop: () -> Boolean = { false },
    ): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        extraHeaders.forEach { (k, v) -> if (v.isNotBlank()) builder.header(k, v) }
        if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")
        val req = builder.build()
        downloadClient.newCall(req).execute().use { resp ->
            if (resp.code == 416) {
                // 范围不满足：已下完
                onProgress?.invoke(resumeFrom, resumeFrom)
                return@withContext resumeFrom
            }
            val body = resp.body ?: throw IllegalStateException("空响应")
            val append = resp.code == 206 && resumeFrom > 0
            val remaining = body.contentLength()
            val total = if (append) { if (remaining >= 0) resumeFrom + remaining else -1L } else remaining
            var written = if (append) resumeFrom else 0L
            if (append) {
                // 把可能多写的尾块截掉，保证从 resumeFrom 精确续传
                RandomAccessFile(out, "rw").use { it.setLength(resumeFrom) }
            }
            onProgress?.invoke(written, total)
            body.byteStream().use { ins ->
                FileOutputStream(out, append).use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (shouldStop()) throw CancellationException("暂停/取消")
                        val n = ins.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        written += n
                        onProgress?.invoke(written, total)
                    }
                }
            }
            written
        }
    }
}
