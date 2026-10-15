package com.mit.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.mit.reader.ReaderApp
import com.mit.reader.data.PairedDeviceState
import com.mit.reader.data.isDeviceOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * 轮询已配对设备的在线状态（4 秒一轮，随调用处离开组合自动停止）。
 * 用无副作用的 HTTP 探活：对方接收服务有响应（含 404）= 在线，不占用遥控器连接位。
 */
@Composable
fun rememberPairedDeviceStates(app: ReaderApp): Map<String, PairedDeviceState> {
    val states = remember { mutableStateMapOf<String, PairedDeviceState>() }
    LaunchedEffect(Unit) {
        while (true) {
            val records = app.pairedDevices
            if (records.isEmpty()) {
                states.clear()
            } else {
                val results = coroutineScope {
                    records.map { d ->
                        async(Dispatchers.IO) {
                            d.deviceId to isDeviceOnline(d.host, d.port)
                        }
                    }.awaitAll()
                }
                val liveIds = HashSet<String>()
                results.forEach { (id, online) ->
                    liveIds.add(id)
                    states[id] = if (online) PairedDeviceState.ONLINE else PairedDeviceState.OFFLINE
                }
                // 已忘记的设备清掉残留状态
                states.keys.toList().forEach { if (it !in liveIds) states.remove(it) }
            }
            delay(4_000)
        }
    }
    return states
}
