package com.mit.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** 全书翻译完成/失败的系统通知（channel「翻译任务」，用户可在系统设置里整体关闭）。 */
object TranslationNotifier {

    private const val CHANNEL_ID = "translation_status"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "翻译任务", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /**
     * 发送一条翻译结果通知；点击回到 App（MainActivity singleTask，复用现有任务栈）。
     * @param notifyId 用 book.id.hashCode()：同一本书重复通知覆盖旧条目，不同书并存。
     */
    fun notifyResult(context: Context, title: String, message: String, notifyId: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, notifyId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            // 用专用矢量小图标（沿用被控通知的教训：不能用 applicationInfo.icon，可能为 0 导致崩溃）
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).notify(notifyId, notification)
        }.onFailure {
            Log.w("TranslationNotifier", "翻译通知发送失败: ${it.message}")
        }
    }
}
