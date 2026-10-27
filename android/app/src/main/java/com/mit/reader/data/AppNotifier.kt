package com.mit.reader.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.mit.reader.DownloadStatus
import com.mit.reader.DownloadTask
import com.mit.reader.MainActivity
import com.mit.reader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** App 通知中心：翻译（进行中进度/完成失败）+ 下载（进度条/终态/操作按钮）。
 *  channel：
 *   - translation_status（DEFAULT）：翻译进行中（setOnlyAlertOnce 静默更新）与完成/失败
 *   - download_status（LOW，静默）：下载进行中/导入/暂停/断点重试等待（ongoing 常驻）
 *   - download_done（DEFAULT）：下载完成/已存在/最终失败
 *  遥控会话通知在 RemoteControlConsent（独立 channel），不经这里。
 *  所有通知的构建与封面解码都在单线程 IO 作用域串行执行：防主线程卡顿，
 *  也保证同一任务的「进度→终态」按调用顺序落，终态不会被迟到的进度更新覆盖。 */
object AppNotifier {

    private const val CHANNEL_TRANSLATION = "translation_status"
    private const val CHANNEL_DOWNLOAD_STATUS = "download_status"
    private const val CHANNEL_DOWNLOAD_DONE = "download_done"

    private const val ACCENT = 0xFF2563EB.toInt()

    /** 单线程串行作用域：通知更新按调用顺序执行。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** 进度类通知节流：同 key 间隔太近的更新直接跳过（终态调用不走节流并清 key）。 */
    private val lastNotifyAt = ConcurrentHashMap<String, Long>()

