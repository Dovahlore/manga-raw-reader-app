package com.mit.reader

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mit.reader.data.AppUpdate
import com.mit.reader.data.Book
import com.mit.reader.data.CloudBook
import com.mit.reader.data.DiscoveredDevice
import com.mit.reader.data.PeerHasBookException
import com.mit.reader.data.TransferClient
import com.mit.reader.data.TransferServer
import com.mit.reader.data.Folder
import com.mit.reader.data.LibraryRepository
import com.mit.reader.data.ReadingProgress
import com.mit.reader.data.ReadingMode
import com.mit.reader.data.ServerConfig
import com.mit.reader.data.TranslationApi
import com.mit.reader.data.serverId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** WebView 后台下载任务状态。 */
enum class DownloadStatus { DOWNLOADING, PAUSED, IMPORTING, DONE, DUPLICATE, FAILED, CANCELED }

/** 一条后台下载任务（进度页「下载」区展示）。 */
data class DownloadTask(
    val id: String,
    val url: String,
    val name: String,
    val title: String,
    val cookie: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val written: Long = 0L,
    val total: Long = 0L,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val message: String = "",
)

/** 同步到云端 / 云端下载还原的一条进度（key 在 ReaderApp.syncTasks 里就是书 id）。 */
data class SyncTask(val text: String, val frac: Float?)

/** 设备对传的接收进度（自动接收，无需确认）。 */
data class IncomingTransfer(
    val title: String,
    val received: Long,
    val total: Long,
    val done: Boolean,
    val message: String,
)

enum class LibraryImportState { RUNNING, DONE, DUPLICATE, FAILED }

data class LibraryImportStatus(
    val state: LibraryImportState,
    val title: String,
    val message: String,
) {
    val running: Boolean get() = state == LibraryImportState.RUNNING
}

data class LibraryScanStatus(
    val running: Boolean,
    val processed: Int,
    val total: Int,
    val added: Int,
    val failed: Int,
    val currentFile: String? = null,
    val message: String,
) {
    val text: String
        get() = when {
            running && !currentFile.isNullOrBlank() -> "扫描中 $processed/$total：$currentFile"
            running && total > 0 -> "扫描中 $processed/$total（新增 $added，失败 $failed）"
            running -> message
            else -> message
        }
}

data class LibraryStorageOption(
    val path: String?,
    val label: String,
    val removable: Boolean,
    val usableBytes: Long,
)

data class StorageMigrationStatus(
    val running: Boolean,
    val copied: Int,
    val total: Int,
    val message: String,
) {
    val text: String
        get() = if (running && total > 0) "存储迁移中 $copied/$total：$message" else message
}

enum class BackgroundActionState { RUNNING, DONE, FAILED }

data class BackgroundActionStatus(
    val state: BackgroundActionState,
    val message: String,
) {
    val running: Boolean get() = state == BackgroundActionState.RUNNING
}

class ReaderApp : Application() {
    lateinit var library: LibraryRepository
        private set
    val api = TranslationApi()

    // 全书翻译跑在 Application 级作用域里：切换页面/进阅读器/回桌面都不会中断
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storageGate = Mutex()
    private val prefs by lazy { getSharedPreferences("mit_translation", MODE_PRIVATE) }

    // 全书翻译队列：FIFO，队头=正在翻，其余=排队中。多本书点「全书翻译」按顺序一本本翻。
    private val translateQueue = mutableStateListOf<String>()
    private var queueWorker: Job? = null
    private var serverJobId: String? = null
    @Volatile private var stopRequested = false

    /** 正在翻的那本（队头）。 */
    val translatingBookId: String? get() = translateQueue.firstOrNull()
    /** 排队中的书（不含正在翻的队头）。 */
    val queuedBookIds: List<String> get() = translateQueue.drop(1)
    var translatingProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** 每本书最后一次已知的翻译进度 (done, total)：停止/完成后保留，进度页用，避免停止后进度消失或回跳。 */
    val progressHistory = mutableStateMapOf<String, Pair<Int, Int>>()
    /** 后台同步云端译文到本地时置 true（书库主页显示小转圈）。 */
    var syncingTranslations by mutableStateOf(false)
        private set
    /** 服务器是否可达（书库/进度页顶栏显示绿点=在线 / 红点=离线）。 */
    var serverOnline by mutableStateOf(true)
        private set
    /** 书库内容版本号：后台导入（下载完成 / 扫描文件夹）新增书后自增，书库页据此自动刷新。 */
    var libraryRevision by mutableStateOf(0)
        private set
    var libraryScanStatus by mutableStateOf<LibraryScanStatus?>(null)
        private set
    var storageMigrationStatus by mutableStateOf<StorageMigrationStatus?>(null)
        private set
    var currentLibraryStorageDir by mutableStateOf<String?>(null)
        private set
    val libraryImports = mutableStateMapOf<String, LibraryImportStatus>()
    val backgroundActions = mutableStateMapOf<String, BackgroundActionStatus>()
    val cloudSubmittingIds = mutableStateListOf<String>()
    private val libraryScanMutex = Mutex()
    @Volatile private var folderSyncRunning = false
    private var libraryScanJob: Job? = null
    private var backgroundSyncJob: Job? = null
    var cloudRevision by mutableStateOf(0)
        private set
    var lastPingResult by mutableStateOf("")
        private set
    var accountUsageText by mutableStateOf("")
        private set
    var libraryBooks by mutableStateOf<List<Book>>(emptyList())
        private set
    var libraryFolders by mutableStateOf<List<Folder>>(emptyList())
        private set
    var libraryLastRead by mutableStateOf<Pair<Book, ReadingProgress>?>(null)
        private set
    private var libraryCacheJob: Job? = null

    /** 通知书库内容变了（后台新增/删除书后调用）。 */
    fun bumpLibrary() {
        libraryRevision++
        refreshLibraryCache(debounceMillis = 300)
    }
    fun bumpCloud() { cloudRevision++ }

    /** 新导入只更新这一本书，避免扫描期间书库反复全量读 SD。 */
    private fun upsertLibraryBook(book: Book) {
        libraryRevision++
        libraryBooks = if (libraryBooks.any { it.id == book.id }) {
            libraryBooks.map { if (it.id == book.id) book else it }
        } else {
            libraryBooks + book
        }
        registerBookMetadata(book)   // 新书入库即上报后端（幂等）
    }

    fun refreshLibraryCache(debounceMillis: Long = 0) {
        libraryCacheJob?.cancel()
        libraryCacheJob = appScope.launch {
            if (debounceMillis > 0) delay(debounceMillis)
            val books = library.books()
            libraryBooks = books
            libraryFolders = library.folders()
            libraryLastRead = library.lastRead(books)
        }
    }

