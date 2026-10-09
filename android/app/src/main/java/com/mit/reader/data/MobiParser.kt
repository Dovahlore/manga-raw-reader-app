package com.mit.reader.data

import java.io.File

/**
 * 极简 MOBI/AZW 解析：只针对"图页型"漫画（每页一张图，HTML 里用 <img recindex="N"> 指向图片记录）。
 * 做法：解析 PDB 头 → 解压 PalmDOC 文本记录拼出 HTML → 按文档顺序取 <img recindex> → 抽对应记录里的图。
 * 纯文字 MOBI 不覆盖（封面也取第一张图）。
 */
object MobiParser {

    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val SIGS = listOf(JPEG, PNG, GIF87, GIF89)

    fun extract(mobi: File, bookDir: File): ParsedBook {
        val pagesDir = File(bookDir, "pages").apply { mkdirs() }
        val data = mobi.readBytes()
        if (data.size < 78) throw IllegalStateException("不是有效的 MOBI 文件")

        val numRecords = u16(data, 76)
        if (numRecords <= 0 || 78 + numRecords * 8 > data.size) throw IllegalStateException("MOBI 记录表损坏")

        val offsets = IntArray(numRecords)
        for (i in 0 until numRecords) offsets[i] = u32(data, 78 + i * 8).toInt()

        fun record(i: Int): ByteArray {
            val start = offsets[i]
            val end = if (i + 1 < numRecords) offsets[i + 1] else data.size
            return data.copyOfRange(start, end.coerceAtMost(data.size))
        }

        val rec0 = record(0)
        val compression = u16(rec0, 0)
        val textRecordCount = u16(rec0, 8)

        // 标题：EXTH(503=更新标题 / 100=作者) > PDB 名 > 文件名
        val pdbName = String(data, 0, 32, Charsets.UTF_8).trimEnd('\u0000', ' ').trim()
        val exth = parseExth(rec0)
        val title = exth[503] ?: exth[100] ?: pdbName.ifBlank { null } ?: mobi.nameWithoutExtension

        // 文本记录（HTML），逐条解压后拼接
        var textRecords = textRecordCount
        if (textRecords <= 0 || textRecords > numRecords - 1) textRecords = numRecords - 1
        val html = StringBuilder()
        for (i in 1..textRecords) {
            val rec = record(i)
            val text = when (compression) {
                1 -> String(rec, Charsets.UTF_8)
                2 -> String(decompressPalmDoc(rec), Charsets.UTF_8)
                else -> throw IllegalStateException(
                    "不支持的 MOBI 压缩类型 $compression（只支持 1=无压缩 / 2=PalmDOC）"
                )
            }
            html.append(text)
        }

        val out = mutableListOf<File>()
        var idx = 0
        val seen = mutableSetOf<Int>()

        // 主路径：按文档顺序取 <img recindex="N">（N 为 1-based 记录号）
        val recindex = Regex(
            """<img\s[^>]*?recindex\s*=\s*["']?(\d+)["']?[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in recindex.findAll(html)) {
            val ri = (m.groupValues[1].toIntOrNull() ?: continue) - 1
            if (ri < 0 || ri >= numRecords || !seen.add(ri)) continue
            val img = extractImage(record(ri)) ?: continue
            idx++
            out += File(pagesDir, "page-${idx.toString().padStart(3, '0')}.${imageExt(img)}")
                .apply { writeBytes(img) }
        }

        // 兜底：没找到 recindex 时，按记录顺序扫描图片签名
        if (out.isEmpty()) {
            for (i in (textRecords + 1) until numRecords) {
                val img = extractImage(record(i)) ?: continue
                idx++
                out += File(pagesDir, "page-${idx.toString().padStart(3, '0')}.${imageExt(img)}")
                    .apply { writeBytes(img) }
            }
        }

        if (out.isEmpty()) throw IllegalStateException("这个 MOBI 里没找到图页（可能是纯文字书）")
        return ParsedBook(title, out)
    }

    // ---------------------------------------------------------------- EXTH

    private fun parseExth(rec0: ByteArray): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        if (rec0.size < 16 + 4) return out
        if (String(rec0, 16, 4, Charsets.US_ASCII) != "MOBI") return out
        if (rec0.size < 16 + 0x80 + 4) return out
        val headerLength = u32(rec0, 16 + 4).toInt()
        val exthFlags = u32(rec0, 16 + 0x80).toInt()
        if (exthFlags and 0x40 == 0) return out
        val start = 16 + headerLength
        if (start + 12 > rec0.size) return out
        if (String(rec0, start, 4, Charsets.US_ASCII) != "EXTH") return out
        val count = u32(rec0, start + 8).toInt()
        var pos = start + 12
        for (i in 0 until count) {
            if (pos + 8 > rec0.size) break
            val type = u32(rec0, pos).toInt()
            val len = u32(rec0, pos + 4).toInt()
            pos += 8
            if (len < 8 || pos + (len - 8) > rec0.size) break
            out[type] = String(rec0, pos, len - 8, Charsets.UTF_8).trim()
            pos += len - 8
        }
        return out
    }

    // ---------------------------------------------------------------- PalmDOC

    private fun decompressPalmDoc(data: ByteArray): ByteArray {
        val out = GrowableBytes(data.size * 3)
        var i = 0
        val n = data.size
        while (i < n) {
            var c = data[i].toInt() and 0xFF
            i++
            if (c == 0x00) {
                if (i >= n) break
                c = data[i].toInt() and 0xFF
                i++
                when {
                    c == 0x00 -> out.append(0)
                    c == 0x01 -> out.append(1)
                    c in 0x02..0x08 -> {
                        if (i + 1 >= n) break
                        val dist = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                        i += 2
                        out.copyFromEnd(dist, c)
                    }
                    c in 0x09..0x7F -> out.append(c)
                    c in 0x80..0xBF -> {
                        out.append(0x20)
                        out.append(c xor 0x80)
                    }
                    else -> out.append(c xor 0x80)
                }
            } else if (c in 0x01..0x08) {
                // 字面串：接下来 c 个字节原样输出
                out.appendLiteral(data, i, c)
                i += c
            } else {
                out.append(c)
            }
        }
        return out.toByteArray()
    }

    private class GrowableBytes(initialCapacity: Int) {
        private var buffer = ByteArray(initialCapacity.coerceAtLeast(64))
        private var length = 0

        fun append(value: Int) {
            ensureCapacity(1)
            buffer[length++] = value.toByte()
        }

        fun appendLiteral(source: ByteArray, offset: Int, count: Int) {
            val available = (source.size - offset).coerceAtLeast(0).coerceAtMost(count)
            if (available > 0) {
                ensureCapacity(available)
                System.arraycopy(source, offset, buffer, length, available)
                length += available
            }
            repeat(count - available) { append(0) }
        }

        fun copyFromEnd(distance: Int, count: Int) {
            if (distance <= 0) {
                repeat(count) { append(0) }
                return
            }
            repeat(count) {
                val source = length - distance
                append(if (source >= 0) buffer[source].toInt() and 0xFF else 0)
            }
        }

        fun toByteArray(): ByteArray = buffer.copyOf(length)

        private fun ensureCapacity(extra: Int) {
            val required = length + extra
            if (required <= buffer.size) return
            var newSize = buffer.size
            while (newSize < required) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }
    }

    // ---------------------------------------------------------------- 图片

    private fun extractImage(rec: ByteArray): ByteArray? {
        if (rec.size < 8) return null
        for (sig in SIGS) if (startsWith(rec, sig, 0)) return rec
        var best = -1
        for (sig in SIGS) {
            val p = indexOf(rec, sig)
            if (p >= 0 && (best < 0 || p < best)) best = p
        }
        return if (best >= 0) rec.copyOfRange(best, rec.size) else null
    }

    private fun imageExt(img: ByteArray): String = when {
        startsWith(img, JPEG, 0) -> "jpg"
        startsWith(img, PNG, 0) -> "png"
        startsWith(img, GIF87, 0) || startsWith(img, GIF89, 0) -> "gif"
        else -> "jpg"
    }

    private fun startsWith(b: ByteArray, sig: ByteArray, off: Int): Boolean {
        if (off + sig.size > b.size) return false
        for (k in sig.indices) if (b[off + k] != sig[k]) return false
        return true
    }

    private fun indexOf(b: ByteArray, sig: ByteArray): Int {
        if (sig.isEmpty() || sig.size > b.size) return -1
        outer@ for (i in 0..b.size - sig.size) {
            for (k in sig.indices) if (b[i + k] != sig[k]) continue@outer
            return i
        }
        return -1
    }

    // ---------------------------------------------------------------- 字节工具

    private fun u16(b: ByteArray, off: Int): Int {
        if (off + 2 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun u32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return 0
        return ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
    }
}
