package com.mit.reader.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

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
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("配对失败：${text.ifBlank { "HTTP ${response.code}" }}")
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) throw IllegalStateException("配对码错误或阅读端已关闭遥控")
            return RemotePairResult(
                token = json.getString("token"),
                hostDeviceId = json.optString("hostDeviceId", "unknown"),
                hostName = json.optString("hostName", device.name),
            )
        }
    }

    fun heartbeat(device: DiscoveredDevice, token: String): RemoteHostStatus {
        val json = post(device, "/remote/heartbeat") {
            put("token", token)
        }
        return RemoteHostStatus(
            ok = json.optBoolean("ok"),
            reason = json.optString("reason").takeIf { it.isNotBlank() },
            readerActive = json.optBoolean("readerActive"),
            reader = json.optJSONObject("reader")?.toReaderSnapshot(),
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
        client.newCall(request).execute().use { response ->
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
