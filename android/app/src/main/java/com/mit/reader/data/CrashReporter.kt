package com.mit.reader.data

import android.content.Context
import android.os.Build
import com.mit.reader.ReaderApp
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Crash 采集与上报：
 * - 崩溃时把技术栈信息（版本/机型/系统/线程/栈）写 JSON 到 filesDir/crashes/（崩溃路径上不做网络 IO）；
 * - 下次启动在 Application 级协程里异步上传 POST /v1/crashes，成功即删，连不上保持时间顺序下次再试；
 * - 同签名 24 小时只报一次（防崩溃风暴），本地最多保留 [MAX_FILES] 份；
 * - 未配置服务器地址时静默跳过（文件保留，等配置好后再报）。
 * 隐私：只含技术信息，不含书名/阅读内容。
 */
object CrashReporter {

    private const val MAX_FILES = 10
    private const val DEDUP_WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val MAX_STACK_CHARS = 32 * 1024
    private const val PREFS = "mit_crash"

    /** 安装全局未捕获异常处理器；必须链回系统默认处理器，否则崩溃流程（退出/ANR 对话框）被吞。 */
    fun install(app: ReaderApp) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { save(app, thread, e) }   // 崩溃路径上任何异常都不允许外抛
            previous?.uncaughtException(thread, e)
        }
    }

    private fun save(context: Context, thread: Thread, e: Throwable) {
        val now = System.currentTimeMillis()
        val sig = signature(e)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - prefs.getLong("sig_$sig", 0L) < DEDUP_WINDOW_MS) return
        prefs.edit().putLong("sig_$sig", now).apply()

        val version = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            JSONObject().put("version_code", code).put("version_name", pi.versionName ?: "unknown")
        }.getOrElse { JSONObject().put("version_code", 0L).put("version_name", "unknown") }

        val payload = JSONObject().apply {
            put("version_code", version.getLong("version_code"))
            put("version_name", version.getString("version_name"))
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("android_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("thread", thread.name)
            put("signature", sig)
            put("stack", stackOf(e))
            put("crashed_at", now)
        }
        val dir = File(context.filesDir, "crashes").apply { mkdirs() }
        File(dir, "crash_$now.json").writeText(payload.toString())
        trim(dir)
    }

    /** 启动后调用（IO 线程）：把本地暂存的崩溃逐个上报，2xx 即删；未配置服务器则跳过。 */
    suspend fun uploadPending(app: ReaderApp) {
        if (ServerConfig.baseUrl.isBlank()) return
        val dir = File(app.filesDir, "crashes")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
        for (f in files) {
            val ok = runCatching { app.api.reportCrash(f.readText()) }.getOrDefault(false)
            if (ok) f.delete() else break
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
        if (files.size > MAX_FILES) {
            files.take(files.size - MAX_FILES).forEach { it.delete() }
        }
    }

    /** 异常签名：异常类名 + 顶层栈帧，sha-256 截 16 hex（同因崩溃 24h 去重用）。 */
    private fun signature(e: Throwable): String {
        val top = e.stackTrace.firstOrNull()
            ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "noframe"
        val raw = "${e.javaClass.name}|$top"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 完整调用栈（含 cause 链，最多 8 层），截断到 [MAX_STACK_CHARS]。 */
    private fun stackOf(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            sb.append(if (depth == 0) "" else "Caused by: ").append(t.toString()).append('\n')
            for (f in t.stackTrace) sb.append("\tat ").append(f).append('\n')
            t = t.cause
            depth++
        }
        return sb.toString().take(MAX_STACK_CHARS)
    }
}
