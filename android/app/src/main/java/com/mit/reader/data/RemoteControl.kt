package com.mit.reader.data

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        }
    }

    fun pair(code: String, controllerName: String, controllerId: String): String? = synchronized(lock) {
        if (!hostEnabled) return null
        if (code.isBlank() || pairingCode.isBlank() || !code.equals(pairingCode, ignoreCase = true)) return null
        val activeControllerId = connectedControllerId
        if (activeControllerId != null && activeControllerId != controllerId) return null
        if (activeControllerId == controllerId && pairingToken.isNotBlank()) return pairingToken
        val token = UUID.randomUUID().toString().replace("-", "")
        pairingToken = token
        connectedControllerName = controllerName.ifBlank { "遥控器" }.take(40)
        connectedControllerId = controllerId
        lastSeenAt = System.currentTimeMillis()
        token
    }

    fun heartbeat(token: String): RemoteHostStatus = synchronized(lock) {
        val reader = readerSnapshot
        val authorized = authorizedController(token) ?: return RemoteHostStatus(
            false, "设备未配对或已取消授权", false, null,
        )
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
        mainHandler.post {
            val handler = synchronized(lock) { readerHandler }
            result = handler?.onCommand(action)
            synchronized(lock) { readerSnapshot = result }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)

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
            pairingToken = ""
            connectedControllerName = null
        }
    }

    private fun expireSilentController() {
        synchronized(lock) {
            if (connectedControllerName != null && System.currentTimeMillis() - lastSeenAt > 6_000) {
                pairingToken = ""
                connectedControllerName = null
                connectedControllerId = null
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
        val activeControllerId = connectedControllerId
        if (activeControllerId != null && activeControllerId != device.deviceId) return false
        connectedControllerName = device.name.ifBlank { "遥控器" }.take(40)
        connectedControllerId = device.deviceId
        return true
    }

    private fun generatePairingCode(): String {
        val value = (100000..999999).random()
        return value.toString()
    }
}
