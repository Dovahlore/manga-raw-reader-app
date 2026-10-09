package com.mit.reader.ui

import android.content.Intent
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mit.reader.BuildConfig
import com.mit.reader.ReaderApp
import com.mit.reader.data.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApp
    val context = LocalContext.current
    var url by remember { mutableStateOf(ServerConfig.baseUrl) }
    var key by remember { mutableStateOf(ServerConfig.apiKey) }
    var result by remember { mutableStateOf("") }
    val usageText = app.accountUsageText
    var folderName by remember { mutableStateOf(ServerConfig.libraryFolderName) }
    var storageResult by remember { mutableStateOf("") }
    var storagePickerOpen by remember { mutableStateOf(false) }
    var pendingStoragePath by remember { mutableStateOf(app.currentLibraryStorageDir) }
    val scanStatus = app.libraryScanStatus
    val storageOptions = app.libraryStorageOptions()
    val currentStorageOption = storageOptions.firstOrNull { it.path == app.currentLibraryStorageDir }
        ?: storageOptions.first()
    val storageMigration = app.storageMigrationStatus

    // 书库文件夹选择（SAF 树）：拿到权限后持久化，重启不失效
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            ServerConfig.libraryFolderUri = uri.toString()
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
            ServerConfig.libraryFolderName = name
            folderName = name
            storageResult = ""
        }
    }

    LaunchedEffect(Unit) { app.refreshAccountUsage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(
                "服务器地址（局域网填 http://IP:8020，线上填 https://域名）",
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("http://192.168.0.90:8020 或 https://mit.example.com") },
            )
            Text(
                "API Key（鉴权 + 云同步账号：与服务端 app.env 的 MIT_API_TOKEN 一致；同一个 Key 多设备互通，不同 Key 互不可见）",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("X-API-Token") },
            )
            Row(Modifier.padding(top = 16.dp)) {
                Button(
                    onClick = {
                        ServerConfig.baseUrl = url
                        ServerConfig.apiKey = key
                        result = "已保存"
                        app.refreshServerStatus()   // 保存后绿点即时反映新地址连通性
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
                    url = ServerConfig.DEFAULT_URL
                    result = "已填回局域网默认地址（保存后生效）"
                },
            ) { Text("恢复局域网默认地址") }
            if (result.isNotBlank()) {
                Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
            app.backgroundActions["ping-server"]?.let { action ->
                Text(
                    if (action.running) "正在测试连接…" else action.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ---- 账号用量 ----
            Text(
                "账号用量",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                if (usageText.isBlank()) "加载中…" else usageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // ---- 书库文件夹（自动扫描导入 epub/mobi/漫画压缩包）----
            Text(
                "书库文件夹（自动扫描导入）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                if (folderName.isNullOrBlank()) "未选择：点「选择文件夹」指定一个目录，App 会自动扫描其中的 .epub / .mobi / .zip / .rar / .cbz / .cbr 并导入（按内容去重）。"
                else "当前：$folderName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) { Text("选择文件夹") }
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
                        progress = { if (scanStatus.total == 0) 0f else scanStatus.processed.toFloat() / scanStatus.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Text(
                    scanStatus.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // ---- 数据存储位置 ----
            Text(
                "数据存储位置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                "书库文件夹只作为扫描来源；导入后的页面和译文写入这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            app.storageMigrationStatus?.let { migration ->
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
            Button(
                onClick = {
                    pendingStoragePath = app.currentLibraryStorageDir
                    storagePickerOpen = true
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("更改存储位置") }
            TextButton(
                enabled = storageMigration?.running != true,
                onClick = app::startManualStorageMigration,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("搬运旧数据到当前位置") }
            if (storageResult.isNotBlank()) {
                Text(storageResult, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            // ---- 关于与更新 ----
            Text(
                "关于与更新",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                "当前版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
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
                    if (app.updateChecking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("检查更新")
                    }
                }
                if (app.updateInfo != null) {
                    Button(
                        onClick = { app.startUpdateDownload() },
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
                    val (w, t) = app.updateDownloadProgress ?: (0L to 0L)
                    if (t > 0) {
                        LinearProgressIndicator(
                            progress = { (w.toFloat() / t).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Text(
                            "${(w * 100 / t).coerceAtMost(100)}% · ${formatBytes(w)} / ${formatBytes(t)}",
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
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // ---- 后台导入状态（Application 级，不随页面销毁）----
            if (app.libraryImports.isNotEmpty()) {
                Text(
                    "后台导入",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
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

/** 字节数 → 可读大小。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
