package com.mit.reader.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * 单页导出：把阅读器当前页（原图/译文）写入公共 Download/MITReader/，相册与文件管理器可见。
 * - API 29+：MediaStore.Downloads，IS_PENDING 两段式写入，无需任何存储权限；
 * - API 26-28：公共 Download 目录直写 + MediaScanner 扫描入库（需要 WRITE_EXTERNAL_STORAGE
 *   运行时权限，Manifest 按 maxSdkVersion=28 声明，由调用方申请）。
 */
object PageExporter {

    /** 公共 Download 下的子目录名。 */
    const val SUB_DIR = "MITReader"

    /**
     * 导出一个页面文件，返回最终显示名（供 Toast 展示）。
     * 失败抛 [IOException]（message 可直接展示给用户）。
     *
     * @param src        源文件（原图或译文 WebP）
     * @param bookTitle  书名（用于文件名，会被 sanitize）
     * @param pageIndex  0 起页索引（展示时 +1）
     * @param translated true=译文页，false=原图（用于文件名标注）
     */
    fun exportPage(context: Context, src: File, bookTitle: String, pageIndex: Int, translated: Boolean): String {
        if (!src.exists() || src.length() == 0L) throw IOException("源文件不存在")
        val ext = src.extension.ifBlank { "jpg" }.lowercase()
        val mime = mimeOf(ext)
        val kind = if (translated) "译文" else "原图"
        val displayName = "${sanitize(bookTitle)}-第${pageIndex + 1}页-$kind.$ext"

        if (Build.VERSION.SDK_INT >= 29) {
            return exportViaMediaStore(context, src, mime, displayName)
        }
        return exportLegacy(src, mime, displayName)
    }

    /** API 29+：MediaStore.Downloads 两段式写入（pending → 写入 → 清 pending）。 */
    @RequiresApi(29)
    private fun exportViaMediaStore(context: Context, src: File, mime: String, displayName: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("系统拒绝了写入 Download 目录")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法打开输出流")
        } catch (e: Exception) {
            // 写一半失败要清掉 pending 记录，不留给相册一条空壳
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException(e.message ?: "写入失败", e)
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return displayName
    }

    /** API 26-28：直写公共 Download/MITReader/，再交 MediaScanner 入库（相册才能看到）。 */
    private fun exportLegacy(src: File, mime: String, displayName: String): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB_DIR)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建 Download/$SUB_DIR 目录")
        val out = uniqueLegacyName(dir, displayName)
        try {
            src.copyTo(out, overwrite = false)
        } catch (e: Exception) {
            out.delete()
            throw if (e is IOException) e else IOException(e.message ?: "写入失败", e)
        }
        MediaScannerConnection.scanFile(null, arrayOf(out.absolutePath), arrayOf(mime), null)
        return out.name
    }

    /** API 26-28 无重名自动处理，手动加序号；(MediaStore 在 29+ 会自行加 " (1)"，无需预查重）。 */
    private fun uniqueLegacyName(dir: File, displayName: String): File {
        var candidate = File(dir, displayName)
        if (!candidate.exists()) return candidate
        val dot = displayName.lastIndexOf('.')
        val stem = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem (${i++})$ext")
        }
        return candidate
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    /** 文件名安全化：替换非法字符、截断长度（保留中文名与书名号）。 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
            .take(60)
            .ifBlank { "book" }
}
