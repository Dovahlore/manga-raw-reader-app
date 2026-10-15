package com.mit.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mit.reader.ui.AppNav
import com.mit.reader.ui.RemoteControlConsentHost
import com.mit.reader.ui.theme.MangaReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenIntent(intent)   // 冷启动：可能是从别的 App「打开」epub/mobi 进来的
        setContent {
            MangaReaderTheme {
                // 全局遥控确认弹窗 + 被控保活 + 「正在被遥控」通知（覆盖所有页面）
                RemoteControlConsentHost(application as ReaderApp)
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)   // 已在运行时再次「打开」/「分享」文件
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as ReaderApp
        if (app.readerScreenActive && app.remoteControllerConnection == null) {
            val action = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> com.mit.reader.data.RemotePageAction.PREVIOUS
                KeyEvent.KEYCODE_VOLUME_DOWN -> com.mit.reader.data.RemotePageAction.NEXT
                else -> null
            }
            if (action != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    app.pageWithVolume(action)
                }
                return true
            }
        }
        if (
            app.remoteControllerScreenActive &&
            app.remoteVolumeKeysEnabled &&
            app.remoteControllerConnection != null
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        app.sendRemoteCommand(com.mit.reader.data.RemotePageAction.PREVIOUS)
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        app.sendRemoteCommand(com.mit.reader.data.RemotePageAction.NEXT)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 处理外部「打开方式」/「分享」进来的 epub、mobi：交给 ReaderApp 导入并跳阅读器。 */
    private fun handleOpenIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return
        (application as ReaderApp).openDocument(uri)
    }
}
