package com.mit.reader.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
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
    private val ownDeviceId: String,
    private val onDeviceFound: (DiscoveredDevice) -> Unit,
    private val onDiscoveryStopped: () -> Unit,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifiManager.createMulticastLock("mit:discovery").apply { setReferenceCounted(false) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = java.util.concurrent.ConcurrentHashMap<String, DiscoveredDevice>()
    // 修复（05-中5）：断点续传会话缓存——键 host:port，值 (sessionId, 记录时间戳)
    private val lastSessionByDevice = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // 修复（02-中3/P3）：每次 resolve 独立 listener——NsdManager 同一时刻只允许一个
    // resolve 进行中，共享实例的并发 resolve 会冲突丢设备
    private fun resolveDevice(info: NsdServiceInfo) {
        runCatching {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    // 发现的是自己：服务方在 TXT 记录里广播了设备 ID
                    if (info.attributes[NSD_KEY_DEVICE_ID]?.toString(Charsets.UTF_8) == ownDeviceId) return
                    if (devices.containsKey(info.serviceName)) return
                    val d = DiscoveredDevice(info.serviceName, host, info.port, info.attributes[NSD_KEY_DEVICE_ID]?.toString(Charsets.UTF_8))
                    devices[info.serviceName] = d
                    mainHandler.post { onDeviceFound(d) }
                }
            })
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) { mainHandler.post { onDiscoveryStopped() } }
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceType == SERVICE_TYPE) {
                resolveDevice(info)
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) { devices.remove(info.serviceName) }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            runCatching { if (multicastLock.isHeld) multicastLock.release() }
            mainHandler.post { onDiscoveryStopped() }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    fun startDiscovery() {
        devices.clear()
        runCatching { if (!multicastLock.isHeld) multicastLock.acquire() }
        // 修复（02-中3/P3）：上轮 stop 未完成会让 discoverServices 抛错——退避 200ms 重试一次，
        // 仍失败才释放组播锁并通知 UI，避免「发现假死但界面还在转圈」
        val started = runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.isSuccess
        if (!started) {
            mainHandler.postDelayed({
                val ok = runCatching {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                }.isSuccess
                if (!ok) {
                    runCatching { if (multicastLock.isHeld) multicastLock.release() }
                    mainHandler.post { onDiscoveryStopped() }
                }
            }, 200)
        }
    }

    fun stopDiscovery() {
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        runCatching { if (multicastLock.isHeld) multicastLock.release() }
    }

    /** 发送一本书。onStage 阶段文案；onProgress 已发字节/总字节。返回接收方回报的导入结果 JSON（无则 null）。 */
    fun send(
        device: DiscoveredDevice,
        token: String,
        manifestJson: String,
        files: List<TransferFileEntry>,
        onStage: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
        cancelled: () -> Boolean = { false },
    ): JSONObject {
        val base = "http://${device.host}:${device.port}"
        val deviceKey = "${device.host}:${device.port}"
        // 修复（02-低5 瑕疵）：顺带清掉过期的复用表条目，防长期进程缓慢累积
        val sessionCutoff = System.currentTimeMillis() - 30L * 60 * 1000
        lastSessionByDevice.entries.removeIf { it.value.second < sessionCutoff }
        onStage("正在建立会话…")

        // 修复（05-中5）：30 分钟内对同一 host:port 有未完成会话时，先拿旧 sessionId 对首个文件
        // 做 GET offset 探测；探测成功直接续传，探测失败（400/未知会话）才回退新建 /session
        var reusedSessionId: String? = null
        val cached = lastSessionByDevice[deviceKey]
        val firstRelPath = files.firstOrNull()?.relPath
        if (cached != null && firstRelPath != null) {
            if (System.currentTimeMillis() - cached.second <= 30L * 60 * 1000 &&
                probeSessionAlive(base, token, cached.first, firstRelPath)
            ) {
                reusedSessionId = cached.first
                onStage("发现上次未完成的传输，正在续传…")
            } else {
                lastSessionByDevice.remove(deviceKey, cached)
            }
        }

        val sessionId: String
        val toSend: List<TransferFileEntry>
        if (reusedSessionId != null) {
            // 复用会话：清单已在接收端校验过，全量探测、逐文件跳过已完成部分
            sessionId = reusedSessionId
            toSend = files
        } else {
            val plan = postSession(base, token, manifestJson)
            if (plan.optBoolean("existed", false)) {
                throw PeerHasBookException()
            }
            if (!plan.optBoolean("spaceOk", true)) {
                throw IllegalStateException("对端空间不足（需约 ${formatBytes(plan.optLong("requiredBytes", 0))}）")
            }
            val newSessionId = plan.getString("sessionId")
            val need = plan.optJSONArray("need") ?: JSONArray()
            val needSet = (0 until need.length()).map { need.getString(it) }.toSet()
            // 修复（05-中5）：新会话成功建立后写入复用表
            lastSessionByDevice[deviceKey] = Pair(newSessionId, System.currentTimeMillis())
            sessionId = newSessionId
            toSend = files.filter { it.relPath in needSet }
        }
        val totalBytes = toSend.sumOf { it.size }
        var sent = 0L

        try {
            toSend.forEach { entry ->
                if (cancelled()) throw java.util.concurrent.CancellationException("发送已取消")
                val offset = getOffset(base, token, sessionId, entry.relPath)
                if (offset >= entry.size) {
                    sent += entry.size
                    onProgress(sent, totalBytes)
                    return@forEach
                }
                putFile(base, token, sessionId, entry, offset, onDelta = { delta ->
                    sent += delta
                    onProgress(sent, totalBytes)
                }, cancelled = cancelled)
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reusedSessionId == null) throw e
            // 修复（05-中5）配套：复用会话与本次清单不匹配（对端已清理/文件表不符）时
            // 作废缓存，回退新建会话整轮重试一次
            lastSessionByDevice.remove(deviceKey)
            onStage("续传会话已失效，正在重新建立会话…")
            return send(device, token, manifestJson, files, onStage, onProgress, cancelled)
        }

        onStage("正在校验并导入…")
        val result = postComplete(base, token, sessionId)
        onProgress(totalBytes, totalBytes)
        return result
    }

    /** 修复（05-中5）：探测旧会话是否仍有效——GET offset 命中即视为可续传；400/未知会话返回 false。 */
    private fun probeSessionAlive(base: String, token: String, sessionId: String, relPath: String): Boolean {
        val req = Request.Builder().url("$base/files/$sessionId/$relPath/offset")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    private fun postSession(base: String, token: String, manifestJson: String): JSONObject {
        val body = manifestJson.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/session")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("会话建立失败 HTTP ${resp.code}: ${text.take(200)}")
            return JSONObject(text)
        }
    }

    private fun getOffset(base: String, token: String, sessionId: String, relPath: String): Long {
        val req = Request.Builder().url("$base/files/$sessionId/$relPath/offset")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return 0L
            val j = JSONObject(resp.body?.string().orEmpty())
            return if (j.optBoolean("done", false)) Long.MAX_VALUE else j.optLong("offset", 0)
        }
    }

    private fun putFile(
        base: String,
        token: String,
        sessionId: String,
        entry: TransferFileEntry,
        offset: Long,
        onDelta: (Long) -> Unit,
        cancelled: () -> Boolean = { false },
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
                        if (cancelled()) throw java.util.concurrent.CancellationException("发送已取消")
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
            .header("Authorization", "Bearer $token")
            .header("Range", "bytes=$offset-")
            .put(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("传输失败 HTTP ${resp.code}: ${resp.body?.string()?.take(200).orEmpty()}")
        }
    }

    private fun postComplete(base: String, token: String, sessionId: String): JSONObject {
        val body = ByteArray(0).toRequestBody(null)
        val req = Request.Builder().url("$base/complete/$sessionId")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
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
