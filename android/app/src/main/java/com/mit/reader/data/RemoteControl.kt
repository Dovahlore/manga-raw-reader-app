package com.mit.reader.data

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class RemotePageAction(val wireName: String) {
    PREVIOUS("page.prev"),
    NEXT("page.next");

    companion object {
        fun fromWireName(value: String): RemotePageAction? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class RemoteReaderSnapshot(
    val bookId: String,
    val title: String,
    val page: Int,
    val pageCount: Int,
)

data class RemoteCommandAck(
    val ok: Boolean,
    val accepted: Boolean,
    val reason: String?,
    val reader: RemoteReaderSnapshot?,
)

data class RemoteHostStatus(
    val ok: Boolean,
    val reason: String?,
    val readerActive: Boolean,
    val reader: RemoteReaderSnapshot?,
    val revoked: Boolean = false,
)

data class RemoteControllerConnection(
    val device: DiscoveredDevice,
    val token: String,
)

fun interface RemoteReaderCommandHandler {
    fun onCommand(action: RemotePageAction): RemoteReaderSnapshot?
}

class RemoteControlManager(
    private val appScope: CoroutineScope,
    private val mainHandler: Handler,
    private val findAuthorizedController: (token: String) -> PairedDevice?,
) {
    var hostEnabled by mutableStateOf(false)
        private set
    var pairingCode by mutableStateOf("")
        private set
    var connectedControllerName by mutableStateOf<String?>(null)
        private set
    var readerInteractionAllowed by mutableStateOf(false)
        private set

    private val lock = Any()
    private var pairingToken: String = ""
    private var connectedControllerId: String? = null
    private var lastSeenAt = 0L
    private var readerHandler: RemoteReaderCommandHandler? = null
    private var readerSnapshot: RemoteReaderSnapshot? = null
    private val recentRequestIds = LinkedHashSet<String>()

    // ---- 修复（02-高1/03-高1/02-高2 部分）：配对节流、断开冷却、接受态跟踪 ----
    private val secureRandom = SecureRandom()
    private var pairFailures = 0
    private var pairLockoutUntil = 0L
    private var lastPairRejectReason: String? = null
    private val suspendedUntil = mutableMapOf<String, Long>()
    private var acceptedControllerId: String? = null

    init {
        appScope.launch {
            while (true) {
                delay(1_000)
                expireSilentController()
            }
        }
    }

    fun setHostEnabled(enabled: Boolean, refreshCode: Boolean = false) {
        synchronized(lock) {
            if (enabled) {
                if (refreshCode || pairingCode.isBlank()) pairingCode = generatePairingCode()
            } else {
                pairingToken = ""
                connectedControllerName = null
                connectedControllerId = null
                acceptedControllerId = null
                readerHandler = null
                readerSnapshot = null
            }
            hostEnabled = enabled
        }
    }

    fun refreshPairingCode() {
        synchronized(lock) {
            if (!hostEnabled) return
            pairingCode = generatePairingCode()
            pairingToken = ""
            connectedControllerName = null
            connectedControllerId = null
            acceptedControllerId = null
        }
    }

    fun pair(code: String, controllerName: String, controllerId: String): String? = synchronized(lock) {
        // 修复（02-高1/03-高3）：失败节流+指数锁定；修复（02-高2 部分/03-低13）：每次配对轮换新凭据，
        // 旧凭据随覆盖立即失效
        val now = System.currentTimeMillis()
        if (now < pairLockoutUntil) {
            lastPairRejectReason = "尝试过于频繁，请 ${((pairLockoutUntil - now) / 1000) + 1} 秒后再试"
            return null
        }
        if (!hostEnabled) {
            lastPairRejectReason = "阅读端未开启遥控"
            return null
        }
        if (code.isBlank() || pairingCode.isBlank() || !code.equals(pairingCode, ignoreCase = true)) {
            lastPairRejectReason = "配对码错误"
            pairFailures++
            if (pairFailures >= 5) {
                val backoff = (60_000L shl minOf(pairFailures - 5, 4)).coerceAtMost(10 * 60_000L)
                pairLockoutUntil = now + backoff
            }
            return null
        }
        pairFailures = 0
        pairLockoutUntil = 0L
        val activeControllerId = connectedControllerId
        if (activeControllerId != null && activeControllerId != controllerId) {
            lastPairRejectReason = "已有其他遥控器连入"
            return null
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        pairingToken = token
        connectedControllerName = controllerName.ifBlank { "遥控器" }.take(40)
        connectedControllerId = controllerId
        acceptedControllerId = null
        lastSeenAt = System.currentTimeMillis()
        lastPairRejectReason = null
        token
    }

    /** 最近一次 pair 拒绝原因（宿主本地诊断用）。 */
    fun lastPairReject(): String? = synchronized(lock) { lastPairRejectReason }

    private fun isSuspendedInternal(deviceId: String): Boolean =
        (suspendedUntil[deviceId] ?: 0L) > System.currentTimeMillis()

    fun heartbeat(token: String): RemoteHostStatus = synchronized(lock) {
        val reader = readerSnapshot
        val authorized = authorizedController(token)
        if (authorized == null) {
            // 修复（02-中5/03-低10）：revoked=true 仅在凭据确实不匹配任何授权时返回，
            // 控制端据此清理死记录；宿主暂时关被控等场景不再触发误删
            return RemoteHostStatus(false, "设备未配对或已取消授权", false, null, revoked = true)
        }
        if (isSuspendedInternal(authorized.deviceId)) {
            return RemoteHostStatus(false, "已被本机断开，请稍后再连接", false, null)
        }
        if (!hostEnabled || !acceptController(authorized)) {
            return RemoteHostStatus(false, "遥控器未授权或已断开", false, null)
        }
        lastSeenAt = System.currentTimeMillis()
        RemoteHostStatus(
            ok = true,
            reason = null,
            readerActive = reader != null && readerInteractionAllowed,
            reader = reader.takeIf { readerInteractionAllowed },
        )
    }

    fun executeCommand(token: String, action: RemotePageAction, requestId: String): RemoteCommandAck {
        val reader = synchronized(lock) {
            val authorized = authorizedController(token) ?: return RemoteCommandAck(
                false, false, "设备未配对或已取消授权", null,
            )
            if (isSuspendedInternal(authorized.deviceId)) {
                return RemoteCommandAck(false, false, "已被本机断开，请稍后再连接", null)
            }
            if (!hostEnabled || !acceptController(authorized)) {
                return RemoteCommandAck(false, false, "遥控器未授权或已断开", null)
            }
            if (requestId.isBlank()) {
                return RemoteCommandAck(false, false, "请求 ID 为空", null)
            }
            if (!recentRequestIds.add(requestId)) {
                val current = readerSnapshot
                return RemoteCommandAck(true, false, "重复请求", current)
            }
            while (recentRequestIds.size > 128) {
                recentRequestIds.remove(recentRequestIds.first())
            }
            if (!readerInteractionAllowed) {
                return RemoteCommandAck(true, false, "当前不在阅读器", null)
            }
            readerSnapshot ?: return RemoteCommandAck(true, false, "当前不在阅读器", null)
        }

        val latch = CountDownLatch(1)
        var result: RemoteReaderSnapshot? = null
        val task = Runnable {
            // 修复（03-高2）：执行前在主线程再查一次 allowed，消除锁内查/锁外执行的 TOCTOU 窗口
            val allowedNow = synchronized(lock) { readerInteractionAllowed }
            val handler = synchronized(lock) { readerHandler }
            if (allowedNow && handler != null) {
                result = handler.onCommand(action)
                synchronized(lock) { readerSnapshot = result }
            }
            latch.countDown()
        }
        mainHandler.post(task)
        val executed = latch.await(2, TimeUnit.SECONDS)
        if (!executed) {
            // 修复（03-中5）：超时后撤掉未执行的命令，保证 ack 与实际翻页一致
            mainHandler.removeCallbacks(task)
        }

        return synchronized(lock) {
            if (result == null) {
                RemoteCommandAck(true, false, "当前不在阅读器", null)
            } else {
                lastSeenAt = System.currentTimeMillis()
                RemoteCommandAck(true, true, null, result)
            }
        }
    }

    fun registerReader(
        bookId: String,
        title: String,
        pageCount: Int,
        currentPage: Int,
        handler: RemoteReaderCommandHandler?,
    ) {
        synchronized(lock) {
            readerHandler = handler
            readerSnapshot = if (handler == null) {
                null
            } else {
                RemoteReaderSnapshot(bookId, title, currentPage, pageCount)
            }
        }
    }

    fun updateReader(bookId: String, title: String, page: Int, pageCount: Int) {
        synchronized(lock) {
            if (readerHandler == null) {
                readerSnapshot = null
            } else {
                readerSnapshot = RemoteReaderSnapshot(bookId, title, page, pageCount)
            }
        }
    }

    fun updateReaderInteractionAllowed(allowed: Boolean) {
        readerInteractionAllowed = allowed
    }

    /** 修复（03-高2）：会话阶段供 UI 判定——pending=有连入但未接受（远端命令必须被拒）。 */
    fun sessionPhase(): String = synchronized(lock) {
        when {
            connectedControllerId == null -> "none"
            acceptedControllerId != null && acceptedControllerId == connectedControllerId -> "accepted"
            else -> "pending"
        }
    }

    fun clearReaderInteractionAllowed() {
        readerInteractionAllowed = false
    }

    fun dispatchLocalCommand(action: RemotePageAction): RemoteReaderSnapshot? {
        val handler = synchronized(lock) { readerHandler } ?: return null
        val result = handler.onCommand(action)
        synchronized(lock) { readerSnapshot = result }
        return result
    }

    fun unregisterReader(handler: RemoteReaderCommandHandler) {
        synchronized(lock) {
            if (readerHandler === handler) {
                readerHandler = null
                readerSnapshot = null
            }
        }
    }

    fun disconnectController() {
        synchronized(lock) {
            // 修复（03-中4）：连 id 一起清，避免旧设备「永久占坑」卡死后续配对；
            // 修复（03-高1/02-中7）：断开即对该设备挂 60 秒冷却，凭已保存授权的心跳也会被拒
            connectedControllerId?.let { suspendedUntil[it] = System.currentTimeMillis() + 60_000 }
            acceptedControllerId = null
            pairingToken = ""
            connectedControllerName = null
            connectedControllerId = null
        }
    }

    private fun expireSilentController() {
        synchronized(lock) {
            if ((connectedControllerName != null || connectedControllerId != null) &&
                System.currentTimeMillis() - lastSeenAt > 6_000
            ) {
                pairingToken = ""
                connectedControllerName = null
                connectedControllerId = null
                acceptedControllerId = null
            }
        }
    }

    private fun authorizedController(token: String): PairedDevice? {
        if (token.isNotBlank() && token == pairingToken) {
            return PairedDevice(
                connectedControllerId ?: return null,
                connectedControllerName ?: "遥控器",
                "", 0, token, System.currentTimeMillis(),
            )
        }
        return findAuthorizedController(token)
    }

    private fun acceptController(device: PairedDevice): Boolean {
        if (isSuspendedInternal(device.deviceId)) return false
        val activeControllerId = connectedControllerId
        if (activeControllerId != null && activeControllerId != device.deviceId) return false
        connectedControllerName = device.name.ifBlank { "遥控器" }.take(40)
        connectedControllerId = device.deviceId
        acceptedControllerId = device.deviceId
        return true
    }

    private fun generatePairingCode(): String {
        // 修复（02-低4）：改用加密安全随机源
        val value = secureRandom.nextInt(900000) + 100000
        return value.toString()
    }
}
