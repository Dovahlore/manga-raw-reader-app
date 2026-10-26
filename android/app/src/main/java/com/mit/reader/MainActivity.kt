package com.mit.reader

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.mit.reader.ui.AppNav
import com.mit.reader.ui.RemoteControlConsentHost
import com.mit.reader.ui.theme.MangaReaderTheme

class MainActivity : ComponentActivity() {

    // 通知权限（Android 13+）：首启申请一次——翻译完成/失败通知需要；拒绝后不再骚扰，
    // 系统设置随时可开；接受遥控的确认流程里有独立的申请。
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
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

    // ComponentActivity.dispatchKeyEvent 带 @RestrictTo(LIBRARY_GROUP_PREFIX)，override 它必然触发 lint
    // RestrictedApi；保留 dispatchKeyEvent 而非改 onKeyDown：音量键 down/up 都要消费，onKeyDown 会漏拦
    // ACTION_UP 导致系统音量条弹出，语义不同。
    @SuppressLint("RestrictedApi")
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

    /** Android 13+ 首启申请一次通知权限（翻译完成通知用；拒绝过就不再弹，可去系统设置开）。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("mit_ui", MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) return
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
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
