package com.mit.reader.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mit.reader.MainActivity
import com.mit.reader.ReaderApp
import kotlinx.coroutines.delay

/** 跨页面共享的遥控会话状态：acceptedControllerId 非空且等于当前连入控制器 = 本机已被弹窗确认、正处于被遥控中；名字仅用于展示。 */
object RemoteControlUi {
    var acceptedControllerName by mutableStateOf<String?>(null)

    // 修复（03-中7）：接受态以稳定 id 跟踪，同名设备不再串态
    var acceptedControllerId by mutableStateOf<String?>(null)
}

/**
 * 弹窗确认式被控模型（v48 起，替代「允许其他设备遥控本机」开关）：
 * - 配对 = 纯凭据交换：被控接受态由本宿主静默保活（每分钟续期，绕开旧 5 分钟自动关闭的 Toast），
 *   配对码在「书库 · 设备」页常显；对方输码即配对成功（不弹窗），配对后可直接向本机分享书籍；
 * - 遥控 = 单独的请求：已配对设备发起连接且会话持续存活（8 秒，用于区分配对瞬间的短暂连入）
 *   时，本机弹窗「是否接受遥控」；接受前翻页能力被压制，每次会话都要重新确认；
 * - 接受后持续被遥控，同时发系统通知「正在被 XXX 遥控」，会话结束自动撤除；
 *   被控端断开入口：设备页「断开」、阅读器底栏「退出被遥控」；拒绝后 60 秒内拦截同一设备重连。
 */
private const val CONTROL_CHANNEL_ID = "remote_control_session"
private const val CONTROL_NOTIFY_ID = 4701

/** 配对回弹（对方配对成功后几秒内即停心跳）与真实遥控请求（心跳持续）的分界。 */
private const val CONTROL_REQUEST_SURVIVE_MS = 8_000L

/** 拒绝后拦截同一设备自动重连的时长。 */
private const val DENY_COOLDOWN_MS = 60_000L

private fun ensureControlChannel(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CONTROL_CHANNEL_ID) == null) {
        nm.createNotificationChannel(
            NotificationChannel(CONTROL_CHANNEL_ID, "遥控状态", NotificationManager.IMPORTANCE_LOW)
        )
    }
}

