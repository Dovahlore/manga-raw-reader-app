package com.mit.reader.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 接收服务首选端口：固定后，被控端重启时遥控端保存的 host:port 仍有效；被占用则退回随机端口。 */
private const val TRANSFER_PREFERRED_PORT = 35217

private fun pickTransferBindPort(): Int = runCatching {
    java.net.ServerSocket(TRANSFER_PREFERRED_PORT).use { TRANSFER_PREFERRED_PORT }
}.getOrDefault(0)

/**
 * 设备对传的接收方：本地 HTTP 服务 + NSD 广播。
 * 发送方发现后把书推过来；这里自动接收（无需逐次确认）、断点续传、校验后原子导入。
 */
class TransferServer(
    context: Context,
    private val localDeviceId: String,
    private val existingBookByHash: (String) -> Book?,
    private val hasTranslatedPage: (String, Int) -> Boolean,
    private val importBook: (File, String) -> TransferImportResult,
    private val onIncoming: (sessionId: String, title: String) -> Unit,
    private val onProgress: (sessionId: String, received: Long, total: Long) -> Unit,
    private val remotePair: (
        code: String,
        controllerName: String,
        controllerId: String,
        controllerEndpoint: String,
    ) -> RemotePairResult?,
    private val remoteHeartbeat: (token: String, intent: String) -> RemoteHostStatus,
    private val remoteCommand: (token: String, action: RemotePageAction, requestId: String) -> RemoteCommandAck,
    private val isTransferAuthorized: (token: String) -> Boolean,
    /** 最近一次 pair 被拒原因（宿主本地诊断）——透传给控制端，锁定/未开被控不再只有通用文案。 */
    private val lastPairReject: (() -> String?)? = null,
) : NanoHTTPD(pickTransferBindPort()) {
    private companion object {
        const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
        const val MAX_ACTIVE_SESSIONS = 8
        const val SESSION_TIMEOUT_MS = 30L * 60 * 1000
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("mit:transfer").apply { setReferenceCounted(false) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceName = "MIT-Reader-${android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "-")}".take(60)
    private val stagingRoot = File(context.cacheDir, "transfers").apply { mkdirs() }

    @Volatile private var discoverable = false
    @Volatile private var nsdRegistered = false

        // 修复（02-低2）：后台会话清扫线程（start 成功后启动，stop 时取消）
    private var sessionSweeper: java.util.Timer? = null

    private data class Session(
        val manifest: JSONObject,
        val stagingDir: File,
        val files: Map<String, TransferFileEntry>,
        val pageIndexByPath: Map<String, Int>,
        val receivedBytes: MutableMap<String, Long> = ConcurrentHashMap(),
        var totalBytes: Long = 0L,
        val createdAt: Long = System.currentTimeMillis(),
        val token: String,
        @Volatile var completed: Boolean = false,
    )
    private val sessions = ConcurrentHashMap<String, Session>()
    private val fileLocks = ConcurrentHashMap<String, Any>()

    // 修复（03-中9）：/remote/command 按凭据的滑动窗口限流（1 秒内最多 10 条）
    private val commandTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    // 修复（02-中2）：注册状态只由 NSD 回调翻转，不再乐观置位；listener 每次注册/注销独立新建。
    // onRegistrationFailed 自动重试一次的闸门（startReceiver/setDiscoverable 开启时复位）。
    private var nsdRetryUsed = false

    /** 修复（V5-中1）：保存注册时的 listener 实例——AOSP 注销要求同一实例，
     *  新建实例会抛 listener not registered 被吞，导致关「可被发现」后服务仍广播到进程退出。 */
    @Volatile private var activeRegListener: NsdManager.RegistrationListener? = null

    /** 修复（02-中11）：接收服务启动失败向前台暴露（ReaderApp 透传到设备页红字提示）。 */
    val startError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun startReceiver() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            android.util.Log.i("TransferServer", "对传接收服务已启动，端口 ${getListeningPort()}")
            startError.value = null
            // 修复（02-低2）：服务起来后启动过期会话清扫线程；重试闸门复位
            startSessionSweeper()
            nsdRetryUsed = false
        } catch (e: Exception) {
            android.util.Log.e("TransferServer", "对传接收服务启动失败", e)
            startError.value = "端口绑定失败（${e.message ?: "未知错误"}）"
        }
        if (startError.value != null) {
            runCatching { if (multicastLock.isHeld) multicastLock.release() }
            return   // 服务没起来就不再广播 NSD，避免「看得到连不上」
        }
        multicastLock.acquire()
        if (discoverable) registerService(getListeningPort())
    }

    /** 可被发现（NSD 广播）开关：关闭后其他设备搜索不到本机，手动 IP:端口 连接不受影响。 */
    fun setDiscoverable(enabled: Boolean) {
        discoverable = enabled
        if (enabled && !nsdRegistered && startError.value == null) {
            nsdRetryUsed = false
            registerService(getListeningPort())
        } else if (!enabled && nsdRegistered) {
            runCatching { unregisterService() }
        }
    }

    fun stopReceiver() {
        // 修复（02-低2）：停服时一并取消会话清扫线程
        runCatching { sessionSweeper?.cancel() }
        sessionSweeper = null
        runCatching { unregisterService() }
        runCatching { stop() }
        runCatching { if (multicastLock.isHeld) multicastLock.release() }
        runCatching { stagingRoot.deleteRecursively() }
    }

    fun localEndpoint(): String? {
        val address = NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?: return null
        // 修复（02-中11 瑕疵）：服务未起来（端口无效）时不报假地址
        val port = getListeningPort()
        if (port <= 0) return null
        return "${address.hostAddress}:$port"
    }

    private fun registerService(port: Int) {
        runCatching {
            val info = NsdServiceInfo().apply {
                serviceName = this@TransferServer.serviceName
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute(NSD_KEY_DEVICE_ID, localDeviceId)
            }
            // 修复（02-中2）：每次注册新建 listener；注册状态只在 onServiceRegistered 置 true
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    nsdRegistered = true
                    android.util.Log.i("TransferServer", "NSD 服务已注册: ${info.serviceName}")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    onNsdUnregistered(this)
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    nsdRegistered = false
                    android.util.Log.w("TransferServer", "NSD 注册失败 errorCode=$errorCode")
                    if (!nsdRetryUsed) {
                        // 自动重试一次（闸门防止失败风暴）
                        nsdRetryUsed = true
                        mainHandler.post {
                            if (discoverable && !nsdRegistered) registerService(port)
                        }
                    }
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            activeRegListener = listener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    /** 修复（02-中2 残留）：注销落地时若开关又已打开（快速关→开竞态），立即补注册，
     *  避免「显示可被发现但实际未广播」的假可发现；若期间已有更新的注册接管
     *  （activeRegListener 换人），本回调不动状态、不重复注册。 */
    private fun onNsdUnregistered(from: NsdManager.RegistrationListener) {
        if (activeRegListener != null && activeRegListener !== from) return
        nsdRegistered = false
        if (discoverable && startError.value == null) {
            mainHandler.post {
                if (discoverable && !nsdRegistered && activeRegListener == null) {
                    registerService(getListeningPort())
                }
            }
        }
    }

    private fun unregisterService() {
        // 修复（V5-中1）：注销必须复用注册时的同一 listener 实例（AOSP 契约），
        // 否则 unregisterService 抛 listener not registered 被 runCatching 吞掉，服务照常广播
        val listener = activeRegListener
        activeRegListener = null
        if (listener == null) {
            nsdRegistered = false
            return
        }
        runCatching { nsdManager.unregisterService(listener) }
        nsdRegistered = false
    }

    override fun serve(session: IHTTPSession): Response = try {
        val uri = session.uri
        when {
            uri == "/remote/pair" && session.method.name == "POST" -> handleRemotePair(session)
            uri == "/remote/heartbeat" && session.method.name == "POST" -> handleRemoteHeartbeat(session)
            uri == "/remote/command" && session.method.name == "POST" -> handleRemoteCommand(session)
            uri == "/session" && session.method.name == "POST" -> handleSession(session)
            uri.startsWith("/files/") -> handleFile(session, uri)
            uri.startsWith("/complete/") -> handleComplete(session, uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    } catch (e: Exception) {
        // 修复（02-低1）：对外固定文案，内部细节只进日志
        android.util.Log.w("TransferServer", "请求处理失败: ${e.message}")
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "请求处理失败")
    }

    // ---------------------------------------------------------------- remote control

    private fun handleRemotePair(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        val code = body.optString("code")
        val controllerName = body.optString("controllerName")
        val controllerId = body.optString("controllerId")
        val controllerEndpoint = body.optString("controllerEndpoint")
        val paired = remotePair(code, controllerName, controllerId, controllerEndpoint)
            ?: return badRequest(lastPairReject?.invoke()?.takeIf { it.isNotBlank() }
                ?: "配对码错误或阅读端未开启遥控")
        return jsonResponse(JSONObject()
            .put("ok", true)
            .put("token", paired.token)
            .put("hostDeviceId", paired.hostDeviceId)
            .put("hostName", paired.hostName)
            // 修复（02-中9 断链）：序列化 endpointMissing，控制端据此提示半残配对
            .put("endpointMissing", paired.endpointMissing))
    }

    private fun handleRemoteHeartbeat(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        // 修复（03 区启发式替代）：透传心跳意图——control=遥控会话（触发确认弹窗）、
        // probe=探活/换 IP 认回（不触发）、空=旧版对端未声明（被控端回落 8 秒存活启发式）
        return jsonResponse(remoteHeartbeat(body.optString("token"), body.optString("intent", "")).toJson())
    }

    private fun handleRemoteCommand(session: IHTTPSession): Response {
        val body = JSONObject(readBody(session))
        val action = RemotePageAction.fromWireName(body.optString("action"))
            ?: return badRequest("未知遥控命令")
        val token = body.optString("token")
        // 修复（03-中9）：滑动窗口限流（1 秒内最多 10 条），超限直接拒绝，不进主线程派发
        if (commandRateLimited(token)) {
            return jsonResponse(RemoteCommandAck(false, false, "命令过于频繁", null).toJson())
        }
        val ack = remoteCommand(token, action, body.optString("requestId"))
        return jsonResponse(ack.toJson())
    }

    /** 修复（03-中9）：按凭据滑动窗口限流；超限返回 true。窗口表膨胀时整体重置兜底。 */
    private fun commandRateLimited(token: String): Boolean {
        val now = System.currentTimeMillis()
        if (commandTimestamps.size > 64) {
            // 修复（V2-②4）：先淘汰已过期窗口，仅在水表仍超限时整体清空——
            // 防持有效凭据者交错伪造凭据触发 clear() 连带重置自己的限流窗口
            commandTimestamps.entries.removeIf { (_, q) -> synchronized(q) { q.isEmpty() || now - q.last() >= 1000L } }
            if (commandTimestamps.size > 64) commandTimestamps.clear()
        }
        val q = commandTimestamps.computeIfAbsent(token) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() >= 1000L) q.removeFirst()
            if (q.size >= 10) return true
            q.addLast(now)
        }
        return false
    }

    // ---------------------------------------------------------------- /session

    private fun handleSession(session: IHTTPSession): Response {
        val token = bearerToken(session) ?: return unauthorizedTransfer()
        if (!isTransferAuthorized(token)) return unauthorizedTransfer()
        val body = readBody(session)
        val manifest = JSONObject(body)
        val hash = manifest.optString("hash")
        val title = manifest.optString("title").ifBlank { "未命名" }

        val files = LinkedHashMap<String, TransferFileEntry>()
        val pageIndexByPath = mutableMapOf<String, Int>()
        val pages = manifest.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until pages.length()) {
            val o = pages.getJSONObject(i)
            val name = o.getString("name")
            require(safeName(name)) { "非法页文件名: $name" }
            files["pages/$name"] = TransferFileEntry("pages/$name", File(name), o.optLong("size"), o.optString("sha256"))
        }
        val translated = manifest.optJSONArray("translated") ?: JSONArray()
        for (i in 0 until translated.length()) {
            val o = translated.getJSONObject(i)
            val name = o.getString("name")
            require(safeName(name)) { "非法译文文件名: $name" }
            val relPath = "translated/$name"
            files[relPath] = TransferFileEntry(relPath, File(name), o.optLong("size"), o.optString("sha256"))
            pageIndexByPath[relPath] = o.optInt("pageIndex", -1)
        }

        // 对端已有同内容书 → 直接拦截，不建会话（避免孤儿 staging 目录）
        val existing = if (hash.isNotEmpty()) existingBookByHash(hash) else null
        if (existing != null) {
            return jsonResponse(JSONObject().apply {
                put("existed", true)
                put("title", title)
            })
        }

        val need = files.keys.toList()
        val requiredBytes = need.sumOf { files[it]?.size ?: 0L }
        require(requiredBytes >= 0) { "传输清单大小非法" }
        // 预留 64MB 余量，避免解压/建索引时把空间撑满
        val usable = stagingRoot.parentFile?.usableSpace ?: 0L
        val spaceOk = usable >= requiredBytes + 64L * 1024 * 1024
        require(spaceOk) { "设备空间不足" }

        val sessionId = UUID.randomUUID().toString()
        val stagingDir = File(stagingRoot, sessionId).apply { mkdirs() }
        File(stagingDir, "manifest.json").writeText(body)
        // 修复（02-低2）：上限检查与 put 收进同一把 sessions 锁，消除检查-放入竞态；
        // 被拒时清掉刚建的 staging 目录
        synchronized(sessions) {
            if (sessions.size >= MAX_ACTIVE_SESSIONS) {
                runCatching { stagingDir.deleteRecursively() }
                return badRequest("传输会话过多，请稍后重试")
            }
            sessions[sessionId] = Session(
                manifest, stagingDir, files, pageIndexByPath, ConcurrentHashMap(),
                totalBytes = requiredBytes,
                token = token,
            )
        }
        mainHandler.post { onIncoming(sessionId, title) }

        return jsonResponse(JSONObject().apply {
            put("sessionId", sessionId)
            put("need", JSONArray(need))
            put("spaceOk", spaceOk)
            put("requiredBytes", requiredBytes)
            put("title", title)
        })
    }

    // ---------------------------------------------------------------- /files

    private fun handleFile(session: IHTTPSession, uri: String): Response {
        val token = bearerToken(session) ?: return unauthorizedTransfer()
        val parts = uri.removePrefix("/files/").split("/", limit = 2)
        if (parts.size != 2) return badRequest("路径错误")
        val sessionId = parts[0]
        val rest = parts[1]
        val st = validSession(sessionId) ?: return badRequest("未知会话")
        if (st.token != token) return unauthorizedTransfer()

        // 查断点：GET /files/{sid}/{relPath}/offset
        if (rest.endsWith("/offset")) {
            if (session.method.name != "GET") return badRequest("需要 GET")
            val relPath = rest.removeSuffix("/offset")
            if (!safeRelPath(relPath)) return badRequest("非法路径")
            val entry = st.files[relPath] ?: return badRequest("未知文件")
            val part = File(st.stagingDir, relPath + ".part")
            val offset = if (part.exists()) part.length() else 0L
            val final = File(st.stagingDir, relPath)
            if (final.exists() && final.length() == entry.size) {
                return jsonResponse(JSONObject().put("offset", entry.size).put("done", true))
            }
            return jsonResponse(JSONObject().put("offset", offset).put("done", false))
        }

        // 传文件：PUT /files/{sid}/{relPath}
        if (session.method.name != "PUT") return badRequest("需要 PUT")
        val relPath = rest
        if (!safeRelPath(relPath)) return badRequest("非法路径")
        val entry = st.files[relPath] ?: return badRequest("未知文件")
        val part = File(st.stagingDir, relPath + ".part").apply { parentFile?.mkdirs() }
        val range = session.headers["range"]
        val startOffset = range?.substringAfter("bytes=")?.substringBefore("-")?.toLongOrNull() ?: 0L
        require(startOffset in 0..entry.size) { "非法续传偏移" }
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        require(startOffset + contentLength <= entry.size) { "上传大小超过清单" }
        // 修复（02-中13）：同一会话同一文件串行写，防两线程交错 setLength/追加写损坏数据
        val fileLock = fileLocks.computeIfAbsent("$sessionId/$relPath") { Any() }
        synchronized(fileLock) {
        RandomAccessFile(part, "rw").use { it.setLength(startOffset) }
        // 只读 Content-Length 个字节就停：客户端发完 body 后仍保持连接等响应，读到 EOF 会一直阻塞
        var remaining = contentLength
        var received = startOffset
        val os = FileOutputStream(part, true)
        val buf = ByteArray(64 * 1024)
        try {
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = session.inputStream.read(buf, 0, want)
                if (n <= 0) break
                os.write(buf, 0, n)
                received += n
                remaining -= n
            }
        } finally {
            os.close()
        }
        st.receivedBytes[relPath] = received
        notifyProgress(st, sessionId)

        if (received == entry.size) {
            val sha = sha256(part)
            if (!sha.equals(entry.sha256, ignoreCase = true)) {
                part.delete()
                return badRequest("校验失败: $relPath")
            }
            val final = File(st.stagingDir, relPath).apply { parentFile?.mkdirs() }
            if (!part.renameTo(final)) {
                part.copyTo(final, overwrite = true)
                part.delete()
            }
            notifyProgress(st, sessionId)
        }
        return jsonResponse(JSONObject().put("ok", true).put("received", received))
        }
    }

    // ---------------------------------------------------------------- /complete

    private fun handleComplete(session: IHTTPSession, uri: String): Response {
        val token = bearerToken(session) ?: return unauthorizedTransfer()
        val sessionId = uri.removePrefix("/complete/")
        val st = validSession(sessionId) ?: return badRequest("未知会话")
        if (st.token != token) return unauthorizedTransfer()
        if (st.completed) return jsonResponse(JSONObject().put("ok", true).put("duplicate", true))
        val manifestJson = File(st.stagingDir, "manifest.json").readText()
        // 修复（02-中12/05-中4）：先导入成功再置 completed——导入失败保留会话可重试，不再「假成功」
        val result = importBook(st.stagingDir, manifestJson)
        st.completed = true
        sessions.remove(sessionId)
        // 修复（02-低5 瑕疵）：会话终结顺带清掉该会话的文件锁条目，防长期进程缓慢累积
        fileLocks.keys.removeIf { it.startsWith("$sessionId/") }
        runCatching { st.stagingDir.deleteRecursively() }
        return jsonResponse(JSONObject().apply {
            put("ok", true)
            put("duplicate", result.duplicate)
            put("addedPages", result.addedPages)
            put("skippedPages", result.skippedPages)
            put("title", result.book.title)
        })
    }

    // ---------------------------------------------------------------- 内部

    private var lastNotify = 0L

    private fun notifyProgress(st: Session, sessionId: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 200) return
        lastNotify = now
        val received = st.receivedBytes.values.sum()
        mainHandler.post { onProgress(sessionId, received, st.totalBytes) }
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
        require(len in 0..MAX_MANIFEST_BYTES) { "传输清单过大" }
        val bytes = if (len > 0) {
            val buf = ByteArray(len.toInt())
            var off = 0
            while (off < buf.size) {
                val n = session.inputStream.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            buf.copyOf(off)
        } else {
            // 修复（05-低7/P9）：无 Content-Length 的请求体加 8MB 硬顶，防未认证端点读爆内存
            val cap = 8L * 1024 * 1024
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = session.inputStream.read(buf)
                if (n <= 0) break
                total += n
                require(total <= cap) { "请求体过大" }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun validSession(sessionId: String): Session? {
        val st = sessions[sessionId] ?: return null
        if (System.currentTimeMillis() - st.createdAt > SESSION_TIMEOUT_MS) {
            sessions.remove(sessionId)
            runCatching { st.stagingDir.deleteRecursively() }
            return null
        }
        return st
    }

    /** 修复（02-低2）：每 10 分钟后台清扫过期会话——复用 validSession 的超时判断（命中即移除并删 staging）。 */
    private fun startSessionSweeper() {
        if (sessionSweeper != null) return
        sessionSweeper = java.util.Timer("transfer-session-sweeper", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() { sweepExpiredSessions() }
            }, 10L * 60 * 1000, 10L * 60 * 1000)
        }
    }

    private fun sweepExpiredSessions() {
        runCatching {
            sessions.keys.toList().forEach { sid -> validSession(sid) }
        }
    }

    private fun safeName(name: String): Boolean =
        name.isNotBlank() && !name.contains("/") && !name.contains("\\") && !name.contains("..")

    private fun safeRelPath(p: String): Boolean {
        if (p.isBlank() || p.startsWith("/") || p.contains("..") || p.contains("\\")) return false
        return p.startsWith("pages/") || p.startsWith("translated/")
    }

    private fun bearerToken(session: IHTTPSession): String? {
        val header = session.headers["authorization"]?.trim() ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        return header.substring(7).trim().takeIf { it.isNotBlank() }
    }

    private fun unauthorizedTransfer(): Response =
        newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "text/plain",
            "设备未配对，请先在遥控器模式输入配对码",
        )

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
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

    private fun jsonResponse(o: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    private fun RemoteHostStatus.toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("reason", reason ?: "")
        .put("readerActive", readerActive)
        .put("revoked", revoked)
        .put("reader", reader.toJson())

    private fun RemoteCommandAck.toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("accepted", accepted)
        .put("reason", reason ?: "")
        .put("reader", reader.toJson())

    private fun RemoteReaderSnapshot?.toJson(): Any {
        this ?: return JSONObject.NULL
        return JSONObject()
            .put("bookId", bookId)
            .put("title", title)
            .put("page", page)
            .put("pageCount", pageCount)
    }

    private fun badRequest(msg: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)
}
