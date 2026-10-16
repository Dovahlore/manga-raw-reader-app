package com.mit.reader.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 已配对设备的在线状态（控制端视角，设备页/遥控器页轮询刷新）。 */
enum class PairedDeviceState(val label: String) {
    OFFLINE("离线"),
    ONLINE("在线"),
}

private val probeClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(1_500, TimeUnit.MILLISECONDS)
        .readTimeout(1_500, TimeUnit.MILLISECONDS)
        .build()
}

private val verifyClient by lazy { RemoteControlClient() }

/**
 * 已配对设备在线探测：对方接收服务（HTTP）有任意响应（含 404）即在线，网络不通为离线。
 * 只发一个无凭据的 GET，不占用对方的遥控器连接位。
 */
fun isDeviceOnline(host: String, port: Int): Boolean {
    val request = Request.Builder()
        .url("http://$host:$port/")
        .get()
        .build()
    return try {
        probeClient.newCall(request).execute().use { true }
    } catch (e: Exception) {
        false
    }
}

/**
 * 把「附近发现到的设备」对齐回已配对记录：记录以配对时保存的对方设备标识（deviceId）为主体，
 * 换 IP 也能认出是同一台设备。仅当记录里保存的地址已失联、而附近出现陌生地址时，才用配对时
 * 拿到的授权向新地址发一次遥控心跳验证——只有原来那台设备保存着这份授权，验证通过即认定
 * 同一设备，返回更新了新地址的记录（由调用方回写）；陌生设备不持有该授权，会被直接拒绝。
 * 注意：本函数做网络 IO，请在 IO 线程调用。
 */
fun reconcileDiscoveredDevices(
    paired: List<PairedDevice>,
    discovered: List<DiscoveredDevice>,
): List<PairedDevice> {
    if (paired.isEmpty() || discovered.isEmpty()) return emptyList()
    val known = paired.map { "${it.host}:${it.port}" }.toHashSet()
    val candidates = discovered.filter { "${it.host}:${it.port}" !in known }
    if (candidates.isEmpty()) return emptyList()
    val updates = mutableListOf<PairedDevice>()
    paired.forEach { record ->
        // 记录里的地址仍在线：无需处理
        if (isDeviceOnline(record.host, record.port)) return@forEach
        candidates.forEach candidate@{ candidate ->
            // 修复（02-高3）：devid 不匹配的候选一律不发凭据（fail-closed），防冒名设备收集配对凭据
            if (candidate.devid == null || candidate.devid != record.deviceId) return@candidate
            // 修复（03 区启发式替代）：探活/认回心跳声明 probe 意图——被控端不再把它当遥控请求，
            // 也不会因为探活弹「遥控请求」确认窗（替代旧的 8 秒存活启发式判断）
            val status = runCatching { verifyClient.heartbeat(candidate, record.token, intent = "probe") }
                .getOrNull() ?: return@candidate
            if (status.ok) {
                updates += record.copy(host = candidate.host, port = candidate.port)
                return@forEach
            }
        }
    }
    return updates
}