    fun refreshAccountUsage() = appScope.launch {
        val text = StringBuilder()
        try {
            val usage = api.usage()
            text.appendLine("用户 ID：${usage.userId}")
            text.appendLine("Token 消耗：${usage.tokenUsed}")
            text.appendLine("翻页次数：${usage.pageCount}")
            usage.lastActiveAt?.let { text.appendLine("最后活跃：$it") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            text.appendLine("用量获取失败：${e.message}")
        }
        try {
            val list = api.cloudList()
            val bytes = list.sumOf { it.size ?: 0L }
            text.appendLine("云端书：${list.size} 本 · 占用 ${formatAccountBytes(bytes)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            text.appendLine("云端用量获取失败：${e.message}")
        }
        accountUsageText = text.toString().trimEnd()
    }

    private fun runBackgroundAction(
        key: String,
        useStorageGate: Boolean = true,
        action: suspend () -> Unit,
    ) {
        if (backgroundActions[key]?.running == true) return
        val queuedForMigration = useStorageGate && storageMigrationStatus?.running == true
        backgroundActions[key] = BackgroundActionStatus(
            BackgroundActionState.RUNNING,
            if (queuedForMigration) "排队中：等待存储搬运" else "正在处理…",
        )
        appScope.launch {
            try {
                if (useStorageGate) storageGate.withLock { action() } else action()
                if (queuedForMigration) backgroundActions[key] = BackgroundActionStatus(BackgroundActionState.RUNNING, "正在处理…")
                backgroundActions[key] = BackgroundActionStatus(BackgroundActionState.DONE, "已完成")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backgroundActions[key] = BackgroundActionStatus(BackgroundActionState.FAILED, e.message ?: "操作失败")
                Toast.makeText(this@ReaderApp, "操作失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                delay(3_000)
                backgroundActions.remove(key)
            }
        }
    }

    /** 外部程序「打开」epub/mobi 后待跳转的书 id；AppNav 消费后进阅读器。 */
    var pendingOpenBookId by mutableStateOf<String?>(null)
        private set

    fun consumePendingOpen() { pendingOpenBookId = null }

    /** 外部「打开方式」进来：导入（按内容 hash 去重）并请求打开阅读器。 */
    fun openDocument(uri: Uri) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()
        importDocument(uri, openAfter = true, persistAcrossRestart = false)
    }

    /** 应用级后台导入：返回/进设置/切页面不会取消，也不会重复导入同一个 Uri。 */
    fun importDocument(
        uri: Uri,
        folderId: String? = null,
        openAfter: Boolean = false,
        persistAcrossRestart: Boolean = true,
    ) {
        val key = uri.toString()
        if (libraryImports[key]?.running == true) return
        if (persistAcrossRestart) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            queuePendingImport(key, folderId, openAfter)
        }
        val queuedForMigration = storageMigrationStatus?.running == true
        libraryImports[key] = LibraryImportStatus(
            LibraryImportState.RUNNING,
            uri.lastPathSegment?.substringAfterLast('/') ?: "导入中",
            if (queuedForMigration) "排队中：等待存储搬运" else "正在导入…",
        )
        appScope.launch {
            try {
                val result = storageGate.withLock {
                    libraryImports[key] = LibraryImportStatus(
                        LibraryImportState.RUNNING,
                        uri.lastPathSegment?.substringAfterLast('/') ?: "导入中",
                        "正在导入…",
                    )
                    val list = library.importResults(uri)
                    if (folderId != null) {
                        list.filterNot { it.duplicate }.forEach { r -> runCatching { library.moveBook(r.book.id, folderId) } }
                    }
                    list
                }
                val added = result.filterNot { it.duplicate }
                val state = if (added.isEmpty()) LibraryImportState.DUPLICATE else LibraryImportState.DONE
                val titleText = if (result.size == 1) result[0].book.title else "合集 ${result.size} 本"
                val msg = when {
                    result.size == 1 && added.isEmpty() -> "本地已有《${result[0].book.title}》"
                    result.size == 1 -> "已导入《${result[0].book.title}》"
                    added.isEmpty() -> "合集 ${result.size} 本本地都已有"
                    else -> "合集拆分：已导入 ${added.size} 本（${added.joinToString("、") { it.book.title }.take(48)}）"
                }
                libraryImports[key] = LibraryImportStatus(state, titleText, msg)
                added.forEach { upsertLibraryBook(it.book.copy(folderId = folderId)) }
                if (openAfter) pendingOpenBookId = (added.firstOrNull() ?: result.firstOrNull())?.book?.id
                Toast.makeText(
                    this@ReaderApp,
                    libraryImports[key]?.message.orEmpty(),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                libraryImports[key] = LibraryImportStatus(
                    LibraryImportState.FAILED,
                    uri.lastPathSegment?.substringAfterLast('/') ?: "导入",
                    "导入失败：${e.message}",
                )
                Toast.makeText(this@ReaderApp, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            removePendingImport(key)
            delay(8_000)
            libraryImports.remove(key)
        }
    }

    /** 导入「单层纯图片文件夹」（SAF 树，解压后的合集文件夹直接用）。含子文件夹会报错提示。 */
    fun importFolder(treeUri: Uri, folderId: String? = null) {
        val key = "tree:${treeUri}"
        if (libraryImports[key]?.running == true) return
        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        libraryImports[key] = LibraryImportStatus(
            LibraryImportState.RUNNING,
            "导入文件夹",
            if (storageMigrationStatus?.running == true) "排队中：等待存储搬运" else "正在导入…",
        )
        appScope.launch {
            try {
                val result = storageGate.withLock { library.importFolderTree(treeUri) }
                if (folderId != null && !result.duplicate) runCatching { library.moveBook(result.book.id, folderId) }
                val state = if (result.duplicate) LibraryImportState.DUPLICATE else LibraryImportState.DONE
                libraryImports[key] = LibraryImportStatus(
                    state,
                    result.book.title,
                    if (result.duplicate) "本地已有《${result.book.title}》" else "已导入《${result.book.title}》",
                )
                if (!result.duplicate) upsertLibraryBook(result.book.copy(folderId = folderId))
                Toast.makeText(this@ReaderApp, libraryImports[key]?.message.orEmpty(), Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libraryImports[key] = LibraryImportStatus(
                    LibraryImportState.FAILED,
                    "导入文件夹",
                    "导入失败：${e.message}",
                )
                Toast.makeText(this@ReaderApp, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                delay(8_000)
                libraryImports.remove(key)
            }
        }
    }

    private fun queuePendingImport(uri: String, folderId: String?, openAfter: Boolean) {
        val list = pendingImports().toMutableList()
        if (list.none { it.first == uri }) {
            list += Triple(uri, folderId, openAfter)
            prefs.edit().putString("pending_imports", JSONArray().apply {
                list.forEach { (itemUri, folder, open) ->
                    put(JSONObject().apply {
                        put("uri", itemUri)
                        folder?.let { put("folder_id", it) }
                        put("open_after", open)
                    })
                }
            }.toString()).apply()
        }
    }

    private fun removePendingImport(uri: String) {
        val remaining = pendingImports().filterNot { it.first == uri }
        prefs.edit().putString("pending_imports", JSONArray().apply {
            remaining.forEach { (itemUri, folder, open) ->
                put(JSONObject().apply {
                    put("uri", itemUri)
                    folder?.let { put("folder_id", it) }
                    put("open_after", open)
                })
            }
        }.toString()).apply()
    }

    private fun pendingImports(): List<Triple<String, String?, Boolean>> {
        val raw = prefs.getString("pending_imports", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            val item = arr.optJSONObject(index) ?: return@mapNotNull null
            val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(uri, item.optString("folder_id").takeIf { it.isNotBlank() }, item.optBoolean("open_after", false))
        }
    }

    private fun resumePendingImports() {
        pendingImports().forEach { (uri, folderId, openAfter) ->
            importDocument(Uri.parse(uri), folderId, openAfter, persistAcrossRestart = false)
        }
    }

    fun startLibraryScan() {
        if (libraryScanStatus?.running == true) return
        libraryScanJob?.cancel()
        libraryScanJob = appScope.launch { scanLibraryNow() }
    }

    fun cancelSyncBook(book: Book) = runBackgroundAction("cancel-sync:${book.id}") {
        if (!requireOnline()) return@runBackgroundAction
        stopTranslatingIf(book.id)
        val cloudId = book.cloudId
        val cancelled = runCatching { library.cancelSync(book) }.isSuccess
        if (!cancelled && cloudId != null) {
            // 后台服务连不上：本地先脱钩（书变回「仅本地」，随时可删），云端副本排进补删队列。
            // 注意：runBackgroundAction 已持有 storageGate，这里不能再 withLock（Mutex 不可重入）
            library.detachCloud(book)
            recordPendingCloudDelete(cloudId)
            Toast.makeText(this, "已取消同步（云端副本将在联网后删除）", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "已取消同步", Toast.LENGTH_SHORT).show()
        }
        bumpLibrary()
        bumpCloud()
    }

    fun translateAllCloudBook(cloudBook: CloudBook) {
        if (cloudBook.id in cloudSubmittingIds) return
        cloudSubmittingIds.add(cloudBook.id)
        runBackgroundAction("translate-cloud:${cloudBook.id}", useStorageGate = false) {
            try {
                api.translateAllFromZip(cloudBook.id, cloudBook.title, "rtl", null)
                bumpCloud()
            } finally {
                cloudSubmittingIds.remove(cloudBook.id)
            }
        }
    }

    fun stopCloudTranslation(cloudBook: CloudBook) = runBackgroundAction("stop-cloud:${cloudBook.id}", useStorageGate = false) {
        api.cancelBookJobs(cloudBook.id)
        bumpCloud()
    }

    fun deleteCloudBook(cloudBook: CloudBook) = runBackgroundAction("delete-cloud:${cloudBook.id}", useStorageGate = false) {
        val ok = runCatching { api.cloudDelete(cloudBook.id) }.getOrDefault(false)
        if (ok) {
            runCatching { library.cloudCoverFile(cloudBook.id).delete() }
            Toast.makeText(this, "已删除云端书", Toast.LENGTH_SHORT).show()
        } else {
            // 离线：排队补删，本地那本（下载过的话）同时脱钩，不再挂着待删的 cloudId
            recordPendingCloudDelete(cloudBook.id)
            storageGate.withLock { library.detachCloudByCloudId(cloudBook.id) }
            Toast.makeText(this, "当前离线：已排队删除云端书，联网后自动完成", Toast.LENGTH_LONG).show()
        }
        bumpLibrary()
        bumpCloud()
    }

    fun moveCloudBook(cloudBook: CloudBook, folderName: String?) = runBackgroundAction("move-cloud:${cloudBook.id}", useStorageGate = false) {
        // 离线时进待同步队列，下次在线补发，避免云端收藏夹与本地对不上
        storageGate.withLock { library.moveCloudBook(cloudBook.id, folderName) }
        bumpCloud()
    }

    fun moveBookToFolder(bookId: String, folderId: String?) = runBackgroundAction("move-book:$bookId") {
        library.moveBook(bookId, folderId)
        bumpLibrary()
    }

    fun createLibraryFolder(name: String) = runBackgroundAction("create-folder:${name.trim()}") {
        library.createFolder(name)
        bumpLibrary()
    }

    fun renameLibraryFolder(folderId: String, name: String) = runBackgroundAction("rename-folder:$folderId") {
        library.renameFolder(folderId, name)
        bumpLibrary()
    }

    fun deleteLibraryFolder(folderId: String) = runBackgroundAction("delete-folder:$folderId") {
        library.deleteFolder(folderId)
        bumpLibrary()
    }

    /** 收藏夹与云端对齐（本地↔云端并集补建）。离线自动跳过；书库页刷新/后台定时都会调。 */
    fun syncCloudFolders() {
        if (folderSyncRunning) return
        folderSyncRunning = true
        appScope.launch {
            try {
                val changed = syncFoldersNow()
                if (changed) refreshLibraryCache(debounceMillis = 200)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 离线/服务端异常：什么都不做，下次再试
            } finally {
                folderSyncRunning = false
            }
        }
    }

    /** 先补删云端已删的夹，再并集对齐（顺序不能反，否则刚删的夹会被云端拉回本地）。 */
    private suspend fun syncFoldersNow(): Boolean = storageGate.withLock {
        library.drainFolderDeletes()
        library.syncFoldersWithCloud()
    }

    fun renameLibraryBook(bookId: String, title: String) = runBackgroundAction("rename-book:$bookId") {
        library.renameBook(bookId, title)
        bumpLibrary()
    }

    /** 删本地书：★ 本地删除不依赖后台服务——先删本地文件与索引，服务端记录能删就删、
     *  删不掉（离线）记进补删队列下次在线补。已同步的书不动云端副本（serverId=cloudId，删了云端就没了）。 */
    fun deleteLocalBook(book: Book) = runBackgroundAction("delete-book:${book.id}") {
        stopTranslatingIf(book.id)
        runCatching { library.deleteSourceFile(book.hash) }
        library.delete(book.id)
        if (book.cloudId == null) {
            val ok = runCatching { api.deleteBook(book.serverId) }.getOrDefault(false)
            if (!ok) recordPendingDelete(book.serverId)
        }
        Toast.makeText(this, "已删除《${book.title}》", Toast.LENGTH_SHORT).show()
        bumpLibrary()
        bumpCloud()
    }

    fun pingServer() {
        if (backgroundActions["ping-server"]?.running == true) return
        backgroundActions["ping-server"] = BackgroundActionStatus(BackgroundActionState.RUNNING, "正在测试连接…")
        appScope.launch {
            try {
                lastPingResult = pingAndUpdate()
                backgroundActions["ping-server"] = BackgroundActionStatus(
                    BackgroundActionState.DONE,
                    lastPingResult,
                )
            } catch (e: Exception) {
                backgroundActions["ping-server"] = BackgroundActionStatus(
                    BackgroundActionState.FAILED,
                    e.message ?: "测试失败",
                )
            } finally {
                delay(3_000)
                backgroundActions.remove("ping-server")
            }
        }
    }

    private suspend fun scanLibraryNow(): Int {
        if (!libraryScanMutex.tryLock()) return -1
        var announcedAdded = 0
        try {
            libraryScanStatus = LibraryScanStatus(
                true,
                0,
                0,
                0,
                0,
                message = if (storageMigrationStatus?.running == true) "排队中：等待存储搬运" else "正在读取文件夹…",
            )
            val added = storageGate.withLock {
                libraryScanStatus = LibraryScanStatus(true, 0, 0, 0, 0, message = "正在读取文件夹…")
                library.scanLibraryFolder(
                    onProgress = { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            val message = if (progress.total == 0) {
                                "正在读取文件夹，已找到 ${progress.processed} 个文件…"
                            } else {
                                "正在扫描…"
                            }
                            libraryScanStatus = LibraryScanStatus(
                                true,
                                progress.processed,
                                progress.total,
                                progress.added,
                                progress.failed,
                                progress.currentFile,
                                message,
                            )
                        }
                    },
                    onBookImported = { book ->
                        withContext(Dispatchers.Main.immediate) { upsertLibraryBook(book) }
                    },
                )
            }
            libraryScanStatus = LibraryScanStatus(
                false,
                0,
                0,
                added,
                0,
                message = "扫描完成，新导入 $added 本",
            )
            return added
        } catch (e: Exception) {
            libraryScanStatus = LibraryScanStatus(
                false,
                0,
                0,
                0,
                0,
                message = "扫描失败：${e.message}",
            )
            return -1
        } finally {
            libraryScanMutex.unlock()
        }
    }

    fun libraryStorageOptions(): List<LibraryStorageOption> {
        val options = mutableListOf<LibraryStorageOption>()
        getExternalFilesDirs(null).orEmpty().filterNotNull().forEach { dir ->
            runCatching { dir.mkdirs() }
            val removable = Environment.isExternalStorageRemovable(dir)
            options += LibraryStorageOption(
                dir.absolutePath,
                if (removable) "SD 卡" else "内部存储（机身）",
                removable,
                dir.usableSpace,
            )
        }
        return options.ifEmpty { listOf(LibraryStorageOption(filesDir.absolutePath, "内部存储（机身）", false, filesDir.usableSpace)) }
    }

    /** 每下好一页译文图就发一次事件 (bookId, pageIndex)：阅读器按书订阅，免轮询。 */
    private val _pageTranslated = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 64)
    val pageTranslated: SharedFlow<Pair<String, Int>> = _pageTranslated.asSharedFlow()
    private val _pageTranslationFailed = MutableSharedFlow<Triple<String, Int, String?>>()
    val pageTranslationFailed: SharedFlow<Triple<String, Int, String?>> = _pageTranslationFailed.asSharedFlow()

    /** 阅读器页级刷新跑在应用级作用域；退出阅读器只取消 UI 收集，不取消下载。 */
    fun refreshBookFromServer(book: Book) = runBackgroundAction("refresh-book:${book.id}") {
        val done = library.refreshTranslations(book, overwrite = false, priority = null)
        done.forEach { _pageTranslated.tryEmit(book.id to it) }
    }

    /** 单页翻译跑在应用级作用域；退出阅读器后结果仍写入本地缓存。 */
    fun translateBookPage(book: Book, index: Int, force: Boolean) = runBackgroundAction("translate-page:${book.id}:$index") {
        if (!requireOnline()) {
            _pageTranslationFailed.tryEmit(Triple(book.id, index, "未连接"))
            return@runBackgroundAction
        }
        try {
            val image = book.pageFiles[index]
            val jobId = if (book.cloudId != null) {
                api.translateFromServer(book.serverId, index, force)
            } else {
                api.translate(
                    image = image,
                    bookId = book.serverId,
                    pageIndex = index,
                    async = true,
                    force = force,
                ).jobId ?: throw IllegalStateException("无 job_id")
            }

            var finished = false
            var pageId = -1
            var error: String? = null
            var tries = 0
            while (!finished && tries < 640) {
                val status = api.job(jobId)
                when (status.status) {
                    "done" -> { pageId = status.pageId ?: -1; finished = true }
                    "failed" -> { error = status.error ?: "翻译失败"; finished = true }
                }
                if (!finished) {
                    delay(1500)
                    tries++
                }
            }
            if (!finished) error = "超时：翻译未在 16 分钟内完成"
            if (error != null) throw IllegalStateException(error)

            val output = library.translatedCacheFile(book.id, index)
            output.parentFile?.mkdirs()
            api.download(api.translatedUrl(pageId), output)
            _pageTranslated.tryEmit(book.id to index)
        } catch (e: Exception) {
            _pageTranslationFailed.tryEmit(Triple(book.id, index, e.message))
        }
    }

    // ---- 同步到云端 / 云端下载还原（app 级后台，切屏/进阅读器/回桌面都不中断）----

    /** 每本书独立一条同步/下载进度（key=本地书 id 或云端书 id），Compose 可直接观察。 */
    val syncTasks = mutableStateMapOf<String, SyncTask>()

    private fun setSync(id: String, text: String, frac: Float?) {
        syncTasks[id] = SyncTask(text, frac)
    }

    private fun clearSync(id: String) {
        syncTasks.remove(id)
    }

    /** 同步一本书到云端：打包 → 上传 → 记录 cloudId。后台进行，不随页面销毁而中断。 */
    fun syncBook(book: Book) {
        if (!requireOnline()) return
        if (syncTasks.containsKey(book.id)) return   // 同一本正在同步，忽略重复点击
        appScope.launch {
            stopTranslatingIf(book.id)   // 同步会迁移 book_id，正在翻就先停，避免轮询到旧 id
            setSync(book.id, "打包中…", null)
            try {
                val synced = storageGate.withLock {
                    library.sync(book) { text, frac ->
                        // 进度回调在 IO 线程，切回主线程写 Compose 状态
                        appScope.launch { setSync(book.id, text, frac) }
                    }
                }
                Toast.makeText(this@ReaderApp, "已同步《${synced.title}》", Toast.LENGTH_SHORT).show()
                upsertLibraryBook(book)
            } catch (e: CancellationException) {
                throw e   // 应用级作用域被取消=进程退出，不弹「失败」
            } catch (e: Exception) {
                Toast.makeText(this@ReaderApp, "同步失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                clearSync(book.id)
            }
        }
    }

    /** 从云端下载一本书还原到本地（后台进行，不随页面销毁而中断）。 */
    fun downloadCloudBook(cb: CloudBook) {
        if (!requireOnline()) return
        if (syncTasks.containsKey(cb.id)) return
        appScope.launch {
            setSync(cb.id, "下载中…", null)
            try {
                val book = storageGate.withLock {
                    library.downloadCloud(cb) { text, frac ->
                        appScope.launch { setSync(cb.id, text, frac) }
                    }
                }
                Toast.makeText(this@ReaderApp, "已下载《${book.title}》", Toast.LENGTH_SHORT).show()
                bumpLibrary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ReaderApp, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                clearSync(cb.id)
            }
        }
    }

    // ---- WebView 后台下载队列（进度页「下载」区查看全部进度，支持暂停/继续/取消/断点续传）----

    /** 全部后台下载任务（含进行中/暂停/失败），Compose 可直接观察。 */
    val downloadTasks = mutableStateListOf<DownloadTask>()
    private val downloadStops = ConcurrentHashMap<String, AtomicBoolean>()
    private val downloadPersistAt = ConcurrentHashMap<String, Long>()
    private val downloadsPrefKey = "downloads"

    /** 加入后台下载：创建任务后立即开始。 */
    fun enqueueDownload(url: String, name: String, cookie: String?, referer: String?, userAgent: String?) {
        val title = library.titleFromFileName(name)
        val task = DownloadTask(UUID.randomUUID().toString(), url, name, title, cookie, referer, userAgent)
        downloadTasks.add(0, task)   // 新的在最上面
        downloadStops[task.id] = AtomicBoolean(false)
        persistDownloads()
        runDownload(task.id)
    }

    fun pauseDownload(id: String) {
        downloadStops.getOrPut(id) { AtomicBoolean(false) }.set(true)
        // 状态等 worker 抛 CancellationException 后置为 PAUSED
    }

    fun resumeDownload(id: String) {
        downloadStops.remove(id)
        updateDownload(id) { it.copy(status = DownloadStatus.DOWNLOADING, message = "") }
        persistDownloads()
        runDownload(id)
    }

    fun cancelDownload(id: String) {
        downloadStops.getOrPut(id) { AtomicBoolean(false) }.set(true)
        downloadTasks.removeAll { it.id == id }
        partFile(id).delete()
        persistDownloads()
    }

    /** 清除已结束（完成/重复）的下载记录。 */
    fun clearFinishedDownloads() {
        downloadTasks.removeAll { it.status == DownloadStatus.DONE || it.status == DownloadStatus.DUPLICATE || it.status == DownloadStatus.CANCELED }
    }

    private fun partFile(id: String): File =
        File(File(getExternalFilesDir(null), "downloads").apply { mkdirs() }, "$id.part")

    private fun runDownload(taskId: String) {
        appScope.launch {
            try {
                val idx = downloadTasks.indexOfFirst { it.id == taskId }
                if (idx < 0) return@launch
                val task = downloadTasks[idx]
                val part = partFile(taskId)

                val existing = library.bookByTitle(task.title)
                if (existing != null) {
                    part.delete()
                    updateDownload(taskId) {
                        it.copy(status = DownloadStatus.DUPLICATE, message = "本地已有《${existing.title}》")
                    }
                    persistDownloads()
                    delay(3_000)
                    downloadTasks.removeAll { it.id == taskId }
                    persistDownloads()
                    return@launch
                }

                val finalWritten = api.downloadResumable(
                    url = task.url,
                    out = part,
                    resumeFrom = task.written.coerceAtLeast(0),
                    extraHeaders = mapOf(
                        "Cookie" to (task.cookie ?: ""),
                        "Referer" to (task.referer ?: ""),
                        "User-Agent" to (task.userAgent ?: ""),
                    ),
                    shouldStop = { downloadStops[taskId]?.get() == true },
                    onProgress = { w, t ->
                        // 进度回调在 IO 线程：所有 Compose 状态写回必须切回主线程，否则快照竞态会闪退
                        val now = System.currentTimeMillis()
                        if (now - (downloadPersistAt[taskId] ?: 0L) > 200) {
                            downloadPersistAt[taskId] = now
                            appScope.launch {
                                updateDownload(taskId) { it.copy(written = w, total = t) }
                                persistDownloads()
                            }
                        }
                    },
                )

                // 下载完成：落盘到最终文件名 → 导入 → 删暂存（本协程在主线程，直接更新状态）
                updateDownload(taskId) { it.copy(written = finalWritten, status = DownloadStatus.IMPORTING) }
                persistDownloads()
                val (finalFile, results) = storageGate.withLock {
                    val target = File(library.defaultBooksDir(), task.name)
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    target to library.importFiles(target)
                }
                finalFile.delete()
                val added = results.filterNot { it.duplicate }
                val (st, msg) = when {
                    results.size == 1 && added.isEmpty() -> DownloadStatus.DUPLICATE to "本地已有《${results[0].book.title}》"
                    results.size == 1 -> DownloadStatus.DONE to "已导入《${results[0].book.title}》"
                    added.isEmpty() -> DownloadStatus.DUPLICATE to "合集 ${results.size} 本本地都已有"
                    else -> DownloadStatus.DONE to "合集拆分：已导入 ${added.size} 本"
                }
                updateDownload(taskId) { it.copy(status = st, message = msg) }
                persistDownloads()
                if (added.isNotEmpty()) {
                    added.forEach { upsertLibraryBook(it.book) }   // 新书入库：只插入这几项，不全量刷新
                    Toast.makeText(this@ReaderApp, msg, Toast.LENGTH_SHORT).show()
                }
                // 成功/重复保留 10 秒让进度页看到，再自动清掉
                delay(10_000)
                downloadTasks.removeAll { it.id == taskId }
                persistDownloads()
            } catch (e: CancellationException) {
                // 暂停（任务还在）或取消（任务已删）
                if (downloadTasks.any { it.id == taskId }) {
                    // 用 .part 实际字节数作断点（已 flush 的干净边界），续传不再重下尾巴
                    updateDownload(taskId) {
                        it.copy(status = DownloadStatus.PAUSED, written = partFile(taskId).length(), message = "已暂停")
                    }
                    persistDownloads()
                }
            } catch (e: Exception) {
                if (downloadTasks.any { it.id == taskId }) {
                    updateDownload(taskId) { it.copy(status = DownloadStatus.FAILED, message = e.message ?: "下载失败") }
                    persistDownloads()
                }
            } finally {
                downloadStops.remove(taskId)
                downloadPersistAt.remove(taskId)
            }
        }
    }

    private fun updateDownload(id: String, transform: (DownloadTask) -> DownloadTask) {
        val idx = downloadTasks.indexOfFirst { it.id == id }
        if (idx >= 0) downloadTasks[idx] = transform(downloadTasks[idx])
    }

    private fun persistDownloads() {
        val arr = JSONArray()
        for (t in downloadTasks) {
            if (t.status == DownloadStatus.DOWNLOADING || t.status == DownloadStatus.PAUSED || t.status == DownloadStatus.FAILED) {
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("url", t.url)
                    put("name", t.name)
                    put("title", t.title)
                    t.cookie?.let { put("cookie", it) }
                    t.referer?.let { put("referer", it) }
                    t.userAgent?.let { put("user_agent", it) }
                    put("total", t.total)
                    put("written", t.written)
                    put("status", t.status.name)
                    put("message", t.message)
                })
            }
        }
        prefs.edit().putString(downloadsPrefKey, arr.toString()).apply()
    }

    /** 进程重启后恢复未完成的下载（进行中→暂停，保留 .part 断点）。 */
    private fun loadDownloads() {
        val raw = prefs.getString(downloadsPrefKey, null) ?: return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val status = runCatching { DownloadStatus.valueOf(o.optString("status")) }.getOrNull() ?: DownloadStatus.FAILED
            downloadTasks.add(DownloadTask(
                id = id,
                url = o.optString("url"),
                name = o.optString("name"),
                title = o.optString("title"),
                cookie = o.optString("cookie").takeIf { it.isNotBlank() },
                referer = o.optString("referer").takeIf { it.isNotBlank() },
                userAgent = o.optString("user_agent").takeIf { it.isNotBlank() },
                written = o.optLong("written", 0),
                total = o.optLong("total", 0),
                status = if (status == DownloadStatus.DOWNLOADING) DownloadStatus.PAUSED else status,
                message = if (status == DownloadStatus.DOWNLOADING) "上次未完成，可继续下载" else o.optString("message"),
            ))
        }
    }

    // ---- App 自动更新（后端分发 APK，自更新）----

    var updateInfo by mutableStateOf<AppUpdate?>(null)
        private set
    var updateChecking by mutableStateOf(false)
        private set
    var updateDownloading by mutableStateOf(false)
        private set
    var updateDownloadProgress by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    var updateMessage by mutableStateOf("")
        private set
    var updateError by mutableStateOf(false)
        private set

    /** 检查新版本。manual=true 时即使无新版也给出「已是最新」反馈。 */
    fun checkForUpdate(manual: Boolean) {
        if (updateChecking) return
        if (manual && !isNetworkAvailable()) {
            updateMessage = "未连接"
            updateError = false
            return
        }
        updateChecking = true
        if (manual) { updateMessage = "正在检查…"; updateError = false }
        appScope.launch {
            try {
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "universal"
                val info = api.checkUpdate(BuildConfig.VERSION_CODE, abi)
                if (info.latest) {
                    updateInfo = info
                    updateMessage = ""
                    updateError = false
                } else {
                    updateInfo = null
                    if (manual) { updateMessage = "已是最新版本（${BuildConfig.VERSION_NAME}）"; updateError = false }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                if (manual) { updateMessage = "未连接"; updateError = false }
            } catch (e: Exception) {
                if (manual) { updateMessage = "检查失败：${e.message}"; updateError = true }
            } finally {
                updateChecking = false
            }
        }
    }

    /** 下载新版本 APK → 校验 sha256/size → 触发系统安装。 */
    fun startUpdateDownload() {
        val info = updateInfo ?: return
        if (updateDownloading) return
        updateDownloading = true
        updateMessage = "正在下载…"
        updateError = false
        appScope.launch {
            try {
                val dir = File(getExternalFilesDir(null), "updates").apply { mkdirs() }
                val apk = File(dir, "app-${info.versionCode}.apk")
                val part = File(dir, "app-${info.versionCode}.apk.part")
                if (part.exists()) part.delete()
                val url = ServerConfig.baseUrl + info.downloadPath
                api.download(url, part, onProgress = { w, t -> updateDownloadProgress = w to t })
                val sha = sha256Hex(part)
                if (!sha.equals(info.sha256, ignoreCase = true) || part.length() != info.size) {
                    part.delete()
                    throw IllegalStateException("安装包校验失败，请重试")
                }
                if (apk.exists()) apk.delete()
                if (!part.renameTo(apk)) {
                    part.copyTo(apk, overwrite = true)
                    part.delete()
                }
                updateMessage = "下载完成，正在打开安装…"
                updateError = false
                installApk(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                updateMessage = "未连接"
                updateError = false
            } catch (e: Exception) {
                updateMessage = "下载失败：${e.message}"
                updateError = true
            } finally {
                updateDownloading = false
                updateDownloadProgress = null
            }
        }
    }

    /** 快速判断是否有可用网络（离线时立刻提示，不等 HTTP 超时）。 */
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun installApk(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            updateMessage = "请允许「安装未知来源应用」后重试"
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun sha256Hex(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ---- 设备对传（单本，只传渲染译文图）----

    private var transferServer: TransferServer? = null
    private var transferClient: TransferClient? = null

    /** 附近可接收设备（NSD 发现的列表）。 */
    var transferDevices by mutableStateOf<List<DiscoveredDevice>>(emptyList())
        private set
    var transferDiscovering by mutableStateOf(false)
        private set
    /** 发送方：每本书一条分享进度（key = book.id）。 */
    val shareTasks = mutableStateMapOf<String, SyncTask>()
    /** 接收方：当前接收进度。 */
    var incomingTransfer by mutableStateOf<IncomingTransfer?>(null)
        private set

    fun startDeviceDiscovery() {
        transferDevices = emptyList()
        transferDiscovering = true
        transferClient?.startDiscovery()
    }

    fun stopDeviceDiscovery() {
        transferDiscovering = false
        transferClient?.stopDiscovery()
    }

    /** 发送一本书给选中设备（后台进行，书下方进度条展示）。 */
    fun shareBook(book: Book, device: DiscoveredDevice) {
        if (shareTasks.containsKey(book.id)) return
        shareTasks[book.id] = SyncTask("准备中…", null)
        appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val pkg = storageGate.withLock { library.transferPackage(book) }
                    val client = transferClient ?: throw IllegalStateException("传输未初始化")
                    client.send(device, pkg.manifestJson, pkg.files,
                        onStage = { text ->
                            appScope.launch { shareTasks[book.id] = SyncTask(text, null) }
                        },
                        onProgress = { sent, total ->
                            appScope.launch {
                                shareTasks[book.id] = SyncTask(
                                    "发送中…",
                                    if (total > 0) sent.toFloat() / total else null,
                                )
                            }
                        },
                    )
                }
                Toast.makeText(this@ReaderApp, "已发送《${book.title}》", Toast.LENGTH_SHORT).show()
            } catch (e: PeerHasBookException) {
                Toast.makeText(this@ReaderApp, "对端已有这本书", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ReaderApp, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                delay(2000)
                shareTasks.remove(book.id)
            }
        }
    }

    // ---- 阅读进度同步（翻页防抖推后端 + 启动/定时拉后端）----

    private val progressPushJobs = mutableMapOf<String, Job>()

    /** 翻页时记录进度：本地立刻存；按内容 hash 防抖 2 秒推后端（本地书/云端书通用）。 */
    fun recordReadingProgress(book: Book, page: Int) {
        val now = System.currentTimeMillis()
        library.setReadingProgressAt(book.id, page, now)
        val h = book.hash
        if (h.isEmpty()) return
        progressPushJobs[book.id]?.cancel()
        progressPushJobs[book.id] = appScope.launch {
            delay(2000)
            runCatching { api.putReadingProgress(h, page, now) }
        }
    }

    /** 双向同步所有书（按 hash）的进度：远端更新的拉下来，本地更新的推上去（离线读过的也能补推）。 */
    private suspend fun syncReadingProgressFromServer() {
        val list = runCatching { api.getReadingProgressList() }.getOrNull() ?: return
        val remote = mutableMapOf<String, Pair<Int, Long>>()
        for (rp in list) {
            val page = rp.page ?: continue
            val at = rp.lastReadAt ?: continue
            remote[rp.hash] = page to at
        }
        for (b in library.books().filter { it.hash.isNotEmpty() }) {
            val local = library.readingProgress(b.id)
            val r = remote[b.hash]
            when {
                r != null && (local == null || r.second > local.lastReadAt) ->
                    library.setReadingProgressAt(b.id, r.first, r.second)          // 拉远端更新的
                local != null && (r == null || local.lastReadAt > r.second) ->
                    runCatching { api.putReadingProgress(b.hash, local.page, local.lastReadAt) }  // 推本地更新的
            }
        }
    }

    /** 导入后立刻上报书元数据（本地书也注册；离线静默失败，后续 syncBookMetadata 补）。 */
    fun registerBookMetadata(book: Book) {
        if (book.cloudId != null) return   // 云端书走 cloud/books，不重复注册
        appScope.launch {
            runCatching {
                api.upsertBook(book.serverId, book.title, book.pageCount,
                    if (book.mode == ReadingMode.MANGA) "rtl" else "ltr", book.hash)
            }
        }
    }

    /** 补上报本地书元数据（幂等；离线导入的书联网后补进后端 DB）。 */
    private suspend fun syncBookMetadata() {
        for (b in library.books().filter { it.cloudId == null && it.hash.isNotEmpty() }) {
            runCatching {
                api.upsertBook(b.serverId, b.title, b.pageCount,
                    if (b.mode == ReadingMode.MANGA) "rtl" else "ltr", b.hash)
            }
        }
    }

    /** 本地书按内容 hash 匹配已有云端书并挂 cloudId（离线导入的书联网后补挂，之后能拉云端译文）。 */
    private suspend fun syncCloudIdMatch() {
        val list = runCatching { api.cloudList() }.getOrNull() ?: return
        val byHash = list.mapNotNull { c -> c.hash?.takeIf { it.isNotBlank() }?.let { it to c.id } }.toMap()
        if (byHash.isEmpty()) return
        for (b in library.books().filter { it.cloudId == null && it.hash.isNotEmpty() }) {
            val cid = byHash[b.hash] ?: continue
            runCatching { library.attachCloudId(b.id, cid) }
        }
    }

    /** 需要联网的操作先检查；离线时提示并拦截。 */
    private fun requireOnline(): Boolean {
        if (serverOnline) return true
        Toast.makeText(this, "当前离线，无法执行此操作", Toast.LENGTH_SHORT).show()
        return false
    }

    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(this)
        library = LibraryRepository(this)
        currentLibraryStorageDir = libraryStorageOptions()
            .firstOrNull { it.path == ServerConfig.libraryStorageDir }?.path
            ?: libraryStorageOptions().first().path
        loadDownloads()
        resumeTranslatingBook()
        resumeInterruptedStorageMigration()
        resumePendingImports()
        refreshLibraryCache()
        refreshAccountUsage()
        startBackgroundSync()
        startConnectivityMonitor()
        // 设备对传：接收方起本地 HTTP 服务 + NSD 广播；发送方按需发现
        transferClient = TransferClient(
            this,
            onDeviceFound = { d ->
                transferDevices = transferDevices.filterNot { it.name == d.name } + d
            },
            onDiscoveryStopped = { transferDiscovering = false },
        )
        transferServer = TransferServer(
            this,
            existingBookByHash = { hash -> library.findByHash(hash) },
            hasTranslatedPage = { id, idx -> library.hasTranslatedLocal(id, idx) },
            importBook = { staging, manifest ->
                val result = runBlocking {
                    storageGate.withLock { library.importTransferredBook(staging, manifest) }
                }
                val t = result.book.title
                appScope.launch {
                    incomingTransfer = IncomingTransfer(
                        t, 0, 0, true,
                        if (result.duplicate) "已接收《$t》（合并 ${result.addedPages} 页）"
                        else "已接收《$t》（${result.addedPages} 页译文）",
                    )
                    bumpLibrary()
                }
                result
            },
            onIncoming = { _, title -> incomingTransfer = IncomingTransfer(title, 0, 0, false, "正在接收…") },
            onProgress = { _, received, total ->
                incomingTransfer = incomingTransfer?.copy(received = received, total = total, message = "接收中…")
            },
        )
        transferServer?.startReceiver()
        // 冷启动自动查更新（30 分钟内查过就跳过，避免每次启动都请求）
        if (System.currentTimeMillis() - prefs.getLong("last_update_check", 0) > 30 * 60 * 1000) {
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
            checkForUpdate(manual = false)
        }
    }

    /** 只切换之后新导入的位置；旧书继续留在原位置并仍在书库显示。 */
    fun changeLibraryStorage(path: String) {
        if (storageMigrationStatus?.running == true || path == currentLibraryStorageDir) return
        if (libraryStorageOptions().none { it.path == path }) {
            storageMigrationStatus = StorageMigrationStatus(false, 0, 0, message = "存储位置不可用：$path")
            return
        }
        appScope.launch {
            ServerConfig.libraryStorageDir = path
            currentLibraryStorageDir = path
            storageMigrationStatus = StorageMigrationStatus(
                running = false,
                copied = 0,
                total = 0,
                message = "已切换存储位置；新导入会写入这里，旧书仍在原位置显示",
            )
            bumpLibrary()
        }
    }

    /** 手动把旧位置数据搬到当前存储；任务归 Application，退出设置页不会取消。 */
    fun startManualStorageMigration() {
        if (storageMigrationStatus?.running == true) return
        val targetPath = currentLibraryStorageDir ?: return
        prefs.edit().putString("active_storage_migration", targetPath).apply()
        appScope.launch {
            runStorageMigration(targetRoot = File(targetPath), commitPath = null)
        }
    }

    private fun resumeInterruptedStorageMigration() {
        val targetPath = prefs.getString("active_storage_migration", null) ?: return
        if (libraryStorageOptions().none { it.path == targetPath }) {
            storageMigrationStatus = StorageMigrationStatus(
                false,
                0,
                0,
                message = "上次搬运中断；目标存储不可用，插入 SD 卡后会自动继续",
            )
            return
        }
        appScope.launch { runStorageMigration(File(targetPath), commitPath = null) }
    }

    private suspend fun runStorageMigration(targetRoot: File?, commitPath: String?) {
        storageMigrationStatus = StorageMigrationStatus(true, 0, 0, message = "正在检查存储迁移…")
        try {
            val result = storageGate.withLock {
                storageMigrationStatus = StorageMigrationStatus(true, 0, 0, message = "正在等待当前导入/扫描任务结束…")
                library.migrateLegacyStorage(targetRoot) { copied, total ->
                    storageMigrationStatus = StorageMigrationStatus(true, copied, total, message = "正在复制")
                }
            }
            commitPath?.let {
                ServerConfig.libraryStorageDir = it
                currentLibraryStorageDir = it
            }
            val count = result.migratedBooks + result.migratedTranslated
            storageMigrationStatus = StorageMigrationStatus(
                running = false,
                copied = count,
                total = count,
                message = if (count == 0) {
                    "存储数据已在当前位置"
                } else {
                    "搬运完成：${result.migratedBooks} 本书、${result.migratedTranslated} 个译文目录；旧位置已保留"
                },
            )
            if (result.migratedBooks > 0) bumpLibrary()
        } catch (e: Exception) {
            storageMigrationStatus = StorageMigrationStatus(
                running = false,
                copied = 0,
                total = 0,
                message = "存储迁移失败：${e.message}；原位置数据已保留",
            )
            return
        }
        prefs.edit().remove("active_storage_migration").apply()
    }

    /** 轻量同步：补删/补取消/收藏夹/阅读进度/书元数据/cloudId 匹配（不含译文图补拉）。 */
    private suspend fun syncLight() {
        runCatching { drainPendingDeletes() }
        runCatching { drainPendingCancels() }
        runCatching { drainPendingCloudDeletes() }
        runCatching { syncFoldersNow() }
        runCatching { library.drainFolderSyncs() }
        runCatching { syncReadingProgressFromServer() }
        runCatching { syncBookMetadata() }
        runCatching { syncCloudIdMatch() }
    }

    private var lastOnlineSyncAt = 0L

    /** 每 30 秒 ping 一次服务器，更新在线状态；离线→在线时触发一次同步（带 60 秒冷却）。 */
    private fun startConnectivityMonitor() {
        appScope.launch {
            var wasOnline = serverOnline
            while (true) {
                serverOnline = api.ping().startsWith("OK")
                if (!wasOnline && serverOnline && System.currentTimeMillis() - lastOnlineSyncAt > 60_000) {
                    lastOnlineSyncAt = System.currentTimeMillis()
                    runCatching { syncLight() }
                    runCatching { syncAllBooks() }
                }
                wasOnline = serverOnline
                delay(30 * 1000)
            }
        }
    }

    /** 立即 ping 并更新绿点，返回结果文案（设置页「测试连接」用）。 */
    suspend fun pingAndUpdate(): String {
        val r = api.ping()
        serverOnline = r.startsWith("OK")
        return r
    }

    /** 异步刷新在线状态（保存配置后让绿点即时反映新地址连通性）。 */
    fun refreshServerStatus() {
        appScope.launch { serverOnline = api.ping().startsWith("OK") }
    }

    private fun formatAccountBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 打开 App + 定时后台同步：给所有书补拉缺失/变化的译文页（同步书别的设备新翻的、非同步书服务端已翻好的）。
     *  只补差异，不全量，避免卡顿。 */
    private fun startBackgroundSync() {
        backgroundSyncJob = appScope.launch {
            // 启动清理：删冗余 book.src + 失败同步/下载遗留的 zip + 已删书的孤儿目录（幂等）
            runCatching { storageGate.withLock { library.cleanupOrphans() } }
            delay(1500)
            // 打开时：先扫书库文件夹（识别云端书），再同步
            scanLibraryNow()   // 应用级单例扫描；完成时会自行刷新书库
            runCatching { syncLight() }
            runCatching { syncAllBooks() }   // 云端有新译文就补拉（对所有书，不限于新书）
            // 之后每 10 分钟轻量同步；译文补拉降频到每 30 分钟
            var tick = 0
            while (true) {
                delay(10 * 60 * 1000)
                runCatching { syncLight() }
                tick++
                if (tick % 3 == 0) runCatching { syncAllBooks() }   // 每 3 轮 = 30 分钟
            }
        }
    }

    private suspend fun syncAllBooks() {
        val books = storageGate.withLock { library.books() }
        if (books.isEmpty()) return
        syncingTranslations = true
        try {
            for (b in books) {
                // 正在全书翻译的由 translateWholeBook 下载，跳过避免并发写同一文件
                if (b.id in translateQueue) continue
                runCatching { storageGate.withLock { library.refreshTranslations(b, overwrite = false) } }
            }
        } finally {
            syncingTranslations = false
        }
    }

    // ---- 离线删书补删：删本地时服务端删除失败（离线/网络抖），记下 book id，下次在线补删，避免 DB 残留 ----

    private fun pendingDeletes(): MutableList<String> {
        val raw = prefs.getString("pending_deletes", null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun persistPendingDeletes(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("pending_deletes", arr.toString()).apply()
    }

    fun recordPendingDelete(serverBookId: String) {
        val list = pendingDeletes()
        if (serverBookId !in list) list.add(serverBookId)
        persistPendingDeletes(list)
    }

    private suspend fun drainPendingDeletes() {
        val ids = pendingDeletes()
        if (ids.isEmpty()) return
        val remaining = mutableListOf<String>()
        for (id in ids) {
            val ok = runCatching { api.deleteBook(id) }.getOrDefault(false)
            if (!ok) remaining.add(id)
        }
        persistPendingDeletes(remaining)
    }

    // ---- 离线取消同步 / 删云端书补删：云端删除失败（离线）先记下 cloudId，下次在线补删 ----

    private fun pendingCloudDeletes(): MutableList<String> {
        val raw = prefs.getString("pending_cloud_deletes", null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun persistPendingCloudDeletes(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("pending_cloud_deletes", arr.toString()).apply()
    }

    fun recordPendingCloudDelete(cloudBookId: String) {
        val list = pendingCloudDeletes()
        if (cloudBookId !in list) list.add(cloudBookId)
        persistPendingCloudDeletes(list)
    }

    private suspend fun drainPendingCloudDeletes() {
        val ids = pendingCloudDeletes()
        if (ids.isEmpty()) return
        val remaining = mutableListOf<String>()
        var offline = false
        for (id in ids) {
            val ok = !offline && runCatching { api.cloudDelete(id) }.getOrDefault(false)
            if (ok) {
                runCatching { library.cloudCoverFile(id).delete() }
            } else {
                offline = true   // 离线：剩下的一起留着，别一条条干等连接超时
                remaining.add(id)
            }
        }
        persistPendingCloudDeletes(remaining)
    }

    // ---- 离线停止补取消：点停止时服务端取消失败（离线），记下 book id，下次在线补取消，避免服务端继续翻 ----

    private fun pendingCancels(): MutableList<String> {
        val raw = prefs.getString("pending_cancels", null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun persistPendingCancels(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("pending_cancels", arr.toString()).apply()
    }

    fun recordPendingCancel(serverBookId: String) {
        val list = pendingCancels()
        if (serverBookId !in list) list.add(serverBookId)
        persistPendingCancels(list)
    }

    private suspend fun drainPendingCancels() {
        val ids = pendingCancels()
        if (ids.isEmpty()) return
        val remaining = mutableListOf<String>()
        for (id in ids) {
            val ok = runCatching { api.cancelBookJobs(id) }.getOrDefault(false)
            if (!ok) remaining.add(id)
        }
        persistPendingCancels(remaining)
    }

    /** 进程被杀后，下次打开时接着收尾队列里的书（服务端一直在翻，这里补下载）。 */
    private fun resumeTranslatingBook() {
        val ids = prefs.getString("translate_queue", null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrNull()
        } ?: return
        appScope.launch {
            translateQueue.clear()
            ids.forEach { id -> if (library.book(id) != null) translateQueue.add(id) }
            if (translateQueue.isNotEmpty()) {
                persistQueue()
                ensureWorker()
            } else {
                prefs.edit().remove("translate_queue").apply()
            }
        }
    }

    /** 一键全书翻译（后台排队执行）。已在队列/正在翻则忽略；多本书按点击顺序一本本翻。 */
    fun startTranslateAll(book: Book) {
        if (!requireOnline()) return
        if (book.id in translateQueue) return
        translateQueue.add(book.id)
        persistQueue()
        ensureWorker()
    }

    private fun persistQueue() {
        val arr = JSONArray()
        translateQueue.forEach { arr.put(it) }
        prefs.edit().putString("translate_queue", arr.toString()).apply()
    }

    private fun ensureWorker() {
        if (queueWorker?.isActive == true) return
        queueWorker = appScope.launch {
            try {
                while (translateQueue.isNotEmpty()) runOne(translateQueue.first())
            } finally {
                queueWorker = null
            }
        }
    }

    /** 翻队列里的队头那一本。 */
    private suspend fun runOne(bookId: String) {
        val book = storageGate.withLock { library.book(bookId) }
        if (book == null) {
            translateQueue.removeAt(0)
            persistQueue()
            return
        }
        stopRequested = false
        translatingProgress = null   // 真实进度由 translateWholeBook 查完服务端后设置，不闪 0
        try {
            val done = translateWholeBook(book) { d, t ->
                translatingProgress = d to t
                progressHistory[book.id] = d to t
            }
            val msg = if (done >= book.pageCount) "《${book.title}》翻译完成"
            else "《${book.title}》翻译完成 $done/${book.pageCount} 页（失败页可在阅读器内重试）"
            Toast.makeText(this@ReaderApp, msg, Toast.LENGTH_LONG).show()
        } catch (e: CancellationException) {
            if (!stopRequested) throw e   // 应用退出（非用户停止）→ 重新抛出取消整个队列
        } catch (e: Exception) {
            if (!stopRequested) {
                Toast.makeText(this@ReaderApp, "《${book.title}》翻译失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            if (translateQueue.firstOrNull() == bookId) translateQueue.removeAt(0)
            serverJobId = null
            translatingProgress = null
            prefs.edit().remove("uploaded_$bookId").remove("job_id").apply()
            persistQueue()
        }
    }

    /** 删除书 / 取消同步 / 点「停止」前调用：把书移出队列；并取消服务端该书的全部后台任务。 */
    fun stopTranslatingIf(bookId: String) {
        val idx = translateQueue.indexOf(bookId)
        val wasRunning = idx == 0
        if (idx >= 0) translateQueue.removeAt(idx)
        if (wasRunning) {
            // 正在翻：请求停止（轮询循环看到 stopRequested 就退出）
            stopRequested = true
            serverJobId = null
            translatingProgress = null
        }
        prefs.edit().remove("uploaded_$bookId").remove("job_id").apply()
        if (idx >= 0) persistQueue()
        // 按书取消服务端所有 queued/running 任务：覆盖进程被杀后遗留的重复 job（孤儿任务）
        appScope.launch {
            val b = library.book(bookId)
            if (b != null) {
                val ok = runCatching { api.cancelBookJobs(b.serverId) }.getOrDefault(false)
                if (!ok) recordPendingCancel(b.serverId)   // 离线/失败：记下，下次在线补取消
            }
        }
    }

    /**
     * 全书翻译：
     * 1) 上传一次没翻过的页（成功后记 uploaded=true，避免重启后再传一遍）；
     * 2) 轮询服务端进度，把翻好的页下载到本地缓存。
     * 服务端已经用后台任务在翻，App 关掉也不影响它，这里只是收结果。
     */
    private suspend fun translateWholeBook(book: Book, onProgress: (Int, Int) -> Unit): Int {
        val total = book.pageCount
        val serverId = book.serverId
        val upKey = "uploaded_${book.id}"   // 每本书独立的上传标记，避免 A 书中断影响 B 书

        var lastDone = -1
        var uploaded = prefs.getBoolean(upKey, false)

        // 外层重试：网络错误（离线/服务端不可用）不退出、不把书移出队列，等几秒重试
        while (true) {
            if (stopRequested) throw CancellationException("用户停止")
            try {
                val existing = api.bookPages(serverId).associateBy { it.pageIndex }
                lastDone = existing.values.count { it.status == "done" }
                onProgress(lastDone, total)

                if (!uploaded) {
                    val pending = (0 until total).filter { existing[it]?.status != "done" }
                    if (pending.isNotEmpty()) {
                        val orderDir = if (book.mode == ReadingMode.MANGA) "rtl" else "ltr"
                        serverJobId = if (book.cloudId != null) {
                            // 已同步：服务端从云端 zip 自取图，不上传页图
                            api.translateAllFromZip(serverId, book.title, orderDir, pending)
                        } else {
                            api.translateAll(serverId, book.title, orderDir, total, pending, pending.map { book.pageFiles[it] })
                        }
                        // 持久化 job_id，重启恢复后「停止」仍能取消服务端任务
                        serverJobId?.let { prefs.edit().putString("job_id", it).apply() }
                    }
                    uploaded = true
                    prefs.edit().putBoolean(upKey, true).apply()
                }

                // 轮询服务端进度，把翻好的页下载到本地缓存
                while (true) {
                    if (stopRequested) throw CancellationException("用户停止")
                    val pages = api.bookPages(serverId)
                    var done = 0
                    for (p in pages) {
                        if (p.status == "done") {
                            val f = library.translatedCacheFile(book.id, p.pageIndex)
                            if (!f.exists() || f.length() == 0L) {
                                // 容错：下载失败（如服务端文件还没落盘的瞬时 404）不打断整本，下一轮重试
                                runCatching { library.downloadTranslatedPage(book.id, p.id, p.pageIndex) }
                                    .onSuccess { _pageTranslated.tryEmit(book.id to p.pageIndex) }
                            }
                            done++
                        }
                    }
                    if (done != lastDone) {
                        lastDone = done
                        onProgress(done, total)
                    }
                    val settled = pages.count { it.status == "done" || it.status == "failed" }
                    if (pages.size >= total && settled >= total) return lastDone
                    delay(2000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // 网络错误/离线：等 5 秒重试，不退出、不把书移出队列（否则离线启动会把队列清空）
                delay(5000)
            } catch (e: Exception) {
                // 其它错误（HTTP 4xx/5xx 等）：向上抛，让 runOne 显示失败
                throw e
            }
        }
    }
}
