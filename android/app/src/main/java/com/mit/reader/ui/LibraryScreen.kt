package com.mit.reader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mit.reader.DownloadStatus
import com.mit.reader.DownloadTask
import com.mit.reader.ReaderApp
import com.mit.reader.SyncTask
import com.mit.reader.data.Book
import com.mit.reader.data.CloudBook
import com.mit.reader.data.Folder
import com.mit.reader.data.ReadingMode
import com.mit.reader.data.ReadingProgress
import com.mit.reader.data.ServerBook
import com.mit.reader.data.serverId
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/** 顶栏动作槽宽度：等距间距由它决定（越小越紧凑）。想调间距改这一个值即可。 */
private val TOP_ACTION_WIDTH = 40.dp

private val CloudVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "Cloud",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(19f)
            curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            moveTo(19f, 18f)
            horizontalLineTo(6f)
            curveTo(3.79f, 18f, 2f, 16.21f, 2f, 14f)
            reflectiveCurveTo(3.79f, 10f, 6f, 10f)
            horizontalLineTo(6.71f)
            curveTo(7.37f, 7.69f, 9.48f, 6f, 12f, 6f)
            curveTo(15.04f, 6f, 17.5f, 8.46f, 17.5f, 11.5f)
            verticalLineTo(12f)
            horizontalLineTo(19f)
            curveTo(20.66f, 12f, 22f, 13.34f, 22f, 15f)
            reflectiveCurveTo(20.66f, 18f, 19f, 18f)
            close()
        }
    }.build()
}

