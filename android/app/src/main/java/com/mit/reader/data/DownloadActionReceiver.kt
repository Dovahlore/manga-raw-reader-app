package com.mit.reader.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mit.reader.ReaderApp

/** 下载通知上的操作按钮（暂停/继续/取消）：直接改 ReaderApp 的任务状态，
 *  通知刷新由状态流转挂点完成（不需要在 receiver 里手动重建通知）。
 *  exported=false：仅本 App 内的 PendingIntent 可触发。 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val app = context.applicationContext as ReaderApp ?: return
        when (intent.action) {
            ACTION_PAUSE -> app.pauseDownload(taskId)
            ACTION_RESUME -> app.resumeDownload(taskId)
            ACTION_CANCEL -> app.cancelDownload(taskId)
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.mit.reader.download.PAUSE"
        const val ACTION_RESUME = "com.mit.reader.download.RESUME"
        const val ACTION_CANCEL = "com.mit.reader.download.CANCEL"
        const val EXTRA_TASK_ID = "task_id"
    }
}