    /** 封面位图缓存（bookId/taskKey → 缩略图，避免每次通知都读盘解码）。 */
    private val coverCache = ConcurrentHashMap<String, Bitmap?>()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_TRANSLATION) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_TRANSLATION, "翻译任务", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (nm.getNotificationChannel(CHANNEL_DOWNLOAD_STATUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DOWNLOAD_STATUS, "下载进度", NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(CHANNEL_DOWNLOAD_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DOWNLOAD_DONE, "下载完成", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun contentIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notify(context: Context, id: Int, n: Notification) {
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(id, n) }
            .onFailure { Log.w("AppNotifier", "通知发送失败: ${it.message}") }
    }

    /** 封面大图标：解码后下采样（最长边 ≤512 再缩到 256），失败/缺封面返回 null。 */
    private fun coverBitmap(cacheKey: String, coverFile: File?): Bitmap? {
        if (coverFile == null || !coverFile.exists()) return null
        coverCache[cacheKey]?.let { return it }
        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(coverFile.absolutePath, bounds)
            var sample = 1
            while ((bounds.outWidth / (sample * 2)) >= 512 || (bounds.outHeight / (sample * 2)) >= 512) sample *= 2
            BitmapFactory.decodeFile(coverFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
        coverCache[cacheKey] = bmp
        return bmp
    }

    private fun action(context: Context, title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_download), title, pi).build()

    // ==================== 翻译 ====================

    private fun translationNotifyId(bookId: String) = bookId.hashCode()

    /** 翻译进行中：进度条（1.5s 节流静默更新），点击回 App。 */
    fun notifyTranslationProgress(context: Context, bookId: String, title: String, done: Int, total: Int, coverFile: File?) {
        if (!allowed(context)) return
        if (done < total) {
            val last = lastNotifyAt["tr:$bookId"] ?: 0L
            if (System.currentTimeMillis() - last < 1500) return
            lastNotifyAt["tr:$bookId"] = System.currentTimeMillis()
        }
        scope.launch {
            ensureChannels(context)
            val n = Notification.Builder(context, CHANNEL_TRANSLATION)
                .setSmallIcon(R.drawable.ic_stat_translate)
                .setColor(ACCENT)
                .setContentTitle("正在翻译《$title》")
                .setContentText("$done / $total 页")
                .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLargeIcon(coverBitmap(bookId, coverFile))
                .setContentIntent(contentIntent(context, translationNotifyId(bookId)))
                .build()
            notify(context, translationNotifyId(bookId), n)
        }
    }

    /** 翻译终态（完成/失败）：复用同一条通知收尾，可划掉。 */
    fun notifyTranslationResult(context: Context, bookId: String, title: String, ok: Boolean, message: String, coverFile: File?) {
        lastNotifyAt.remove("tr:$bookId")
        if (!allowed(context)) return
        scope.launch {
            ensureChannels(context)
            val n = Notification.Builder(context, CHANNEL_TRANSLATION)
                .setSmallIcon(R.drawable.ic_stat_translate)
                .setColor(ACCENT)
                .setContentTitle(if (ok) "《$title》翻译完成" else "《$title》翻译失败")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setLargeIcon(coverBitmap(bookId, coverFile))
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context, translationNotifyId(bookId)))
                .build()
            notify(context, translationNotifyId(bookId), n)
        }
    }

    /** 用户停止/取消翻译：撤掉进行中通知。 */
    fun cancelTranslation(context: Context, bookId: String) {
        lastNotifyAt.remove("tr:$bookId")
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(translationNotifyId(bookId)) }
    }

    // ==================== 下载 ====================

    private fun downloadNotifyId(taskId: String) = taskId.hashCode() xor 0x5f3759df.toInt()

    /** 通知按钮的 PendingIntent：requestCode 必须按 (任务, 动作) 唯一（filterEquals 不比 extras）。 */
    private fun actionPending(context: Context, action: String, taskId: String, ordinal: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, downloadNotifyId(taskId) + ordinal,
            Intent(context, DownloadActionReceiver::class.java)
                .setAction(action).putExtra(DownloadActionReceiver.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 下载进行中/导入/暂停/断点重试等待：静默 ongoing 通知（进度条 + 暂停/继续/取消按钮）。 */
    fun notifyDownloadProgress(context: Context, task: DownloadTask) {
        if (!allowed(context)) return
        val key = "dl:${task.id}"
        if (task.status == DownloadStatus.DOWNLOADING) {
            val last = lastNotifyAt[key] ?: 0L
            if (System.currentTimeMillis() - last < 1500) return
            lastNotifyAt[key] = System.currentTimeMillis()
        }
        scope.launch {
            ensureChannels(context)
            val title = task.title.ifBlank { task.name }
            val b = Notification.Builder(context, CHANNEL_DOWNLOAD_STATUS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setColor(ACCENT)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent(context, downloadNotifyId(task.id)))
            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    b.setContentTitle("正在下载《$title》")
                    if (task.total > 0) {
                        val pct = ((task.written * 100 / task.total).toInt()).coerceIn(0, 100)
                        b.setContentText("${mb(task.written)} / ${mb(task.total)} · $pct%")
                        b.setProgress(100, pct, false)
                    } else {
                        b.setContentText("已下载 ${mb(task.written)}")
                        b.setProgress(0, 0, true)
                    }
                    b.setOngoing(true)
                    b.addAction(action(context, "暂停", actionPending(context, DownloadActionReceiver.ACTION_PAUSE, task.id, 1)))
                }
                DownloadStatus.IMPORTING -> {
                    b.setContentTitle("正在导入《$title》")
                    b.setContentText("校验并导入书库…")
                    b.setProgress(0, 0, true)
                    b.setOngoing(true)
                }
                DownloadStatus.PAUSED -> {
                    b.setContentTitle("已暂停《$title》")
                    b.setContentText("${mb(task.written)}${if (task.total > 0) " / ${mb(task.total)}" else ""} · 断点已保存")
                    b.setOngoing(true)
                    b.addAction(action(context, "继续", actionPending(context, DownloadActionReceiver.ACTION_RESUME, task.id, 2)))
                }
                DownloadStatus.FAILED -> {
                    // 断点重试等待中（倒计时/原因在 task.message）
                    b.setContentTitle("下载中断《$title》")
                    b.setContentText(task.message.ifBlank { "等待自动重试" })
                    b.setOngoing(true)
                    b.addAction(action(context, "立即继续", actionPending(context, DownloadActionReceiver.ACTION_RESUME, task.id, 2)))
                }
                else -> return@launch   // 终态走 notifyDownloadResult / notifyDownloadFinalFail
            }
            b.addAction(action(context, "取消", actionPending(context, DownloadActionReceiver.ACTION_CANCEL, task.id, 3)))
            notify(context, downloadNotifyId(task.id), b.build())
        }
    }

    /** 下载终态（完成/已存在）：DEFAULT channel，可划掉。 */
    fun notifyDownloadResult(context: Context, task: DownloadTask, coverFile: File? = null) {
        lastNotifyAt.remove("dl:${task.id}")
        if (!allowed(context)) return
        scope.launch {
            ensureChannels(context)
            val title = task.title.ifBlank { task.name }
            val done = task.status == DownloadStatus.DONE
            val text = task.message.ifBlank { if (done) "已导入书库" else "本地已有该书，未重复导入" }
            val n = Notification.Builder(context, CHANNEL_DOWNLOAD_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setColor(ACCENT)
                .setContentTitle(if (done) "下载完成《$title》" else "《$title》已存在")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setLargeIcon(coverBitmap("dl:${task.id}", coverFile))
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context, downloadNotifyId(task.id)))
                .build()
            notify(context, downloadNotifyId(task.id), n)
        }
    }

    /** 自动重试耗尽后的最终失败：DEFAULT channel + 继续/取消按钮。 */
    fun notifyDownloadFinalFail(context: Context, task: DownloadTask) {
        lastNotifyAt.remove("dl:${task.id}")
        if (!allowed(context)) return
        scope.launch {
            ensureChannels(context)
            val title = task.title.ifBlank { task.name }
            val text = task.message.ifBlank { "多次自动重试仍失败，可手动继续" }
            val n = Notification.Builder(context, CHANNEL_DOWNLOAD_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setColor(ACCENT)
                .setContentTitle("下载失败《$title》")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOngoing(false)
                .addAction(action(context, "继续", actionPending(context, DownloadActionReceiver.ACTION_RESUME, task.id, 2)))
                .addAction(action(context, "取消", actionPending(context, DownloadActionReceiver.ACTION_CANCEL, task.id, 3)))
                .setContentIntent(contentIntent(context, downloadNotifyId(task.id)))
                .build()
            notify(context, downloadNotifyId(task.id), n)
        }
    }

    /** 任务取消/清除：撤掉对应通知。 */
    fun cancelDownload(context: Context, taskId: String) {
        lastNotifyAt.remove("dl:$taskId")
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(downloadNotifyId(taskId)) }
    }

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
