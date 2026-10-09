package com.mit.reader.data

import java.io.File

/** 设备对传：NSD 服务类型（同一局域网内发现「接收方」用）。 */
const val SERVICE_TYPE = "_mitreader._tcp"

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
)

/** 对端已有这本书（按内容 hash），分享被拦截。 */
class PeerHasBookException : Exception()
