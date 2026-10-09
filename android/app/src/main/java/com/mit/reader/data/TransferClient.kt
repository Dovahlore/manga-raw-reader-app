package com.mit.reader.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 设备对传的发送方：NSD 发现接收设备 + 把一本书推过去。
 * 流程：/session（拿 plan + 空间检查）→ 逐文件 PUT（Range 断点续传）→ /complete（触发接收方校验导入）。
 */
class TransferClient(
    context: Context,
    private val onDeviceFound: (DiscoveredDevice) -> Unit,
    private val onDiscoveryStopped: () -> Unit,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, DiscoveredDevice>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            if (devices.containsKey(info.serviceName)) return
            val d = DiscoveredDevice(info.serviceName, host, info.port)
            devices[info.serviceName] = d
            mainHandler.post { onDeviceFound(d) }
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) { mainHandler.post { onDiscoveryStopped() } }
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceType == SERVICE_TYPE) {
                runCatching { nsdManager.resolveService(info, resolveListener) }
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) { devices.remove(info.serviceName) }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            mainHandler.post { onDiscoveryStopped() }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun startDiscovery() {
        devices.clear()
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
    }

    fun stopDiscovery() = runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }

    /** 发送一本书。onStage 阶段文案；onProgress 已发字节/总字节。返回接收方回报的导入结果 JSON（无则 null）。 */
    fun send(
        device: DiscoveredDevice,
        manifestJson: String,
        files: List<TransferFileEntry>,
        onStage: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): JSONObject {
        val base = "http://${device.host}:${device.port}"
        onStage("正在建立会话…")
        val plan = postSession(base, manifestJson)
        if (plan.optBoolean("existed", false)) {
            throw PeerHasBookException()
        }
        if (!plan.optBoolean("spaceOk", true)) {
            throw IllegalStateException("对端空间不足（需约 ${formatBytes(plan.optLong("requiredBytes", 0))}）")
        }
        val sessionId = plan.getString("sessionId")
        val need = plan.optJSONArray("need") ?: JSONArray()
        val needSet = (0 until need.length()).map { need.getString(it) }.toSet()
        val toSend = files.filter { it.relPath in needSet }
        val totalBytes = toSend.sumOf { it.size }
        var sent = 0L

        toSend.forEach { entry ->
            val offset = getOffset(base, sessionId, entry.relPath)
            if (offset >= entry.size) {
                sent += entry.size
                onProgress(sent, totalBytes)
                return@forEach
            }
            putFile(base, sessionId, entry, offset) { delta ->
                sent += delta
                onProgress(sent, totalBytes)
            }
        }

        onStage("正在校验并导入…")
        val result = postComplete(base, sessionId)
        onProgress(totalBytes, totalBytes)
        return result
    }

    private fun postSession(base: String, manifestJson: String): JSONObject {
        val body = manifestJson.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/session").post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("会话建立失败 HTTP ${resp.code}: ${text.take(200)}")
            return JSONObject(text)
        }
    }

    private fun getOffset(base: String, sessionId: String, relPath: String): Long {
        val req = Request.Builder().url("$base/files/$sessionId/$relPath/offset").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return 0L
            val j = JSONObject(resp.body?.string().orEmpty())
            return if (j.optBoolean("done", false)) Long.MAX_VALUE else j.optLong("offset", 0)
        }
    }

    private fun putFile(
        base: String,
        sessionId: String,
        entry: TransferFileEntry,
        offset: Long,
        onDelta: (Long) -> Unit,
    ) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = entry.size - offset
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(64 * 1024)
                entry.file.inputStream().use { ins ->
                    var skip = offset
                    while (skip > 0) {
                        val skipped = ins.skip(skip)
                        if (skipped <= 0) break
                        skip -= skipped
                    }
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        onDelta(n.toLong())
                    }
                }
            }
        }
        val req = Request.Builder()
            .url("$base/files/$sessionId/${entry.relPath}")
            .header("Range", "bytes=$offset-")
            .put(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("传输失败 HTTP ${resp.code}: ${resp.body?.string()?.take(200).orEmpty()}")
        }
    }

    private fun postComplete(base: String, sessionId: String): JSONObject {
        val body = ByteArray(0).toRequestBody(null)
        val req = Request.Builder().url("$base/complete/$sessionId").post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("导入失败 HTTP ${resp.code}: ${text.take(200)}")
            return JSONObject(text)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.2f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