/** 顶栏动作槽：固定宽度 + 内容居中 + 点击水波纹（比 IconButton 默认 48dp 紧凑，便于等距）。 */
@Composable
private fun TopBarAction(
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = Modifier.size(width = TOP_ACTION_WIDTH, height = 48.dp)
    Box(
        modifier = if (onClick != null) base.clip(CircleShape).clickable(onClick = onClick) else base,
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", n / (1L shl 10).toDouble())
    else -> "$n B"
}

/** 书库网格里的一项：要么是本地书（带同步状态），要么是「仅云端」的云端书。 */
private sealed class LibraryEntry {
    data class Local(val book: Book, val synced: Boolean) : LibraryEntry()
    data class Cloud(val book: CloudBook) : LibraryEntry()
}

/** 云端书在服务端的全书翻译状态，用于书库卡片进度与防重复提交。 */
private data class CloudTranslationState(
    val status: String?,
    val done: Int,
    val failed: Int,
    val total: Int,
) {
    val active: Boolean get() = status in setOf("submitting", "queued", "running", "active")
}

/** 书库排序方式。 */
private enum class SortMode(val label: String) {
    NAME("名称"), RECENT("最近阅读"), CREATED("创建时间")
}

/** 书库内显示的书籍来源；「云端」包含已同步的本地书和仅云端书。 */
private enum class LibraryFilter(val label: String) {
    ALL("全部"), LOCAL("本地"), CLOUD("云端")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalTaskBanner(app: ReaderApp) {
    val scan = app.libraryScanStatus?.takeIf { it.running }
    val migration = app.storageMigrationStatus?.takeIf { it.running }
    val imports = app.libraryImports.values.filter { it.running }
    if (scan == null && migration == null && imports.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            scan?.let {
                val progressText = when {
                    it.total > 0 -> "${it.processed}/${it.total}"
                    it.processed > 0 -> "已找到 ${it.processed} 个文件"
                    else -> null
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = listOfNotNull(it.message, progressText, "已新增 ${it.added} 本")
                            .joinToString("，"),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (it.total > 0) {
                    LinearProgressIndicator(
                        progress = { it.processed.toFloat() / it.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            migration?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (scan != null) 4.dp else 0.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "${it.message} ${it.copied}/${it.total}",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            imports.forEach {
                Text(
                    text = it.message,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onKmoe: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val activity = context.findActivity()
    val books = app.libraryBooks
    val folders = app.libraryFolders
    var refreshing by remember { mutableStateOf(0) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var libraryFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var cloudBooks by remember { mutableStateOf<List<CloudBook>>(emptyList()) }
    var cloudErr by remember { mutableStateOf<String?>(null) }
    var serverBooks by remember { mutableStateOf<List<ServerBook>>(emptyList()) }
    var progressErr by remember { mutableStateOf<String?>(null) }
    var gridMode by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var viewMenuOpen by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<Book?>(null) }
    var moveCloudTarget by remember { mutableStateOf<CloudBook?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    var confirmCancelSync by remember { mutableStateOf<Book?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<Folder?>(null) }
    var folderMenuTarget by remember { mutableStateOf<Folder?>(null) }
    var renameTarget by remember { mutableStateOf<Folder?>(null) }
    var renameBookTarget by remember { mutableStateOf<Book?>(null) }
    var shareTarget by remember { mutableStateOf<Book?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showExitDialog by remember { mutableStateOf(false) }
    val lastRead = app.libraryLastRead

    /** 按当前排序方式排本地书。 */
    fun sortedBooks(list: List<Book>): List<Book> = when (sortMode) {
        SortMode.NAME -> list.sortedBy { it.title }
        SortMode.RECENT -> list.sortedByDescending { app.library.readingProgress(it.id)?.lastReadAt ?: 0L }
        SortMode.CREATED -> list.sortedByDescending { it.createdAt }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        app.importDocument(uri, currentFolderId)
    }

    // 导入「单层纯图片文件夹」：解压后的合集文件夹直接选它 = 一本书（含子文件夹会被拒绝）
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        app.importFolder(uri, currentFolderId)
    }

    LaunchedEffect(Unit) { app.refreshLibraryCache() }

    // 打开「分享/对传」设备选择框时开始发现附近设备，关闭时停止
    LaunchedEffect(shareTarget) {
        if (shareTarget != null) app.startDeviceDiscovery()
    }

    // 后台导入新书（下载完成 / 扫描文件夹）后自动刷新书库，不用等 60 秒定时刷新

    // 云端列表：进入/刷新/切 tab 时拉一次（书库筛选同步状态时要用）
    LaunchedEffect(refreshing, tab) {
        runCatching { app.api.cloudList() }
            .onSuccess {
                if (cloudBooks != it) cloudBooks = it
                cloudErr = null
                // 云端收藏夹拉到本地（并集补建）：下载云端书才能落进对应收藏夹；本地新建的夹也推上云
                app.syncCloudFolders()
            }
            .onFailure { cloudErr = it.message }
    }

    // 书库卡片和进度页共用真实服务端 job 状态；全局串行 worker 中只能一本 running。
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { app.api.listBooks() }
                .onSuccess {
                    if (serverBooks != it) serverBooks = it
                    progressErr = null
                }
                .onFailure { progressErr = it.message }
            delay(2_000)
        }
    }

    // 定时刷新云端列表：多设备增删书 / 同步状态变化能及时看到
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            app.refreshLibraryCache()
        }
    }

    fun doSync(book: Book) {
        app.syncBook(book)   // app 级后台：进阅读器/切屏也不中断
    }

    fun performCancelSync(book: Book) {
        app.cancelSyncBook(book)
    }

    fun doCancelSync(book: Book) {
        if (app.translatingBookId == book.id) {
            confirmCancelSync = book   // 正在翻译：先弹确认
        } else {
            performCancelSync(book)
        }
    }

    fun doDownloadCloud(cb: CloudBook) {
        app.downloadCloudBook(cb)   // app 级后台：进阅读器/切屏也不中断
    }

    /** 云端书（未下载到本地）全书翻译：服务端直接从云端 zip 取图翻译，不上传图片。 */
    fun doTranslateAllCloud(cb: CloudBook) {
        val serverBook = serverBooks.firstOrNull { it.id == cb.id }
        val total = cb.pageCount ?: serverBook?.pageCount ?: 0
        val done = serverBook?.donePages ?: 0
        if (total > 0 && done >= total) {
            Toast.makeText(app, "《${cb.title ?: "未命名"}》已全部翻译完成", Toast.LENGTH_SHORT).show()
            return
        }
        val alreadyActive = cb.id in app.cloudSubmittingIds ||
            serverBook?.jobStatus in setOf("queued", "running") ||
            (serverBook?.activeJobs ?: 0) > 0
        if (alreadyActive) {
            Toast.makeText(app, "《${cb.title ?: "未命名"}》已有翻译任务", Toast.LENGTH_SHORT).show()
            return
        }
        app.translateAllCloudBook(cb)
    }

    /** 停止云端书（未下载）的后台翻译任务。 */
    fun doStopCloud(cb: CloudBook) {
        app.stopCloudTranslation(cb)
    }

    fun doDeleteCloud(cb: CloudBook) {
        app.deleteCloudBook(cb)
    }

    // 书库页按系统返回：先退搜索/文件夹，否则弹确认退出
    BackHandler {
        when {
            searchActive -> { searchActive = false; searchQuery = "" }
            tab == 0 && currentFolderId != null -> currentFolderId = null
            else -> showExitDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索书名 / 收藏夹") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        val titleText = when {
                            tab == 1 -> "进度"
                            currentFolderId != null -> {
                                val folder = folders.find { it.id == currentFolderId }
                                if (folder != null) {
                                    val localInFolder = books.filter { it.folderId == currentFolderId }
                                    // 云端书只算「仅云端」的，避免同步+本地同一本被数两次
                                    val cloudOnlyInFolder = cloudBooks.filter { cb ->
                                        books.none { it.cloudId == cb.id } && cb.folder == folder.name
                                    }
                                    val knownCloudIds = cloudBooks.map { it.id }.toSet()
                                    val n = when (libraryFilter) {
                                        LibraryFilter.ALL -> localInFolder.size + cloudOnlyInFolder.size
                                        LibraryFilter.LOCAL -> localInFolder.size
                                        LibraryFilter.CLOUD ->
                                            localInFolder.count { it.cloudId != null && it.cloudId in knownCloudIds } +
                                                cloudOnlyInFolder.size
                                    }
                                    "${folder.name}（$n）"
                                } else "书库"
                            }
                            else -> "书库"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(titleText, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                            TextButton(onClick = onKmoe) { Text("Kmoe") }
                        }
                    }
                },
                navigationIcon = {
                    when {
                        searchActive -> IconButton(onClick = { searchActive = false; searchQuery = "" }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "退出搜索")
                        }
                        tab == 1 -> Unit   // 进度是全局视图，不显示文件夹返回箭头
                        currentFolderId != null -> IconButton(onClick = { currentFolderId = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (searchActive) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, contentDescription = "清空") }
                    } else {
                        // 固定 5 个槽位（宽度 TOP_ACTION_WIDTH，比 IconButton 的 48dp 紧凑）：
                        // 同步（最左）→ 搜索 → 排序 → 服务器状态 → 设置。
                        // 每格内容盒固定 24dp（与图标同宽），所以等距；想调紧/调松改 TOP_ACTION_WIDTH 一个值即可。
                        TopBarAction {
                            if (app.syncingTranslations) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                        // 搜索 + 排序：仅书库页；进度页不显示（绿点/设置在右边，位置不受影响）
                        if (tab == 0) {
                            TopBarAction(onClick = { searchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "搜索")
                            }
                            Box {
                                TopBarAction(onClick = { viewMenuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "视图与排序")
                                }
                                DropdownMenu(expanded = viewMenuOpen, onDismissRequest = { viewMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (gridMode) "列表视图" else "网格视图") },
                                        onClick = { gridMode = !gridMode; viewMenuOpen = false },
                                    )
                                    HorizontalDivider()
                                    SortMode.entries.forEach { m ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(m.label, modifier = Modifier.weight(1f))
                                                    if (sortMode == m) {
                                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            },
                                            onClick = { sortMode = m; viewMenuOpen = false },
                                        )
                                    }
                                }
                            }
                        }
                        // 绿点=在线，红点=离线；点一下立即重新检测
                        TopBarAction(onClick = { app.refreshServerStatus() }) {
                            // 内容盒 24dp（与图标同宽）：绿点居中，左右视觉间距和相邻图标一致
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (app.serverOnline) Color(0xFF43A047) else Color(0xFFD32F2F)),
                                )
                            }
                        }
                        TopBarAction(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                var importMenuOpen by remember { mutableStateOf(false) }
                Box {
                    FloatingActionButton(onClick = { importMenuOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "导入")
                    }
                    DropdownMenu(expanded = importMenuOpen, onDismissRequest = { importMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("导入文件（EPUB / MOBI / 压缩包）") },
                            onClick = { importMenuOpen = false; importLauncher.launch(arrayOf("*/*")) },
                        )
                        DropdownMenuItem(
                            text = { Text("导入文件夹（单层纯图片）") },
                            onClick = { importMenuOpen = false; importFolderLauncher.launch(null) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            GlobalTaskBanner(app)
            app.incomingTransfer?.let { inc ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!inc.done) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                inc.message,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (!inc.done && inc.total > 0) {
                            LinearProgressIndicator(
                                progress = { (inc.received.toFloat() / inc.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            if (searchActive) {
                SearchResults(
                    query = searchQuery,
                    books = books,
                    cloudBooks = cloudBooks,
                    folders = folders,
                    onOpenBook = { id -> searchActive = false; searchQuery = ""; onOpen(id) },
                    onEnterFolder = { id -> searchActive = false; searchQuery = ""; currentFolderId = id },
                    onDownloadCloud = { doDownloadCloud(it) },
                    downloadingCloudIds = app.syncTasks.keys.toSet(),
                )
            } else {
                TabRow(selectedTabIndex = tab) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0; filterMenuOpen = false },
                            text = { Text("书库 · ${libraryFilter.label}") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 8.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable {
                                    tab = 0
                                    filterMenuOpen = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "▾",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            DropdownMenu(
                                expanded = filterMenuOpen,
                                onDismissRequest = { filterMenuOpen = false },
                            ) {
                                LibraryFilter.entries.forEach { filter ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(filter.label, modifier = Modifier.weight(1f))
                                                if (libraryFilter == filter) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            libraryFilter = filter
                                            filterMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1; filterMenuOpen = false; viewMenuOpen = false },
                        text = { Text("进度") },
                    )
                }
                if (tab == 1) {
                    ProgressList(
                        books = books,
                        cloudBooks = cloudBooks,
                        serverBooks = serverBooks,
                        error = progressErr,
                        translatingBookId = app.translatingBookId,
                        translatingProgress = app.translatingProgress,
                        queuedBookIds = app.queuedBookIds.toSet(),
                        progressHistory = app.progressHistory,
                        onStop = { app.stopTranslatingIf(it.id) },
                        onStopCloud = { doStopCloud(it) },
                    )
                } else {
                    val cloudIds = cloudBooks.map { it.id }.toSet()
                    val localEntries = sortedBooks(books).map { b ->
                        LibraryEntry.Local(b, b.cloudId != null && b.cloudId in cloudIds)
                    }
                    val cloudOnly = cloudBooks.filter { cb -> books.none { it.cloudId == cb.id } }
                        .map { LibraryEntry.Cloud(it) }
                    val shownEntries = when (libraryFilter) {
                        LibraryFilter.ALL -> localEntries + cloudOnly
                        LibraryFilter.LOCAL -> localEntries
                        LibraryFilter.CLOUD -> localEntries.filter { it.synced } + cloudOnly
                    }
                    val serverById = serverBooks.associateBy { it.id }
                    val cloudTranslationStates = cloudBooks.associate { cb ->
                        val serverBook = serverById[cb.id]
                        val status = when {
                            cb.id in app.cloudSubmittingIds -> "submitting"
                            !serverBook?.jobStatus.isNullOrBlank() -> serverBook?.jobStatus
                            (serverBook?.activeJobs ?: 0) > 0 -> "active"
                            else -> null
                        }
                        cb.id to CloudTranslationState(
                            status = status,
                            done = serverBook?.donePages ?: 0,
                            failed = serverBook?.failedPages ?: 0,
                            total = cb.pageCount ?: serverBook?.pageCount ?: 0,
                        )
                    }
                    // 已全部翻完的书（服务端 done == 总数）：菜单里「全书翻译」直接显示「已完成」，不再重新提交
                    val completedServerIds = serverBooks.filter {
                        (it.pageCount ?: 0) > 0 && it.donePages >= (it.pageCount ?: 0)
                    }.map { it.id }.toSet()
                    LibraryTab(
                        entries = shownEntries,
                        folders = folders,
                        cloudTranslationStates = cloudTranslationStates,
                        completedServerIds = completedServerIds,
                        currentFolderId = currentFolderId,
                        lastRead = lastRead,
                        cloudErr = cloudErr,
                        onOpen = onOpen,
                        onEnterFolder = { currentFolderId = it },
                        onNewFolder = { showCreateFolder = true },
                        onMoveBook = { moveTarget = it },
                        onMoveToFolder = { book, fid ->
                            app.moveBookToFolder(book.id, fid)
                        },
                        onMoveOut = { book ->
                            app.moveBookToFolder(book.id, null)
                        },
                        onDelete = { confirmDelete = it },
                        onRenameBook = { renameBookTarget = it },
                        onFolderMenu = { folderMenuTarget = it },
                        onTranslateAll = { app.startTranslateAll(it) },
                        onSync = { doSync(it) },
                        onCancelSync = { doCancelSync(it) },
                        onDownloadCloud = { doDownloadCloud(it) },
                        onDeleteCloud = { doDeleteCloud(it) },
                        onTranslateAllCloud = { doTranslateAllCloud(it) },
                        onMoveCloud = { moveCloudTarget = it },
                        onShare = { shareTarget = it },
                        translatingBookId = app.translatingBookId,
                        translatingProgress = app.translatingProgress,
                        queuedBookIds = app.queuedBookIds.toSet(),
                        syncTasks = app.syncTasks,
                        shareTasks = app.shareTasks,
                        gridMode = gridMode,
                    )
                }
            }
        }
    }

    // ---- 移动对话框 ----
    moveTarget?.let { book ->
        MoveBookDialog(
            title = book.title,
            inFolder = book.folderId != null,
            folders = folders,
            onMove = { folder -> app.moveBookToFolder(book.id, folder?.id); moveTarget = null },
            onDismiss = { moveTarget = null },
        )
    }

    // ---- 移动云端书对话框（按云端收藏夹名同步）----
    moveCloudTarget?.let { cb ->
        MoveBookDialog(
            title = cb.title ?: "未命名",
            inFolder = !cb.folder.isNullOrBlank(),
            folders = folders,
            onMove = { folder ->
                app.moveCloudBook(cb, folder?.name)
                moveCloudTarget = null
            },
            onDismiss = { moveCloudTarget = null },
        )
    }

    // ---- 新建收藏夹 ----
    if (showCreateFolder) {
        CreateFolderDialog(
            onCreate = { name -> app.createLibraryFolder(name); showCreateFolder = false },
            onDismiss = { showCreateFolder = false },
        )
    }

    // ---- 删除书：删本地书 + 本地译文 + BOOKS 源文件；云端副本由「取消同步」单独管理 ----
    confirmDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除《${book.title}》？") },
            text = {
                Text(
                    if (book.cloudId != null)
                        "删除本地的书、译文缓存与书库文件夹里的源文件；云端副本保留（要删云端请用「取消同步」）。"
                    else
                        "删除本地的书、译文缓存与书库文件夹里的源文件。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val b = book
                    confirmDelete = null
                    app.deleteLocalBook(b)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }

    // ---- 取消同步：正在翻译时先确认（确认后先停 job 再删云端）----
    confirmCancelSync?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmCancelSync = null },
            title = { Text("正在翻译中") },
            text = { Text("《${book.title}》正在全书翻译。确认停止翻译并取消同步吗？（将删除云端副本与翻译结果）") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelSync = null
                    performCancelSync(book)
                }) { Text("确认停止并取消同步") }
            },
            dismissButton = { TextButton(onClick = { confirmCancelSync = null }) { Text("取消") } },
        )
    }

    // ---- 删除收藏夹 ----
    confirmDeleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFolder = null },
            title = { Text("删除收藏夹「${folder.name}」？") },
            text = { Text("里面的书会回到「未分类」，不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val f = folder
                    confirmDeleteFolder = null
                    app.deleteLibraryFolder(f.id)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFolder = null }) { Text("取消") } },
        )
    }

    // ---- 收藏夹菜单（重命名 / 删除）----
    folderMenuTarget?.let { folder ->
        FolderMenuDialog(
            folder = folder,
            onRename = { folderMenuTarget = null; renameTarget = folder },
            onDelete = { folderMenuTarget = null; confirmDeleteFolder = folder },
            onDismiss = { folderMenuTarget = null },
        )
    }

    // ---- 重命名收藏夹 ----
    renameTarget?.let { folder ->
        RenameFolderDialog(
            folder = folder,
            onRename = { name -> app.renameLibraryFolder(folder.id, name); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }

    // ---- 重命名书 ----
    renameBookTarget?.let { book ->
        RenameBookDialog(
            book = book,
            onRename = { name -> app.renameLibraryBook(book.id, name); renameBookTarget = null },
            onDismiss = { renameBookTarget = null },
        )
    }

    // ---- 分享/对传：选择接收设备 ----
    shareTarget?.let { book ->
        val devices = app.transferDevices
        val discovering = app.transferDiscovering
        AlertDialog(
            onDismissRequest = { shareTarget = null; app.stopDeviceDiscovery() },
            title = { Text("分享《${book.title}》") },
            text = {
                Column {
                    Text(
                        "选择接收设备（两台设备需连同一个 WiFi）。对方 App 打开即自动接收。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (devices.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            if (discovering) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                if (discovering) " 正在搜索附近设备…" else " 未发现设备，请确认对方已打开本 App",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        devices.forEach { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    shareTarget = null
                                    app.stopDeviceDiscovery()
                                    app.shareBook(book, d)
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(d.name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shareTarget = null; app.stopDeviceDiscovery() }) { Text("取消") }
            },
        )
    }

    // ---- 退出确认 ----
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出应用？") },
            text = { Text("确定要退出吗？") },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text("取消") } },
        )
    }

}

