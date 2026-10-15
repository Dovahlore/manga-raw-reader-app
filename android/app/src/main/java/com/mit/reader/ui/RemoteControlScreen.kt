package com.mit.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mit.reader.ReaderApp
import com.mit.reader.data.PairedDevice
import com.mit.reader.data.PairedDeviceState
import com.mit.reader.data.RemotePageAction
import com.mit.reader.data.isDeviceOnline
import com.mit.reader.data.reconcileDiscoveredDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 被控端在「忘记本机/取消授权」后对旧凭据的固定拒绝文案（RemoteControlManager 生成）。 */
private const val REMOTE_PAIR_CANCELLED_MSG = "设备未配对或已取消授权"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApp
    val view = LocalView.current
    val deviceStates = rememberPairedDeviceStates(app)
    // 失联自动重连（NSD 短扫描认回）+ 对方取消配对的死记录清理，挂在遥控器页生命周期内
    val lastTappedRecord = rememberRemoteAutoReconnect(app)

    // 从「设备」页点已配对设备进来：自动发起连接
    LaunchedEffect(Unit) {
        app.consumePendingRemoteConnect()?.let(app::connectPairedRemoteDevice)
    }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        // 遥控器模式与被控互斥；「允许被附近设备发现」是独立开关，进入遥控器模式不再强制关闭
        app.enterRemoteControllerMode()
        onDispose {
            view.keepScreenOn = false
            app.leaveRemoteControllerScreen()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("遥控器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val connection = app.remoteControllerConnection
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (connection == null) {
                // 配对与连接管理在「书库 · 设备」页；这里只列已配对设备，点击连接后遥控
                Text(
                    app.remoteControllerMessage.ifBlank {
                        "点击已配对设备连接后即可遥控；配对新设备请到「书库 · 设备」页。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (app.pairedDevices.isEmpty()) {
                    Text(
                        "暂无已配对设备。请在「书库 · 设备」页输入对方配对码完成配对。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(app.pairedDevices, key = { it.deviceId }) { device ->
                        val state = deviceStates[device.deviceId]
                        val online = state == PairedDeviceState.ONLINE
                        ListItem(
                            modifier = Modifier.clickable(enabled = online && !app.remoteControllerBusy) {
                                lastTappedRecord.value = device
                                app.connectPairedRemoteDevice(device)
                            },
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text("${device.host}:${device.port}") },
                            trailingContent = {
                                if (app.remoteControllerBusy) {
                                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.size(10.dp).clip(CircleShape).background(
                                                if (online) Color(0xFF43A047) else Color(0xFF9E9E9E)
                                            ),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            when (state) {
                                                PairedDeviceState.ONLINE -> "在线 · 点击连接"
                                                PairedDeviceState.OFFLINE -> "离线"
                                                null -> "检测中…"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (online) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            } else {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(connection.device.name, style = MaterialTheme.typography.titleMedium)
                        val reader = app.remoteControllerReader
                        Text(
                            text = reader?.let { "${it.title} · ${it.page + 1}/${it.pageCount}" }
                                ?: app.remoteControllerMessage.ifBlank { "等待阅读端打开书籍" },
                        )
                        if (reader == null) {
                            Text(
                                "提示：对方停留在阅读器界面时，遥控翻页才有效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(app.remoteControllerMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("音量键翻页")
                    Switch(
                        checked = app.remoteVolumeKeysEnabled,
                        onCheckedChange = app::updateRemoteVolumeKeysEnabled,
                    )
                }
                Text(
                    "音量上 = 上一页，音量下 = 下一页。仅在遥控器页面前台时拦截。",
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { app.sendRemoteCommand(RemotePageAction.PREVIOUS) },
                        modifier = Modifier.weight(1f),
                    ) { Text("上一页", textAlign = TextAlign.Center) }
                    Button(
                        onClick = { app.sendRemoteCommand(RemotePageAction.NEXT) },
                        modifier = Modifier.weight(1f),
                    ) { Text("下一页", textAlign = TextAlign.Center) }
                }
                OutlinedButton(onClick = app::disconnectRemoteController, modifier = Modifier.fillMaxWidth()) {
                    Text("断开")
                }
            }
        }
    }
}

/**
 * 失联自动重连（挂载 NSD 扫描引擎，非网段盲扫）：
 * 遥控连接因网络异常断开（消息以「连接失败」开头）时，起一次约 4 秒的短时 NSD 扫描发现
 * 附近设备，用配对时保存的授权向候选地址做心跳验证认回；地址变了就落盘更新并自动重连。
 * 手动断开、对方明确拒绝（未开被控/授权被取消）不触发。10 秒冷却、最多连试 3 次。
 */
@Composable
private fun rememberRemoteAutoReconnect(app: ReaderApp): MutableState<PairedDevice?> {
    val scope = rememberCoroutineScope()
    val lastRecord = remember { mutableStateOf<PairedDevice?>(null) }
    var recoveryJob by remember { mutableStateOf<Job?>(null) }
    var attempts by remember { mutableStateOf(0) }
    var lastAttemptAt by remember { mutableStateOf(0L) }

    LaunchedEffect(app.remoteControllerConnection, app.remoteControllerMessage) {
        // 对方单侧取消配对：本机这条记录已成死凭据，自动移除（含会话中被对方忘记的情况）
        if (app.remoteControllerMessage == REMOTE_PAIR_CANCELLED_MSG) {
            lastRecord.value?.let { record ->
                app.forgetPairedDevice(record.deviceId)
                app.showRemoteControllerMessage("对方已取消配对，已移除「${record.name}」的记录")
                lastRecord.value = null
                recoveryJob?.cancel()
            }
            return@LaunchedEffect
        }
        val current = app.remoteControllerConnection
        if (current != null) {
            // 连接建立/重连成功：记住目标设备并复位计数
            lastRecord.value = app.pairedDevices.firstOrNull {
                it.host == current.device.host && it.port == current.device.port
            }
            attempts = 0
            return@LaunchedEffect
        }
        val lost = lastRecord.value ?: return@LaunchedEffect
        // 手动断开（已断开）、对方明确拒绝（未开被控/未授权）都不是网络失联，不自动重连
        if (!app.remoteControllerMessage.startsWith("连接失败")) return@LaunchedEffect
        if (recoveryJob?.isActive == true) return@LaunchedEffect
        if (app.remoteControllerBusy) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < 10_000) return@LaunchedEffect
        if (attempts >= 3) {
            app.showRemoteControllerMessage("自动重连多次失败，请检查对方设备后手动重连")
            return@LaunchedEffect
        }
        lastAttemptAt = now
        attempts += 1
        app.showRemoteControllerMessage("正在自动重连（$attempts/3）…")
        recoveryJob = scope.launch { recoverLostConnection(app, lost) }
    }

    return lastRecord
}

/** 一次自动重连尝试：短时 NSD 扫描 → 心跳验证认回 → 落盘 → 重连。 */
private suspend fun recoverLostConnection(app: ReaderApp, lost: PairedDevice) {
    app.startDeviceDiscovery()
    try {
        delay(4_000)
    } finally {
        app.stopDeviceDiscovery()
    }
    val updates = withContext(Dispatchers.IO) {
        reconcileDiscoveredDevices(app.pairedDevices, app.transferDevices)
    }
    val updated = updates.firstOrNull { it.deviceId == lost.deviceId }
    when {
        updated != null -> {
            // 换了 IP/端口：先落盘新地址再重连
            app.upsertPairedDevice(updated)
            app.connectPairedRemoteDevice(updated)
        }
        // 地址没变（瞬时断连）：发现结果对不上但原地址可达，直接重连
        isDeviceOnline(lost.host, lost.port) -> app.connectPairedRemoteDevice(lost)
        else -> app.showRemoteControllerMessage(
            "自动重连失败：附近未找到可认回的「${lost.name}」，" +
                "请确认对方已打开本 App 并开启「允许被附近设备发现」"
        )
    }
}