private fun postControlledNotification(context: Context, controllerName: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    ensureControlChannel(context)
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pi = PendingIntent.getActivity(
        context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val notification = Notification.Builder(context, CONTROL_CHANNEL_ID)
        .setSmallIcon(context.applicationInfo.icon)
        .setContentTitle("正在被「$controllerName」遥控")
        .setContentText("对方可翻页。阅读器底栏可退出被遥控")
        .setOngoing(true)
        .setContentIntent(pi)
        .build()
    androidx.core.app.NotificationManagerCompat.from(context).notify(CONTROL_NOTIFY_ID, notification)
}

private fun cancelControlledNotification(context: Context) {
    androidx.core.app.NotificationManagerCompat.from(context).cancel(CONTROL_NOTIFY_ID)
}

private data class ConsentRequest(val controllerName: String, val at: Long)

@Composable
fun RemoteControlConsentHost(app: ReaderApp) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ConsentRequest?>(null) }
    val deniedAtByName = remember { mutableStateMapOf<String, Long>() }
    // 修复（02-中8/03-中7）：拒绝冷却同步记 id，判定优先 id，名字仅作兜底
    val deniedAtById = remember { mutableStateMapOf<String, Long>() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 修复（03-低11）：接受会话后授权成功的当下立即补发「正在被遥控」通知
        if (granted && RemoteControlUi.acceptedControllerName != null) {
            postControlledNotification(context, RemoteControlUi.acceptedControllerName!!)
        }
    }

    // ---- 被控接受态保活：挂载时立即开启，之后每分钟静默续期（重置旧 5 分钟自动关闭计时）----
    LaunchedEffect(Unit) {
        if (!app.remoteControllerScreenActive && app.remoteControllerConnection == null) {
            app.setRemoteHostEnabled(true, refreshCode = false)
        }
        while (true) {
            delay(60_000)
            if (!app.remoteControllerScreenActive && app.remoteControllerConnection == null) {
                app.setRemoteHostEnabled(true, refreshCode = false)
            }
        }
    }

    // ---- 会话边界检测：协议级意图（intent）优先，旧版对端回落 8 秒存活启发式 ----
    // 修复（03-中7）：「是否已接受/是否同一会话」全部改用 controllerId 判定（名字仅用于展示），
    // pending 判定用 pending 状态本身。不拿 sessionPhase() 当「已同意」用：manager 级 accepted
    // 由心跳即置位（已配对凭据一连入就 accepted），用它判定会让已配对回连/新配对会话跳过弹窗，
    // 同名绕过的修复必须落在 id 身份对比上。
    // 修复（03 区启发式替代）：新版对端心跳携带 intent——control=遥控会话（连续 2 次即弹窗，
    // 兼探活的 2s 心跳节奏），probe=探活/认回（永不弹窗），pair=配对回弹（不弹）；
    // intent 缺失（旧版对端）才回落原 8 秒存活启发式。
    LaunchedEffect(app.remoteControl.connectedControllerName, app.remoteControl.sessionIntent, app.remoteControl.controlBeats) {
        val name = app.remoteControl.connectedControllerName
        if (name == null) {
            // 会话结束（对方断开/静默超时/本机拒绝）：清理弹窗、通知与接受态
            if (RemoteControlUi.acceptedControllerName != null || pending != null) {
                cancelControlledNotification(context)
            }
            RemoteControlUi.acceptedControllerId = null
            RemoteControlUi.acceptedControllerName = null
            pending = null
            // 修复（03-高2 残留）：会话结束立即收回远端翻页能力，不再依赖下一轮 500ms 压制
            app.remoteControl.clearReaderInteractionAllowed()
        } else if (RemoteControlUi.acceptedControllerId != app.remoteControl.currentControllerId() &&
            pending == null
        ) {
            val controllerId = app.remoteControl.currentControllerId()
            // 冷却判定优先 id，名字兜底（旧数据）
            val deniedAt = controllerId?.let { deniedAtById[it] } ?: deniedAtByName[name]
            if (deniedAt != null && System.currentTimeMillis() - deniedAt < DENY_COOLDOWN_MS) {
                // 刚拒绝过同一设备：直接掐断，防止其凭已保存授权自动重连回来
                app.disconnectRemoteHostController()
            } else if (app.remoteControl.sessionIntent == "control" && app.remoteControl.controlBeats >= 2) {
                // 协议级：对端明确声明遥控意图且连续两次心跳——立即弹窗，不再等 8 秒
                pending = ConsentRequest(name, System.currentTimeMillis())
            } else if (app.remoteControl.sessionIntent == null) {
                // 旧版对端（心跳无 intent 字段）：维持 8 秒存活启发式
                delay(CONTROL_REQUEST_SURVIVE_MS)
                // 分界时长后仍是同一台设备（id 相同）且未被接受过，才弹窗
                if (controllerId != null &&
                    app.remoteControl.currentControllerId() == controllerId &&
                    RemoteControlUi.acceptedControllerId != controllerId
                ) {
                    pending = ConsentRequest(name, System.currentTimeMillis())
                }
            }
            // intent=="probe"/"pair"：探活/配对回弹，不弹窗
        }
    }

    // ---- 接受前压制翻页能力：未接受的会话每 500ms 重申一次，覆盖 resume/pause 路径的覆写 ----
    // 修复（03-中7）：压制/放行按 id 判定（acceptedControllerId 是否等于当前连入控制器）
    LaunchedEffect(pending, RemoteControlUi.acceptedControllerId, app.remoteControl.connectedControllerName) {
        val session = app.remoteControl.connectedControllerName
        if (session != null && RemoteControlUi.acceptedControllerId != app.remoteControl.currentControllerId()) {
            while (true) {
                app.remoteControl.updateReaderInteractionAllowed(false)
                delay(500)
            }
        } else if (RemoteControlUi.acceptedControllerId != null) {
            app.remoteControl.updateReaderInteractionAllowed(app.readerScreenActive)
        }
    }

    // ---- 阅读器进出时，已接受的会话要跟随刷新 ----
    LaunchedEffect(app.readerScreenActive) {
        if (RemoteControlUi.acceptedControllerId != null && pending == null) {
            app.remoteControl.updateReaderInteractionAllowed(app.readerScreenActive)
        }
    }

    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { /* 必须显式选择：不点按钮不消失 */ },
            title = { Text("遥控请求") },
            text = { Text("「${request.controllerName}」请求遥控本机。接受后对方可翻页，直到任一方断开。") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    // 修复（03-中7）：接受态写入 manager 当前连入控制器的稳定 id，名字仅展示
                    RemoteControlUi.acceptedControllerId = app.remoteControl.currentControllerId()
                    RemoteControlUi.acceptedControllerName = request.controllerName
                    app.remoteControl.updateReaderInteractionAllowed(app.readerScreenActive)
                    if (Build.VERSION.SDK_INT >= 33) {
                        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    postControlledNotification(context, request.controllerName)
                }) { Text("接受") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    // 修复（02-中8/03-中7）：冷却写入点同步记 id，判定优先 id
                    app.remoteControl.currentControllerId()?.let {
                        deniedAtById[it] = System.currentTimeMillis()
                    }
                    deniedAtByName[request.controllerName] = System.currentTimeMillis()
                    app.disconnectRemoteHostController()
                }) { Text("拒绝") }
            },
        )
    }
}
