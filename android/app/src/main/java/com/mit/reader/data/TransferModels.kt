package com.mit.reader.data

import java.io.File

/** 设备对传：NSD 服务类型（同一局域网内发现「接收方」用）。 */
const val SERVICE_TYPE = "_mitreader._tcp."

/** NSD 广播/发现时携带本机设备 ID 的键，发现方用它过滤掉自己。 */
const val NSD_KEY_DEVICE_ID = "devid"

/** 一个待传文件（relPath 形如 pages/page-0001.jpg / translated/003.webp）。 */
data class TransferFileEntry(
    val relPath: String,
    val file: File,
    val size: Long,
    val sha256: String,
)

/** 一本书的完整传输包：manifest JSON + 文件清单（manifest 本身走 /session 请求体，不单独传）。 */
data class TransferPackage(
    val manifestJson: String,
    val files: List<TransferFileEntry>,
)

/** 接收方导入结果（给发送方回报 / 本地 Toast 用）。 */
data class TransferImportResult(
    val book: Book,
    val duplicate: Boolean,
    val addedPages: Int,
    val skippedPages: Int,
)

/** 接收方发现到的可接收设备。 */
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val devid: String? = null,
)

/** 一条配对凭据：direction=in 表示对方凭它遥控本机；out 表示本机用它遥控对方。 */
data class PairedCredential(
    val token: String,
    val direction: String,
    val issuedAt: Long,
)

/** 已配对设备：配对一次后，遥控和局域网传书共用这个授权。
 *  修复（02-中10/U2）：凭据改多凭据列表——双向/重复配对各自追加，不再互相覆盖；
 *  token 字段保留为「最新凭据」便捷访问（=credentials 中 issuedAt 最新者）。 */
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val lastConnectedAt: Long,
    val credentials: List<PairedCredential> = emptyList(),
)

data class RemotePairResult(
    val token: String,
    val hostDeviceId: String,
    val hostName: String,
    val endpointMissing: Boolean = false,
)

/** 对端已有这本书（按内容 hash），分享被拦截。 */
class PeerHasBookException : Exception()
