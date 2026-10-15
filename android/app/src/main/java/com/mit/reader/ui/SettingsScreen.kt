package com.mit.reader.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mit.reader.BuildConfig
import com.mit.reader.LibraryScanStatus
import com.mit.reader.LibraryStorageOption
import com.mit.reader.ReaderApp
import com.mit.reader.StorageMigrationStatus
import com.mit.reader.data.ServerConfig

private const val SETTINGS_TAB_SERVER = 0
private const val SETTINGS_TAB_TRANSLATOR = 1
private const val SETTINGS_TAB_LIBRARY = 2
private const val SETTINGS_TAB_UPDATE = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApp
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(SETTINGS_TAB_SERVER) }

    var serverUrl by remember { mutableStateOf(ServerConfig.baseUrl) }
    var serverKey by remember { mutableStateOf(ServerConfig.apiKey) }
    var serverResult by remember { mutableStateOf("") }
    val usageText = app.accountUsageText

    var translatorModel by remember { mutableStateOf(ServerConfig.translatorModel) }
    var translatorBaseUrl by remember { mutableStateOf(ServerConfig.translatorBaseUrl) }
    var translatorApiKey by remember { mutableStateOf(ServerConfig.translatorApiKey) }
    var translatorResult by remember { mutableStateOf("") }

    var folderName by remember { mutableStateOf(ServerConfig.libraryFolderName) }
    var storageResult by remember { mutableStateOf("") }
    var storagePickerOpen by remember { mutableStateOf(false) }
    var pendingStoragePath by remember { mutableStateOf(app.currentLibraryStorageDir) }
    val scanStatus = app.libraryScanStatus
    val storageOptions = app.libraryStorageOptions()
    val currentStorageOption = storageOptions.firstOrNull { it.path == app.currentLibraryStorageDir }
        ?: storageOptions.first()
    val storageMigration = app.storageMigrationStatus

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            ServerConfig.libraryFolderUri = uri.toString()
            folderName = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
            ServerConfig.libraryFolderName = folderName
            storageResult = ""
        }
    }

    LaunchedEffect(Unit) { app.refreshAccountUsage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == SETTINGS_TAB_SERVER,
                    onClick = { selectedTab = SETTINGS_TAB_SERVER },
                    text = { Text("服务器") },
                )
                Tab(
                    selected = selectedTab == SETTINGS_TAB_TRANSLATOR,
                    onClick = { selectedTab = SETTINGS_TAB_TRANSLATOR },
                    text = { Text("翻译") },
                )
                Tab(
                    selected = selectedTab == SETTINGS_TAB_LIBRARY,
                    onClick = { selectedTab = SETTINGS_TAB_LIBRARY },
                    text = { Text("书库") },
                )
                Tab(
                    selected = selectedTab == SETTINGS_TAB_UPDATE,
                    onClick = { selectedTab = SETTINGS_TAB_UPDATE },
                    text = { Text("更新") },
                )
            }

            when (selectedTab) {
                SETTINGS_TAB_SERVER -> SettingsServerTab(
                    app = app,
                    url = serverUrl,
                    key = serverKey,
                    usageText = usageText,
                    onUrlChange = { serverUrl = it },
                    onKeyChange = { serverKey = it },
                    onSaved = { serverResult = it },
                )
                SETTINGS_TAB_TRANSLATOR -> SettingsTranslatorTab(
                    model = translatorModel,
                    baseUrl = translatorBaseUrl,
                    apiKey = translatorApiKey,
                    onModelChange = { translatorModel = it },
                    onBaseUrlChange = { translatorBaseUrl = it },
                    onApiKeyChange = { translatorApiKey = it },
                    onResult = { translatorResult = it },
                )
                SETTINGS_TAB_LIBRARY -> SettingsLibraryTab(
                    app = app,
                    folderName = folderName,
                    scanStatus = scanStatus,
                    storageOptions = storageOptions,
                    currentStorageOption = currentStorageOption,
                    storageMigration = storageMigration,
                    storageResult = storageResult,
                    onStorageResult = { storageResult = it },
                    onSelectFolder = { folderPicker.launch(null) },
                    onChangeStorage = {
                        pendingStoragePath = app.currentLibraryStorageDir
                        storagePickerOpen = true
                    },
                )
                SETTINGS_TAB_UPDATE -> SettingsUpdateTab(app)
            }
        }
    }

    if (storagePickerOpen) {
        AlertDialog(
            onDismissRequest = { storagePickerOpen = false },
            title = { Text("选择数据存储位置") },
            text = {
                Column {
                    storageOptions.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pendingStoragePath = option.path }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = pendingStoragePath == option.path,
                                onClick = { pendingStoragePath = option.path },
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(option.label)
                                Text(
                                    "可用 ${formatBytes(option.usableBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    option.path ?: "App 私有内部数据",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(
                        "确认后只切换新导入的位置，不自动搬运；旧书会继续在书库显示。如需搬运，请返回设置点击「搬运旧数据到当前位置」。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingStoragePath != app.currentLibraryStorageDir,
                    onClick = {
                        app.changeLibraryStorage(pendingStoragePath ?: currentStorageOption.path.orEmpty())
                        storagePickerOpen = false
                    },
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { storagePickerOpen = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsServerTab(
    app: ReaderApp,
    url: String,
    key: String,
    usageText: String,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSaved: (String) -> Unit,
) {
    var result by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "服务器地址（局域网填 http://IP:8020，线上填 https://域名）",
            style = MaterialTheme.typography.labelMedium,
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("http://192.168.0.90:8020 或 https://mit.example.com") },
        )
        Text(
            "API Key（鉴权 + 云同步账号：与服务端 MIT_API_TOKEN 一致）",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        OutlinedTextField(
            value = key,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("X-API-Token") },
        )
        Row(Modifier.padding(top = 16.dp)) {
            Button(
                onClick = {
                    ServerConfig.baseUrl = url
                    ServerConfig.apiKey = key
                    result = "服务器配置已保存"
                    onSaved(result)
                    app.refreshServerStatus()
                },
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
            Button(
                onClick = {
                    ServerConfig.baseUrl = url
                    ServerConfig.apiKey = key
                    app.pingServer()
                },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            ) { Text("测试连接") }
        }
        TextButton(
            onClick = {
                onUrlChange(ServerConfig.DEFAULT_URL)
                result = "已填回局域网默认地址，保存后生效"
            },
        ) { Text("恢复局域网默认地址") }
        if (result.isNotBlank()) {
            Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        app.backgroundActions["ping-server"]?.let { action ->
            // 修复：服务器 401 时明确提示令牌失效，其余错误照旧展示原文。
            // "HTTP 401: …" 格式由 TranslationApi 保证（ping() 非 2xx 直接返回该文案；其他接口抛
            // ApiHttpException 后到 UI 层只剩 message 字符串），故按前缀分流而非类型 catch。
            val tokenExpired = action.message.startsWith("HTTP 401")
            val pingError = tokenExpired ||
                (action.message.contains("失败") && !action.message.contains("未连接"))
            Text(
                when {
                    action.running -> "正在测试连接…"
                    tokenExpired -> "服务器令牌已失效，请在下方更新 API Token"
                    else -> action.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (pingError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text("账号用量", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text(
            if (usageText.isBlank()) "加载中…" else usageText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SettingsTranslatorTab(
    model: String,
    baseUrl: String,
    apiKey: String,
    onModelChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onResult: (String) -> Unit,
) {
    var result by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("翻译模型（OpenAI 兼容接口）", style = MaterialTheme.typography.titleMedium)
        Text(
            "模型名、Base URL 和模型 API Key 保存本机，并随每次翻译请求临时传给后端；服务器不再保存 DeepSeek Key。跨页上下文默认使用前 3 页，并按真实页码分块。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            label = { Text("模型名") },
            placeholder = { Text("deepseek-v4-pro / qwen3-max 等") },
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            label = { Text("API Base URL") },
            placeholder = { Text("https://api.deepseek.com 或 http://192.168.0.5:11434/v1") },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            label = { Text("模型 API Key") },
            placeholder = { Text("必填") },
        )
        Button(
            onClick = {
                result = when {
                    model.isBlank() || baseUrl.isBlank() || apiKey.isBlank() -> {
                        "模型名、Base URL 和模型 API Key 都必须填写"
                    }
                    else -> {
                        ServerConfig.translatorModel = model
                        ServerConfig.translatorBaseUrl = baseUrl
                        ServerConfig.translatorApiKey = apiKey
                        "翻译模型配置已保存，之后新翻译立即生效"
                    }
                }
                onResult(result)
            },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("保存模型配置") }
        TextButton(
            onClick = {
                onModelChange("")
                onBaseUrlChange("")
                onApiKeyChange("")
                ServerConfig.translatorModel = ""
                ServerConfig.translatorBaseUrl = ""
                ServerConfig.translatorApiKey = ""
                result = "已清空模型配置；所有新翻译会被禁止"
                onResult(result)
            },
        ) { Text("清空模型配置") }
        if (result.isNotBlank()) {
            Text(
                result,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SettingsLibraryTab(
    app: ReaderApp,
    folderName: String?,
    scanStatus: LibraryScanStatus?,
    storageOptions: List<LibraryStorageOption>,
    currentStorageOption: LibraryStorageOption,
    storageMigration: StorageMigrationStatus?,
    storageResult: String,
    onStorageResult: (String) -> Unit,
    onSelectFolder: () -> Unit,
    onChangeStorage: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("书库文件夹（自动扫描导入）", style = MaterialTheme.typography.titleMedium)
        Text(
            if (folderName.isNullOrBlank()) {
                "未选择：点「选择文件夹」指定一个目录，App 会自动扫描其中的 .epub / .mobi / .zip / .rar / .cbz / .cbr 并导入。"
            } else {
                "当前：$folderName"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 8.dp)) {
            Button(onClick = onSelectFolder, modifier = Modifier.weight(1f)) { Text("选择文件夹") }
            Button(
                onClick = app::startLibraryScan,
                enabled = !ServerConfig.libraryFolderUri.isNullOrBlank() && scanStatus?.running != true,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            ) {
                if (scanStatus?.running == true) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("扫描中", Modifier.padding(start = 6.dp))
                } else {
                    Text("重新扫描")
                }
            }
        }
        if (scanStatus != null) {
            if (scanStatus.running && scanStatus.total > 0) {
                LinearProgressIndicator(
                    progress = { scanStatus.processed.toFloat() / scanStatus.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Text(scanStatus.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        Text("数据存储位置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        Text(
            "书库文件夹只作为扫描来源；导入后的页面和译文写入这里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        storageMigration?.let { migration ->
            Text(
                migration.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (migration.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            "当前：${currentStorageOption.label}\n${currentStorageOption.path ?: "App 私有内部数据"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onChangeStorage, modifier = Modifier.padding(top = 8.dp)) { Text("更改存储位置") }
        TextButton(
            enabled = storageMigration?.running != true,
            onClick = app::startManualStorageMigration,
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("搬运旧数据到当前位置") }
        if (storageResult.isNotBlank()) {
            Text(storageResult, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        if (app.libraryImports.isNotEmpty()) {
            Text("后台导入", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            app.libraryImports.values.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    if (task.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "${task.title}：${task.message}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = if (task.running) 8.dp else 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsUpdateTab(app: ReaderApp) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("关于与更新", style = MaterialTheme.typography.titleMedium)
        Text(
            "当前版本：${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 8.dp)) {
            Button(
                onClick = { app.checkForUpdate(manual = true) },
                enabled = !app.updateChecking,
                modifier = Modifier.weight(1f),
            ) {
                if (app.updateChecking) CircularProgressIndicator(Modifier.size(16.dp)) else Text("检查更新")
            }
            if (app.updateInfo != null) {
                Button(
                    onClick = app::startUpdateDownload,
                    enabled = !app.updateDownloading,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) { Text(if (app.updateDownloading) "下载中…" else "立即更新") }
            }
        }
        app.updateInfo?.let { info ->
            if (info.changelog.isNotBlank()) {
                Text(
                    "新版 ${info.versionName}（${formatBytes(info.size)}）：${info.changelog}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (app.updateDownloading) {
                val (downloaded, total) = app.updateDownloadProgress ?: (0L to 0L)
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "${(downloaded * 100 / total).coerceAtMost(100)}% · ${formatBytes(downloaded)} / ${formatBytes(total)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        if (app.updateMessage.isNotBlank()) {
            Text(
                app.updateMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (app.updateError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