/** 从 Context 链上找到宿主 Activity（用于退出应用）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun coverRequest(file: File): ImageRequest {
    val context = LocalContext.current
    return remember(file.absolutePath, file.lastModified()) {
        val cacheKey = "${file.absolutePath}|${file.lastModified()}|cover"
        ImageRequest.Builder(context)
            .data(file)
            .size(360, 500)
            .crossfade(false)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }
}

/** 「书库」页：根视图 = 上次阅读 + 收藏夹区 + 未分类书 + 仅云端书；点进收藏夹 = 只看该文件夹里的书。 */
@Composable
private fun LibraryTab(
    entries: List<LibraryEntry>,
    folders: List<Folder>,
    cloudTranslationStates: Map<String, CloudTranslationState>,
    completedServerIds: Set<String>,
    currentFolderId: String?,
    lastRead: Pair<Book, ReadingProgress>?,
    cloudErr: String?,
    onOpen: (String) -> Unit,
    onEnterFolder: (String) -> Unit,
    onNewFolder: () -> Unit,
    onMoveBook: (Book) -> Unit,
    onMoveToFolder: (Book, String) -> Unit,
    onMoveOut: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onRenameBook: (Book) -> Unit,
    onFolderMenu: (Folder) -> Unit,
    onTranslateAll: (Book) -> Unit,
    onSync: (Book) -> Unit,
    onCancelSync: (Book) -> Unit,
    onDownloadCloud: (CloudBook) -> Unit,
    onDeleteCloud: (CloudBook) -> Unit,
    onTranslateAllCloud: (CloudBook) -> Unit,
    onMoveCloud: (CloudBook) -> Unit,
    onShare: (Book) -> Unit,
    translatingBookId: String?,
    translatingProgress: Pair<Int, Int>?,
    queuedBookIds: Set<String>,
    syncTasks: Map<String, SyncTask>,
    shareTasks: Map<String, SyncTask>,
    gridMode: Boolean,
) {
    val localBooks = entries.mapNotNull { (it as? LibraryEntry.Local)?.book }
    val cloudOnly = entries.mapNotNull { (it as? LibraryEntry.Cloud)?.book }
    val syncedByBookId = entries.mapNotNull { e ->
        (e as? LibraryEntry.Local)?.let { it.book.id to it.synced }
    }.toMap()
    // 云端书按 folder 名分桶（与本地收藏夹名对齐）；没匹配到本地夹的归「未分类」
    val cloudByFolderName = cloudOnly.groupBy { it.folder?.takeIf { f -> f.isNotBlank() } ?: "" }
    val cloudUnfiled = cloudOnly.filter { cb ->
        val name = cb.folder ?: ""
        name.isBlank() || folders.none { it.name == name }
    }
    // 收藏夹展开状态提到这里：切网格/列表时不重置
    var foldersExpanded by remember { mutableStateOf(true) }

    if (currentFolderId != null) {
        val folder = folders.find { it.id == currentFolderId } ?: return
        val inBooks = localBooks.filter { it.folderId == currentFolderId }
        val inCloud = cloudByFolderName[folder.name] ?: emptyList()
        if (inBooks.isEmpty() && inCloud.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("「${folder.name}」是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "回到书库，长按漫画即可移进这里",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return
        }
        if (gridMode) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                gridItems(inBooks, key = { it.id }) { book ->
                    BookCell(
                        book = book,
                        synced = syncedByBookId[book.id] == true,
                        inFolder = true,
                        onOpen = { onOpen(book.id) },
                        onLongPress = { onMoveBook(book) },
                        onTranslateAll = { onTranslateAll(book) },
                        onMove = { onMoveBook(book) },
                        onMoveOut = { onMoveOut(book) },
                        onDelete = { onDelete(book) },
                        onRename = { onRenameBook(book) },
                        onSync = { onSync(book) },
                        onCancelSync = { onCancelSync(book) },
                        onShare = { onShare(book) },
                        isTranslating = translatingBookId == book.id,
                        isQueued = book.id in queuedBookIds,
                        isCompleted = book.serverId in completedServerIds,
                        progressText = if (translatingBookId == book.id) translatingProgress?.let { "${it.first}/${it.second}" } else null,
                        syncTasks = syncTasks,
                        shareTasks = shareTasks,
                    )
                }
                gridItems(inCloud, key = { it.id }) { cb ->
                    CloudCell(
                        cloud = cb,
                        translation = cloudTranslationStates[cb.id],
                        syncTasks = syncTasks,
                        onDownload = { onDownloadCloud(cb) },
                        onDelete = { onDeleteCloud(cb) },
                        onTranslateAll = { onTranslateAllCloud(cb) },
                        onMove = { onMoveCloud(cb) },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listItems(inBooks, key = { it.id }) { book ->
                    BookRow(
                        book = book,
                        synced = syncedByBookId[book.id] == true,
                        inFolder = true,
                        onOpen = { onOpen(book.id) },
                        onLongPress = { onMoveBook(book) },
                        onTranslateAll = { onTranslateAll(book) },
                        onMove = { onMoveBook(book) },
                        onMoveOut = { onMoveOut(book) },
                        onDelete = { onDelete(book) },
                        onRename = { onRenameBook(book) },
                        onSync = { onSync(book) },
                        onCancelSync = { onCancelSync(book) },
                        onShare = { onShare(book) },
                        isTranslating = translatingBookId == book.id,
                        isQueued = book.id in queuedBookIds,
                        isCompleted = book.serverId in completedServerIds,
                        progressText = if (translatingBookId == book.id) translatingProgress?.let { "${it.first}/${it.second}" } else null,
                        syncTasks = syncTasks,
                        shareTasks = shareTasks,
                    )
                }
                listItems(inCloud, key = { it.id }) { cb ->
                    CloudRow(
                        cloud = cb,
                        translation = cloudTranslationStates[cb.id],
                        syncTasks = syncTasks,
                        onDownload = { onDownloadCloud(cb) },
                        onDelete = { onDeleteCloud(cb) },
                        onTranslateAll = { onTranslateAllCloud(cb) },
                        onMove = { onMoveCloud(cb) },
                    )
                }
            }
        }
        return
    }

    val unFiled = localBooks.filter { it.folderId == null }
    // 完全空（没本地书也没收藏夹）才显示引导；有收藏夹时即使没书也要把收藏夹展示出来
    if (localBooks.isEmpty() && folders.isEmpty() && cloudOnly.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("点右下角 + 导入文件 / 图片文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onNewFolder) { Text("新建收藏夹") }
            }
        }
        return
    }
    // 云端 tab 且云端列表拉取失败时，给个明确提示
    if (cloudErr != null && localBooks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载云端失败：$cloudErr", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    if (gridMode) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (lastRead != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ContinueReadingCard(
                        book = lastRead.first,
                        page = lastRead.second.page,
                        onContinue = { onOpen(lastRead.first.id) },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FoldersSection(
                    folders = folders,
                    books = localBooks,
                    cloudByFolderName = cloudByFolderName,
                    gridMode = true,
                    expanded = foldersExpanded,
                    onToggleExpanded = { foldersExpanded = !foldersExpanded },
                    onEnterFolder = onEnterFolder,
                    onNewFolder = onNewFolder,
                    onFolderMenu = onFolderMenu,
                )
            }
            if (unFiled.isNotEmpty()) {
                gridItems(unFiled, key = { it.id }) { book ->
                    BookCell(
                        book = book,
                        synced = syncedByBookId[book.id] == true,
                        inFolder = false,
                        onOpen = { onOpen(book.id) },
                        onLongPress = { onMoveBook(book) },
                        onTranslateAll = { onTranslateAll(book) },
                        onMove = { onMoveBook(book) },
                        onDelete = { onDelete(book) },
                        onRename = { onRenameBook(book) },
                        onSync = { onSync(book) },
                        onCancelSync = { onCancelSync(book) },
                        onShare = { onShare(book) },
                        isTranslating = translatingBookId == book.id,
                        isQueued = book.id in queuedBookIds,
                        isCompleted = book.serverId in completedServerIds,
                        progressText = if (translatingBookId == book.id) translatingProgress?.let { "${it.first}/${it.second}" } else null,
                        syncTasks = syncTasks,
                        shareTasks = shareTasks,
                    )
                }
            }
            if (cloudUnfiled.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "云端（未下载）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                gridItems(cloudUnfiled, key = { it.id }) { cb ->
                    CloudCell(
                        cloud = cb,
                        translation = cloudTranslationStates[cb.id],
                        syncTasks = syncTasks,
                        onDownload = { onDownloadCloud(cb) },
                        onDelete = { onDeleteCloud(cb) },
                        onTranslateAll = { onTranslateAllCloud(cb) },
                        onMove = { onMoveCloud(cb) },
                    )
                }
            }
            if (unFiled.isEmpty() && localBooks.isEmpty() && cloudOnly.isEmpty() && folders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "点右下角 + 导入文件 / 图片文件夹",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (lastRead != null) {
                item {
                    ContinueReadingCard(
                        book = lastRead.first,
                        page = lastRead.second.page,
                        onContinue = { onOpen(lastRead.first.id) },
                    )
                }
            }
            item {
                FoldersSection(
                    folders = folders,
                    books = localBooks,
                    cloudByFolderName = cloudByFolderName,
                    gridMode = false,
                    expanded = foldersExpanded,
                    onToggleExpanded = { foldersExpanded = !foldersExpanded },
                    onEnterFolder = onEnterFolder,
                    onNewFolder = onNewFolder,
                    onFolderMenu = onFolderMenu,
                )
            }
            if (unFiled.isNotEmpty()) {
                item {
                    Text(
                        "未分类（${unFiled.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                listItems(unFiled, key = { it.id }) { book ->
                    BookRow(
                        book = book,
                        synced = syncedByBookId[book.id] == true,
                        inFolder = false,
                        onOpen = { onOpen(book.id) },
                        onLongPress = { onMoveBook(book) },
                        onTranslateAll = { onTranslateAll(book) },
                        onMove = { onMoveBook(book) },
                        onDelete = { onDelete(book) },
                        onRename = { onRenameBook(book) },
                        onSync = { onSync(book) },
                        onCancelSync = { onCancelSync(book) },
                        onShare = { onShare(book) },
                        isTranslating = translatingBookId == book.id,
                        isQueued = book.id in queuedBookIds,
                        isCompleted = book.serverId in completedServerIds,
                        progressText = if (translatingBookId == book.id) translatingProgress?.let { "${it.first}/${it.second}" } else null,
                        syncTasks = syncTasks,
                        shareTasks = shareTasks,
                    )
                }
            }
            if (cloudUnfiled.isNotEmpty()) {
                item {
                    Text(
                        "云端（未下载）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                listItems(cloudUnfiled, key = { it.id }) { cb ->
                    CloudRow(
                        cloud = cb,
                        translation = cloudTranslationStates[cb.id],
                        syncTasks = syncTasks,
                        onDownload = { onDownloadCloud(cb) },
                        onDelete = { onDeleteCloud(cb) },
                        onTranslateAll = { onTranslateAllCloud(cb) },
                        onMove = { onMoveCloud(cb) },
                    )
                }
            }
            if (unFiled.isEmpty() && localBooks.isEmpty() && cloudOnly.isEmpty() && folders.isNotEmpty()) {
                item {
                    Text(
                        "点右下角 + 导入文件 / 图片文件夹",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 收藏夹区：网格模式按屏幕宽度等宽填满每行；列表模式显示紧凑行。收起=只显示第一排/前几条，展开=全部。 */
@Composable
private fun FoldersSection(
    folders: List<Folder>,
    books: List<Book>,
    cloudByFolderName: Map<String, List<CloudBook>>,
    gridMode: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEnterFolder: (String) -> Unit,
    onNewFolder: () -> Unit,
    onFolderMenu: (Folder) -> Unit,
) {
    val app = LocalContext.current.applicationContext as ReaderApp
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("收藏夹（${folders.size}）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onNewFolder) { Text("＋ 新建") }
            TextButton(onClick = onToggleExpanded) { Text(if (expanded) "收起" else "展开") }
        }
        if (folders.isNotEmpty()) {
            if (gridMode) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val spacing = 10.dp
                    val minCell = 88.dp
                    val columns = maxOf(1, ((maxWidth + spacing) / (minCell + spacing)).toInt())
                    val itemWidth = (maxWidth - spacing * (columns - 1)) / columns
                    val visible = if (expanded) folders else folders.take(columns)
                    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                        visible.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                                row.forEach { folder ->
                                    val cloudIn = cloudByFolderName[folder.name] ?: emptyList()
                                    val count = books.count { it.folderId == folder.id } + cloudIn.size
                                    val cover = books.firstOrNull { it.folderId == folder.id }?.coverFile
                                        ?: cloudIn.firstOrNull()?.let { app.library.cloudCoverFile(it.id) }
                                    FolderCell(
                                        folder = folder,
                                        count = count,
                                        coverFile = cover,
                                        onClick = { onEnterFolder(folder.id) },
                                        onLongClick = { onFolderMenu(folder) },
                                        modifier = Modifier.width(itemWidth),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val visible = if (expanded) folders else folders.take(3)
                Column {
                    visible.forEach { folder ->
                        val cloudIn = cloudByFolderName[folder.name] ?: emptyList()
                        val count = books.count { it.folderId == folder.id } + cloudIn.size
                        val cover = books.firstOrNull { it.folderId == folder.id }?.coverFile
                            ?: cloudIn.firstOrNull()?.let { app.library.cloudCoverFile(it.id) }
                        FolderRow(
                            folder = folder,
                            count = count,
                            coverFile = cover,
                            onClick = { onEnterFolder(folder.id) },
                            onLongClick = { onFolderMenu(folder) },
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderCell(
    folder: Folder,
    count: Int,
    coverFile: File?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (coverFile != null) {
                AsyncImage(model = coverFile?.let { coverRequest(it) }, contentDescription = folder.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Text(
            folder.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis, softWrap = false,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 列表模式的收藏夹行（紧凑）：小封面 + 名称 + 数量。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: Folder,
    count: Int,
    coverFile: File?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 32.dp, height = 44.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (coverFile != null) {
                AsyncImage(model = coverFile?.let { coverRequest(it) }, contentDescription = folder.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            folder.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis, softWrap = false,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 书卡片：点封面打开；长按→移动；右下角 ⋮ 打开菜单；左上角是同步状态角标。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookCell(
    book: Book,
    synced: Boolean,
    inFolder: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onTranslateAll: () -> Unit,
    onMove: () -> Unit,
    onMoveOut: () -> Unit = {},
    onDelete: () -> Unit,
    onRename: () -> Unit = {},
    onSync: () -> Unit = {},
    onCancelSync: () -> Unit = {},
    onShare: () -> Unit = {},
    isTranslating: Boolean,
    isQueued: Boolean = false,
    isCompleted: Boolean = false,
    progressText: String?,
    syncTasks: Map<String, SyncTask> = emptyMap(),
    shareTasks: Map<String, SyncTask> = emptyMap(),
) {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            AsyncImage(
                model = coverRequest(book.coverFile),
                contentDescription = book.title,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            )
            // 左上角同步状态角标
            CloudBadge(
                color = if (synced) Color(0xFF1E88E5) else Color(0xFFD32F2F),
                check = synced,
                modifier = Modifier.align(Alignment.TopStart),
            )
            // 右下角 ⋮ 菜单
            var menuOpen by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "菜单",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { menuOpen = true }
                        .padding(4.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isTranslating) "翻译中…" else if (isQueued) "排队中…" else if (isCompleted) "已完成" else "全书翻译") },
                        onClick = { menuOpen = false; onTranslateAll() },
                        enabled = !isTranslating && !isQueued && !isCompleted,
                    )
                    if (synced) {
                        DropdownMenuItem(text = { Text("取消同步") }, onClick = { menuOpen = false; onCancelSync() })
                    } else {
                        DropdownMenuItem(text = { Text("同步到云端") }, onClick = { menuOpen = false; onSync() })
                    }
                    if (inFolder) {
                        DropdownMenuItem(text = { Text("移出文件夹") }, onClick = { menuOpen = false; onMoveOut() })
                    } else {
                        DropdownMenuItem(text = { Text("移动到文件夹…") }, onClick = { menuOpen = false; onMove() })
                    }
                    DropdownMenuItem(text = { Text("分享/对传") }, onClick = { menuOpen = false; onShare() })
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis, softWrap = false,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "${book.pageCount} 页 · ${if (book.mode == ReadingMode.MANGA) "日漫" else "普通"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 同步/下载进度（书卡片下方的小进度条，不弹窗，不挡操作）
        syncTasks[book.id]?.let { SyncProgressLine(it.text, it.frac) }
        // 设备对传（分享）进度
        shareTasks[book.id]?.let { SyncProgressLine(it.text, it.frac) }
        if (isTranslating) {
            val total = book.pageCount
            val done = progressText?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                "翻译中 $progressText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else if (isQueued) {
            Text(
                "排队中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** 左上角小角标（同步状态用）。 */
@Composable
private fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier.padding(3.dp)
            .clip(CircleShape).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 同步状态云朵角标：颜色 + 可选对勾（云端=绿云朵，本地=红云朵，已同步=蓝云朵✓）。 */
@Composable
private fun CloudBadge(color: Color, check: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(3.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.75f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                CloudVector,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            if (check) {
                Text("✓", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 书卡片下方的小进度条（同步/下载用，不弹窗、不挡操作）。 */
@Composable
private fun SyncProgressLine(text: String, frac: Float?) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (frac != null) {
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

private fun cloudTranslationLabel(status: String?): String = when (status) {
    "submitting" -> "提交中"
    "queued" -> "排队中"
    "running" -> "翻译中"
    "active" -> "翻译处理中"
    else -> ""
}

/** 书库中的云端全书翻译进度，与本地书卡片保持一致。 */
@Composable
private fun CloudTranslationProgress(state: CloudTranslationState?) {
    if (state?.active != true) return
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (state.status == "submitting" || state.total <= 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { (state.done.toFloat() / state.total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            buildString {
                append(cloudTranslationLabel(state.status))
                if (state.total > 0) append(" ${state.done}/${state.total}")
                if (state.failed > 0) append(" · 失败 ${state.failed}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** 仅云端书卡片：不可打开阅读，只能「下载到本地」或「删除云端」；封面从服务端拉取并缓存。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CloudCell(
    cloud: CloudBook,
    translation: CloudTranslationState? = null,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onTranslateAll: () -> Unit = {},
    onMove: () -> Unit = {},
    syncTasks: Map<String, SyncTask> = emptyMap(),
) {
    val app = LocalContext.current.applicationContext as ReaderApp
    var coverReady by remember { mutableStateOf(false) }
    LaunchedEffect(cloud.id) {
        app.library.ensureCloudCover(cloud.id)
        coverReady = true
    }
    val coverFile = app.library.cloudCoverFile(cloud.id)
    val translationActive = translation?.active == true
    val translationCompleted = (translation?.total ?: 0) > 0 && (translation?.done ?: 0) >= (translation?.total ?: 0)
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = {}, onLongClick = onMove)
        ) {
            if (coverReady && coverFile.exists()) {
                AsyncImage(
                    model = coverRequest(coverFile),
                    contentDescription = cloud.title,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    CloudVector,
                    contentDescription = cloud.title,
                    tint = Color(0xFF43A047),
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                )
            }
            CloudBadge(color = Color(0xFF43A047), modifier = Modifier.align(Alignment.TopStart))
            var menuOpen by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "菜单",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { menuOpen = true }
                        .padding(4.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(if (translationActive) "${cloudTranslationLabel(translation?.status)}…" else if (translationCompleted) "已完成" else "全书翻译")
                        },
                        onClick = { menuOpen = false; onTranslateAll() },
                        enabled = !translationActive && !translationCompleted,
                    )
                    DropdownMenuItem(text = { Text("下载到本地") }, onClick = { menuOpen = false; onDownload() })
                    DropdownMenuItem(text = { Text("移动到收藏夹…") }, onClick = { menuOpen = false; onMove() })
                    DropdownMenuItem(text = { Text("删除云端") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        Text(
            cloud.title ?: "未命名",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis, softWrap = false,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "${cloud.pageCount ?: 0} 页 · 云端",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        syncTasks[cloud.id]?.let { SyncProgressLine(it.text, it.frac) }
        CloudTranslationProgress(translation)
    }
}

/** 列表模式的本地书行（紧凑）：封面缩略图 + 标题/页数 + 状态角标 + 进度 + ⋮ 菜单。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: Book,
    synced: Boolean,
    inFolder: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onTranslateAll: () -> Unit,
    onMove: () -> Unit,
    onMoveOut: () -> Unit = {},
    onDelete: () -> Unit,
    onRename: () -> Unit = {},
    onSync: () -> Unit = {},
    onCancelSync: () -> Unit = {},
    onShare: () -> Unit = {},
    isTranslating: Boolean,
    isQueued: Boolean = false,
    isCompleted: Boolean = false,
    progressText: String?,
    syncTasks: Map<String, SyncTask> = emptyMap(),
    shareTasks: Map<String, SyncTask> = emptyMap(),
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverRequest(book.coverFile),
            contentDescription = book.title,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(width = 40.dp, height = 56.dp).clip(MaterialTheme.shapes.small),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    book.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis, softWrap = false,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CloudBadge(color = if (synced) Color(0xFF1E88E5) else Color(0xFFD32F2F), check = synced)
            }
            Text(
                "${book.pageCount} 页 · ${if (book.mode == ReadingMode.MANGA) "日漫" else "普通"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isTranslating) {
                val total = book.pageCount
                val done = progressText?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text("翻译中 $progressText", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            } else if (isQueued) {
                Text("排队中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            syncTasks[book.id]?.let { SyncProgressLine(it.text, it.frac) }
            shareTasks[book.id]?.let { SyncProgressLine(it.text, it.frac) }        }
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "菜单",
                modifier = Modifier.clip(CircleShape).clickable { menuOpen = true }.padding(4.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (isTranslating) "翻译中…" else if (isQueued) "排队中…" else if (isCompleted) "已完成" else "全书翻译") },
                    onClick = { menuOpen = false; onTranslateAll() },
                    enabled = !isTranslating && !isQueued && !isCompleted,
                )
                if (synced) {
                    DropdownMenuItem(text = { Text("取消同步") }, onClick = { menuOpen = false; onCancelSync() })
                } else {
                    DropdownMenuItem(text = { Text("同步到云端") }, onClick = { menuOpen = false; onSync() })
                }
                if (inFolder) {
                    DropdownMenuItem(text = { Text("移出文件夹") }, onClick = { menuOpen = false; onMoveOut() })
                } else {
                    DropdownMenuItem(text = { Text("移动到文件夹…") }, onClick = { menuOpen = false; onMove() })
                }
                DropdownMenuItem(text = { Text("分享/对传") }, onClick = { menuOpen = false; onShare() })
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

/** 列表模式的仅云端书行（紧凑）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CloudRow(
    cloud: CloudBook,
    translation: CloudTranslationState? = null,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onTranslateAll: () -> Unit = {},
    onMove: () -> Unit = {},
    syncTasks: Map<String, SyncTask> = emptyMap(),
) {
    val app = LocalContext.current.applicationContext as ReaderApp
    var coverReady by remember { mutableStateOf(false) }
    LaunchedEffect(cloud.id) { app.library.ensureCloudCover(cloud.id); coverReady = true }
    val coverFile = app.library.cloudCoverFile(cloud.id)
    val translationActive = translation?.active == true
    val translationCompleted = (translation?.total ?: 0) > 0 && (translation?.done ?: 0) >= (translation?.total ?: 0)
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onMove).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 40.dp, height = 56.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (coverReady && coverFile.exists()) {
                AsyncImage(model = coverRequest(coverFile), contentDescription = cloud.title, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    CloudVector,
                    contentDescription = cloud.title,
                    tint = Color(0xFF43A047),
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cloud.title ?: "未命名",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis, softWrap = false,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                CloudBadge(color = Color(0xFF43A047))
            }
            Text("${cloud.pageCount ?: 0} 页 · 云端", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            syncTasks[cloud.id]?.let { SyncProgressLine(it.text, it.frac) }
            CloudTranslationProgress(translation)
        }
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "菜单",
                modifier = Modifier.clip(CircleShape).clickable { menuOpen = true }.padding(4.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(if (translationActive) "${cloudTranslationLabel(translation?.status)}…" else if (translationCompleted) "已完成" else "全书翻译")
                    },
                    onClick = { menuOpen = false; onTranslateAll() },
                    enabled = !translationActive && !translationCompleted,
                )
                DropdownMenuItem(text = { Text("下载到本地") }, onClick = { menuOpen = false; onDownload() })
                DropdownMenuItem(text = { Text("移动到收藏夹…") }, onClick = { menuOpen = false; onMove() })
                DropdownMenuItem(text = { Text("删除云端") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

/** 移动书到收藏夹（或移出）：带搜索框 + 可滚动列表，收藏夹多时不撑破屏幕。 */
@Composable
private fun MoveBookDialog(
    title: String,
    inFolder: Boolean,
    folders: List<Folder>,
    onMove: (Folder?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = folders.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动《$title》到…") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索收藏夹") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    folders.isEmpty() -> Text(
                        "还没有收藏夹，先去书库页「新建」一个。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    filtered.isEmpty() -> Text(
                        "没有匹配的收藏夹",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        val scrollState = rememberScrollState()
                        val scrollbarColor = MaterialTheme.colorScheme.outline
                        // 撑满宽度 + 明确高度 + 右侧自绘滚动条；每个条目整行可点/可滑
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 96.dp, max = 320.dp)
                                .verticalScroll(scrollState)
                                .drawWithContent {
                                    drawContent()
                                    if (scrollState.maxValue > 0) {
                                        val trackWidth = 4.dp.toPx()
                                        val fraction = scrollState.value.toFloat() / scrollState.maxValue
                                        val contentH = size.height + scrollState.maxValue
                                        val thumbH = (size.height * size.height / contentH).coerceAtLeast(24.dp.toPx())
                                        val thumbTop = fraction * (size.height - thumbH)
                                        drawRoundRect(
                                            color = scrollbarColor,
                                            topLeft = Offset(size.width - trackWidth - 2.dp.toPx(), thumbTop),
                                            size = Size(trackWidth, thumbH),
                                            cornerRadius = CornerRadius(trackWidth / 2f),
                                        )
                                    }
                                },
                        ) {
                            if (inFolder && query.isBlank()) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { onMove(null) }.padding(vertical = 12.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("移出（未分类）", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            filtered.forEach { f ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onMove(f) }.padding(vertical = 12.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 新建收藏夹。 */
@Composable
private fun CreateFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建收藏夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 收藏夹菜单：重命名 / 删除。 */
@Composable
private fun FolderMenuDialog(
    folder: Folder,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${folder.name}」") },
        text = {
            Column {
                TextButton(onClick = onRename) { Text("重命名") }
                TextButton(onClick = onDelete) { Text("删除收藏夹") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 重命名收藏夹。 */
@Composable
private fun RenameFolderDialog(
    folder: Folder,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名收藏夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 重命名书（导入时用的是文件名，可在这里改成自己喜欢的名字）。 */
@Composable
private fun RenameBookDialog(
    book: Book,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(book.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名书") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("书名") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 翻译进度列表：本地书 + 云端书（未下载） + 服务端 done/failed/active 汇总。 */
@Composable
private fun ProgressList(
    books: List<Book>,
    cloudBooks: List<CloudBook>,
    serverBooks: List<ServerBook>,
    error: String?,
    translatingBookId: String?,
    translatingProgress: Pair<Int, Int>?,
    queuedBookIds: Set<String>,
    progressHistory: Map<String, Pair<Int, Int>>,
    onStop: (Book) -> Unit,
    onStopCloud: (CloudBook) -> Unit,
) {
    var localExpanded by remember { mutableStateOf(true) }
    var cloudExpanded by remember { mutableStateOf(true) }
    var downloadsExpanded by remember { mutableStateOf(true) }
    val app = LocalContext.current.applicationContext as ReaderApp
    val downloadTasks = app.downloadTasks
    val hasFinishedDownload = downloadTasks.any {
        it.status == DownloadStatus.DONE || it.status == DownloadStatus.DUPLICATE
    }

    // 已同步书的服务端 book_id 是 cloudId（serverId），未同步是本地 id
    val serverMap = serverBooks.associateBy { it.id }
    val visibleBooks = books.filter { book ->
        translatingBookId == book.id ||
            book.id in queuedBookIds ||
            (progressHistory[book.id]?.first ?: 0) > 0 ||
            (serverMap[book.serverId]?.let { it.donePages + it.failedPages > 0 } == true)
    }
    // 云端书（未下载到本地）里有翻译进度/在跑的
    val visibleCloud = cloudBooks.filter { cb ->
        books.none { it.cloudId == cb.id } &&
            serverMap[cb.id]?.let { it.donePages + it.failedPages > 0 || it.activeJobs > 0 } == true
    }
    if (downloadTasks.isEmpty() && visibleBooks.isEmpty() && visibleCloud.isEmpty()) {
        val emptyText = when {
            error != null -> "加载进度失败：$error"
            books.isEmpty() && cloudBooks.isEmpty() -> "暂无书籍"
            else -> "还没有翻译过的书"
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyText,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (downloadTasks.isNotEmpty()) {
            item(key = "download-header") {
                DownloadSectionHeader(
                    count = downloadTasks.size,
                    expanded = downloadsExpanded,
                    hasFinished = hasFinishedDownload,
                    onToggle = { downloadsExpanded = !downloadsExpanded },
                    onClear = { app.clearFinishedDownloads() },
                )
            }
            if (downloadsExpanded) {
                listItems(downloadTasks, key = { it.id }) { task ->
                    DownloadRow(
                        task = task,
                        onPause = { app.pauseDownload(task.id) },
                        onResume = { app.resumeDownload(task.id) },
                        onCancel = { app.cancelDownload(task.id) },
                    )
                }
            }
        }
        if (visibleBooks.isNotEmpty()) {
            item(key = "local-header") {
                ProgressSectionHeader(
                    title = "本地",
                    count = visibleBooks.size,
                    expanded = localExpanded,
                    onToggle = { localExpanded = !localExpanded },
                )
            }
            if (localExpanded) {
                listItems(visibleBooks, key = { it.id }) { book ->
                    val s = serverMap[book.serverId]
                    val serverStatus = s?.jobStatus
                    val isRunning = serverStatus == "running"
                    val isQueued = serverStatus == "queued" || book.id in queuedBookIds
                    val isPreparing = translatingBookId == book.id && serverStatus == null && (s?.activeJobs ?: 0) == 0
                    val hasLegacyActiveJob = serverStatus == null && (s?.activeJobs ?: 0) > 0
                    val hist = progressHistory[book.id]
                    val done = if (translatingBookId == book.id) (translatingProgress?.first ?: hist?.first ?: 0)
                               else (hist?.first ?: s?.donePages ?: 0)
                    val failed = s?.failedPages ?: 0
                    val total = book.pageCount
                    val status = when {
                        isRunning -> "进行中"
                        isQueued -> "排队中"
                        isPreparing -> "准备中"
                        hasLegacyActiveJob -> "活动中"
                        done >= total && failed == 0 -> "已完成"
                        failed > 0 && done + failed >= total -> "部分失败"
                        else -> ""   // 部分翻译但没在跑：不显示状态，只显示页数
                    }
                    BookProgressRow(
                        title = book.title,
                        status = status,
                        done = done,
                        failed = failed,
                        total = total,
                        isRunning = isRunning,
                        isQueued = isQueued,
                        coverFile = book.coverFile,
                        onStop = { onStop(book) },
                    )
                }
            }
        }
        if (visibleCloud.isNotEmpty()) {
            item(key = "cloud-header") {
                ProgressSectionHeader(
                    title = "云端（未下载）",
                    count = visibleCloud.size,
                    expanded = cloudExpanded,
                    onToggle = { cloudExpanded = !cloudExpanded },
                )
            }
            if (cloudExpanded) {
                listItems(visibleCloud, key = { "cloud-${it.id}" }) { cb ->
                    val s = serverMap[cb.id]
                    val done = s?.donePages ?: 0
                    val failed = s?.failedPages ?: 0
                    val total = cb.pageCount ?: 0
                    val isRunning = s?.jobStatus == "running"
                    val isQueued = s?.jobStatus == "queued"
                    val hasLegacyActiveJob = s?.jobStatus == null && (s?.activeJobs ?: 0) > 0
                    val status = when {
                        isRunning -> "进行中"
                        isQueued -> "排队中"
                        hasLegacyActiveJob -> "活动中"
                        total > 0 && done >= total && failed == 0 -> "已完成"
                        failed > 0 && done + failed >= total -> "部分失败"
                        else -> ""
                    }
                    BookProgressRow(
                        cb.title ?: "未命名", status, done, failed, total, isRunning, isQueued,
                        cloud = true,
                        cloudCoverId = cb.id,
                        onStop = { onStopCloud(cb) },
                    )
                }
            }
        }
    }
}

/** 进度页「下载」区分组头：计数 + 清除已结束记录。 */
@Composable
private fun DownloadSectionHeader(
    count: Int,
    expanded: Boolean,
    hasFinished: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("下载 · $count", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (hasFinished) {
                TextButton(onClick = onClear) { Text("清除已完成") }
            }
            Text(
                if (expanded) "收起 ▲" else "展开 ▼",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 进度页「下载」区的一行：文件名 + 状态 + 进度条 + 暂停/继续/取消。 */
@Composable
private fun DownloadRow(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val statusText = when (task.status) {
        DownloadStatus.DOWNLOADING -> "下载中"
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.IMPORTING -> "导入中"
        DownloadStatus.DONE -> "已完成"
        DownloadStatus.DUPLICATE -> "已存在"
        DownloadStatus.FAILED -> "失败"
        DownloadStatus.CANCELED -> "已取消"
    }
    val statusColor = when (task.status) {
        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
        DownloadStatus.PAUSED -> Color(0xFFF57C00)
        DownloadStatus.DOWNLOADING, DownloadStatus.IMPORTING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val showProgress = task.status == DownloadStatus.DOWNLOADING ||
        task.status == DownloadStatus.IMPORTING ||
        task.status == DownloadStatus.PAUSED

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    task.title.ifBlank { task.name },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
            if (task.message.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    task.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showProgress) {
                Spacer(Modifier.height(8.dp))
                if (task.total > 0) {
                    LinearProgressIndicator(
                        progress = { (task.written.toFloat() / task.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${(task.written * 100 / task.total).coerceAtMost(100)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${formatBytes(task.written)} / ${formatBytes(task.total)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已接收 ${formatBytes(task.written)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val actions: (@Composable () -> Unit)? = when (task.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.IMPORTING -> {
                    { Row {
                        TextButton(onClick = onPause, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("暂停") }
                        TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("取消") }
                    } }
                }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    { Row {
                        TextButton(onClick = onResume, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(if (task.status == DownloadStatus.FAILED) "重试" else "继续")
                        }
                        TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("删除") }
                    } }
                }
                else -> null
            }
            actions?.invoke()
        }
    }
}

/** 进度页的可折叠分组标题。 */
@Composable
private fun ProgressSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$title · $count",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "收起 ▲" else "展开 ▼",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun BookProgressRow(
    title: String,
    status: String,
    done: Int,
    failed: Int,
    total: Int,
    isRunning: Boolean,
    isQueued: Boolean,
    cloud: Boolean = false,
    coverFile: File? = null,
    cloudCoverId: String? = null,
    onStop: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ReaderApp
    val isActiveTask = status in setOf("进行中", "排队中", "准备中", "活动中")
    var cloudCoverReady by remember(cloudCoverId) { mutableStateOf(false) }
    LaunchedEffect(cloudCoverId) {
        if (cloudCoverId != null) {
            app.library.ensureCloudCover(cloudCoverId)
            cloudCoverReady = true
        }
    }
    val shownCover = when {
        cloudCoverId != null && cloudCoverReady -> app.library.cloudCoverFile(cloudCoverId)
        coverFile != null -> coverFile
        else -> null
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 60.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (shownCover != null && shownCover.exists()) {
                AsyncImage(
                    model = coverRequest(shownCover),
                    contentDescription = "$title 封面",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (cloud) {
                Icon(CloudVector, contentDescription = null, tint = Color(0xFF43A047), modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (cloud) {
                    Icon(CloudVector, contentDescription = null, tint = Color(0xFF43A047), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis, softWrap = false,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (status) {
                            "已完成" -> MaterialTheme.colorScheme.primary
                            "进行中" -> MaterialTheme.colorScheme.tertiary
                            "部分失败" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (isActiveTask) {
                    TextButton(onClick = onStop) { Text("停止") }
                }
            }
            Text(
                buildString {
                    append("$done / $total 页")
                    if (failed > 0) append(" · 失败 $failed")
                    if (isRunning && done < total) append(" · 后台翻译中")
                    if (isQueued) append(" · 排队中")
                    if (status == "准备中") append(" · 正在提交任务")
                    if (status == "活动中") append(" · 状态同步中")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 本地、云端与排队任务共用同一进度展示；状态只说明调度阶段，进度始终取 done / total。
            if (isActiveTask && total > 0) {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

/** 搜索：同时匹配收藏夹、本地书和仅云端书；已下载的云端书不重复显示。 */
@Composable
private fun SearchResults(
    query: String,
    books: List<Book>,
    cloudBooks: List<CloudBook>,
    folders: List<Folder>,
    onOpenBook: (String) -> Unit,
    onEnterFolder: (String) -> Unit,
    onDownloadCloud: (CloudBook) -> Unit,
    downloadingCloudIds: Set<String>,
) {
    val q = query.trim()
    if (q.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("输入书名或收藏夹名", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val folderById = folders.associateBy { it.id }
    val cloudOnly = cloudBooks.filter { cloud -> books.none { it.cloudId == cloud.id } }
    val matchedFolders = folders.filter { it.name.contains(q, ignoreCase = true) }
    val matchedBooks = books.filter { it.title.contains(q, ignoreCase = true) }
    val matchedCloudBooks = cloudOnly.filter { it.title?.contains(q, ignoreCase = true) == true }
    if (matchedFolders.isEmpty() && matchedBooks.isEmpty() && matchedCloudBooks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有找到「$q」", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (matchedFolders.isNotEmpty()) {
            item {
                Text(
                    "收藏夹",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            listItems(matchedFolders, key = { it.id }) { folder ->
                val count = books.count { it.folderId == folder.id } +
                    cloudOnly.count { it.folder == folder.name }
                SearchRow(
                    title = folder.name,
                    subtitle = "$count 本",
                    onClick = { onEnterFolder(folder.id) },
                )
            }
        }
        if (matchedBooks.isNotEmpty()) {
            item {
                Text(
                    "本地漫画",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            listItems(matchedBooks, key = { it.id }) { book ->
                val folderName = book.folderId?.let { folderById[it]?.name }
                SearchRow(
                    title = book.title,
                    subtitle = if (folderName != null) "在「$folderName」" else "未分类",
                    onClick = { onOpenBook(book.id) },
                )
            }
        }
        if (matchedCloudBooks.isNotEmpty()) {
            item {
                Text(
                    "云端漫画",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            listItems(matchedCloudBooks, key = { "cloud-search-${it.id}" }) { cloud ->
                val folderText = cloud.folder?.takeIf { it.isNotBlank() }?.let { "在「$it」" } ?: "未分类"
                val downloading = cloud.id in downloadingCloudIds
                SearchRow(
                    title = cloud.title ?: "未命名",
                    subtitle = "仅云端 · $folderText",
                    actionLabel = if (downloading) "下载中…" else "下载",
                    actionEnabled = !downloading,
                    onAction = { onDownloadCloud(cloud) },
                )
            }
        }
    }
}

@Composable
private fun SearchRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentModifier = Modifier.weight(1f).then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
        Column(contentModifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction, enabled = actionEnabled) {
                Text(actionLabel)
            }
        }
    }
}

/** 「继续阅读」卡片：显示上次读的书和进度，点它跳到上次那页。 */
@Composable
private fun ContinueReadingCard(book: Book, page: Int, onContinue: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onContinue),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = coverRequest(book.coverFile),
                contentDescription = book.title,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.size(width = 44.dp, height = 60.dp).clip(MaterialTheme.shapes.small),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("继续阅读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "读到第 ${(page + 1).coerceAtMost(book.pageCount)} / ${book.pageCount} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
