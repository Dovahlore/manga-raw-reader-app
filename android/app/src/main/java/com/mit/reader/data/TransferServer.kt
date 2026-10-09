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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备对传的接收方：本地 HTTP 服务 + NSD 广播。
 * 发送方发现后把书推过来；这里自动接收（无需逐次确认）、断点续传、校验后原子导入。
 */
class TransferServer(
    context: Context,
    private val existingBookByHash: (String) -> Book?,
    private val hasTranslatedPage: (String, Int) -> Boolean,
    private val importBook: (File, String) -> TransferImportResult,
    private val onIncoming: (sessionId: String, title: String) -> Unit,
    private val onProgress: (sessionId: String, received: Long, total: Long) -> Unit,
) : NanoHTTPD(0) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("mit:transfer").apply { setReferenceCounted(false) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceName = "MIT-Reader-${android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "-")}".take(60)
    private val stagingRoot = File(context.cacheDir, "transfers").apply { mkdirs() }

    private data class Session(
        val manifest: JSONObject,
        val stagingDir: File,
        val files: Map<String, TransferFileEntry>,
        val pageIndexByPath: Map<String, Int>,
        val receivedBytes: MutableMap<String, Long> = ConcurrentHashMap(),
        var totalBytes: Long = 0L,
        @Volatile var completed: Boolean = false,
    )
    private val sessions = ConcurrentHashMap<String, Session>()

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    fun startReceiver() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            android.util.Log.i("TransferServer", "对传接收服务已启动，端口 ${getListeningPort()}")
        } catch (e: Exception) {
            android.util.Log.e("TransferServer", "对传接收服务启动失败", e)
        }
        multicastLock.acquire()
        registerService(getListeningPort())
    }

    fun stopReceiver() {
        runCatching { unregisterService() }
        runCatching { stop() }
        runCatching { if (multicastLock.isHeld) multicastLock.release() }
        runCatching { stagingRoot.deleteRecursively() }
    }

    private fun registerService(port: Int) {
        runCatching {
            val info = NsdServiceInfo().apply {
                serviceName = this@TransferServer.serviceName
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }
    }

    private fun unregisterService() = nsdManager.unregisterService(registrationListener)

    override fun serve(session: IHTTPSession): Response = try {
        val uri = session.uri
        when {
            uri == "/session" && session.method.name == "POST" -> handleSession(session)
            uri.startsWith("/files/") -> handleFile(session, uri)
            uri.startsWith("/complete/") -> handleComplete(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", e.message ?: "error")
    }

    // ---------------------------------------------------------------- /session

    private fun handleSession(session: IHTTPSession): Response {
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

        // 增量：同内容书已有 → 原页跳过；已有译文页跳过
        val existing = if (hash.isNotEmpty()) existingBookByHash(hash) else null
        val need = mutableListOf<String>()
        for ((relPath, entry) in files) {
            val already = when {
                relPath.startsWith("pages/") -> existing != null
                relPath.startsWith("translated/") ->
                    existing != null && hasTranslatedPage(existing.id, pageIndexByPath[relPath] ?: -1)
                else -> false
            }
            if (!already) need += relPath
        }
        val requiredBytes = need.sumOf { files[it]?.size ?: 0L }
        // 预留 64MB 余量，避免解压/建索引时把空间撑满
        val usable = stagingRoot.parentFile?.usableSpace ?: 0L
        val spaceOk = usable >= requiredBytes + 64L * 1024 * 1024

        val sessionId = UUID.randomUUID().toString()
        val stagingDir = File(stagingRoot, sessionId).apply { mkdirs() }
        File(stagingDir, "manifest.json").writeText(body)
        sessions[sessionId] = Session(manifest, stagingDir, files, pageIndexByPath, ConcurrentHashMap(), requiredBytes)
        mainHandler.post { onIncoming(sessionId, title) }

        return jsonResponse(JSONObject().apply {
            put("sessionId", sessionId)
            put("need", JSONArray(need))
            put("spaceOk", spaceOk)
            put("requiredBytes", requiredBytes)
            put("title", title)
            put("existed", existing != null)
        })
    }

    // ---------------------------------------------------------------- /files

    private fun handleFile(session: IHTTPSession, uri: String): Response {
        val parts = uri.removePrefix("/files/").split("/", limit = 2)
        if (parts.size != 2) return badRequest("路径错误")
        val sessionId = parts[0]
        val rest = parts[1]
        val st = sessions[sessionId] ?: return badRequest("未知会话")

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
        RandomAccessFile(part, "rw").use { it.setLength(startOffset) }
        // 只读 Content-Length 个字节就停：客户端发完 body 后仍保持连接等响应，读到 EOF 会一直阻塞
        var remaining = session.headers["content-length"]?.toLongOrNull() ?: 0L
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

    // ---------------------------------------------------------------- /complete

    private fun handleComplete(uri: String): Response {
        val sessionId = uri.removePrefix("/complete/")
        val st = sessions[sessionId] ?: return badRequest("未知会话")
        if (st.completed) return jsonResponse(JSONObject().put("ok", true).put("duplicate", true))
        st.completed = true
        val manifestJson = File(st.stagingDir, "manifest.json").readText()
        val result = importBook(st.stagingDir, manifestJson)
        sessions.remove(sessionId)
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
        val bytes = if (len in 1..(16L * 1024 * 1024)) {
            val buf = ByteArray(len.toInt())
            var off = 0
            while (off < buf.size) {
                val n = session.inputStream.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            buf.copyOf(off)
        } else {
            session.inputStream.readBytes()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun safeName(name: String): Boolean =
        name.isNotBlank() && !name.contains("/") && !name.contains("\\") && !name.contains("..")

    private fun safeRelPath(p: String): Boolean {
        if (p.isBlank() || p.startsWith("/") || p.contains("..") || p.contains("\\")) return false
        return p.startsWith("pages/") || p.startsWith("translated/")
    }

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

    private fun badRequest(msg: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)
}
