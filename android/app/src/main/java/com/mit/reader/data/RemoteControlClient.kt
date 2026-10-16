package com.mit.reader.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 传输层失败（连不上/读超时）——区别于服务端协议拒绝；自动重连只认「连接失败」前缀（P5）。 */
class RemoteTransportException(message: String) : java.io.IOException(message)

class RemoteControlClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    fun pair(
        device: DiscoveredDevice,
        code: String,
        controllerName: String,
        controllerId: String,
        controllerEndpoint: String?,
    ): RemotePairResult {
        val body = JSONObject()
            .put("code", code.trim())
            .put("controllerName", controllerName)
            .put("controllerId", controllerId)
            .put("controllerEndpoint", controllerEndpoint ?: "")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://${device.host}:${device.port}/remote/pair")
            .post(body)
            .build()
        val resp = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            // 修复（03-中6/P5）：传输层异常与协议拒绝分离——传输异常才带「连接失败」前缀
            throw RemoteTransportException("连接失败：${e.message ?: "网络错误"}")
        }
        resp.use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("配对失败：${text.ifBlank { "HTTP ${response.code}" }}")
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) throw IllegalStateException("配对码错误或阅读端已关闭遥控")
            return RemotePairResult(
                token = json.getString("token"),
                hostDeviceId = json.optString("hostDeviceId", "unknown"),
                hostName = json.optString("hostName", device.name),
                // 修复（02-中9 断链）：解析宿主上报的 endpointMissing——此前服务端不序列化、
                // 客户端也不解析，「对方未提供本地地址」提示是死代码
                endpointMissing = json.optBoolean("endpointMissing", false),
            )
        }
    }

    fun heartbeat(device: DiscoveredDevice, token: String, intent: String = "control"): RemoteHostStatus {
        val json = post(device, "/remote/heartbeat") {
            put("token", token)
            // 修复（03 区启发式替代）：声明心跳意图——control=遥控会话；探活/认回复用方传 probe
            put("intent", intent)
        }
        return RemoteHostStatus(
            ok = json.optBoolean("ok"),
            reason = json.optString("reason").takeIf { it.isNotBlank() },
            readerActive = json.optBoolean("readerActive"),
            reader = json.optJSONObject("reader")?.toReaderSnapshot(),
            // 修复（02-中5 断链）：解析 revoked——服务端已序列化但客户端不读，
            // 导致真吊销后控制端永不清理死记录、revoked 链路整体死代码
            revoked = json.optBoolean("revoked", false),
        )
    }

    fun sendCommand(
        device: DiscoveredDevice,
        token: String,
        action: RemotePageAction,
        requestId: String = UUID.randomUUID().toString(),
    ): RemoteCommandAck {
        val json = post(device, "/remote/command") {
            put("token", token)
            put("action", action.wireName)
            put("requestId", requestId)
        }
        return RemoteCommandAck(
            ok = json.optBoolean("ok"),
            accepted = json.optBoolean("accepted"),
            reason = json.optString("reason").takeIf { it.isNotBlank() },
            reader = json.optJSONObject("reader")?.toReaderSnapshot(),
        )
    }

    private fun post(
        device: DiscoveredDevice,
        path: String,
        buildBody: JSONObject.() -> Unit,
    ): JSONObject {
        val body = JSONObject().apply(buildBody).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://${device.host}:${device.port}$path")
            .post(body)
            .build()
        val resp = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            // 修复（03-中6/P5）：传输层异常与协议拒绝分离，服务端 4xx 不再被误判为网络失联
            throw RemoteTransportException("连接失败：${e.message ?: "网络错误"}")
        }
        resp.use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(text.ifBlank { "HTTP ${response.code}" })
            return JSONObject(text)
        }
    }

    private fun JSONObject.toReaderSnapshot(): RemoteReaderSnapshot = RemoteReaderSnapshot(
        bookId = optString("bookId"),
        title = optString("title"),
        page = optInt("page"),
        pageCount = optInt("pageCount"),
    )
}
