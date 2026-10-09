package com.mit.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mit.reader.ReaderApp

sealed class Route(val path: String) {
    data object Library : Route("library")
    data object Reader : Route("reader/{bookId}") {
        fun of(bookId: String) = "reader/$bookId"
    }
}

private enum class FullScreenOverlay { Settings, Kmoe }

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as ReaderApp
    var overlay by rememberSaveable { mutableStateOf<FullScreenOverlay?>(null) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }

    // 冷启动查到新版本后弹更新对话框（手动查版本走设置页，不重复弹）
    LaunchedEffect(app.updateInfo) {
        if (app.updateInfo != null) showUpdateDialog = true
    }

    // 外部「打开」epub/mobi 导入完成后，自动进阅读器
    LaunchedEffect(app.pendingOpenBookId) {
        val id = app.pendingOpenBookId ?: return@LaunchedEffect
        overlay = null
        nav.navigate(Route.Reader.of(id)) { launchSingleTop = true }
        app.consumePendingOpen()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Route.Library.path) {
            composable(Route.Library.path) {
                LibraryScreen(
                    onOpen = { id ->
                        overlay = null
                        nav.navigate(Route.Reader.of(id))
                    },
                    onSettings = { overlay = FullScreenOverlay.Settings },
                    onKmoe = { overlay = FullScreenOverlay.Kmoe },
                )
            }
            composable(Route.Reader.path) { backStack ->
                val bookId = backStack.arguments?.getString("bookId") ?: return@composable
                ReaderScreen(bookId = bookId, onBack = { nav.popBackStack() })
            }
        }
        when (overlay) {
            FullScreenOverlay.Settings -> {
                BackHandler { overlay = null }
                SettingsScreen(onBack = { overlay = null })
            }
            FullScreenOverlay.Kmoe -> {
                BackHandler { overlay = null }
                KmoeScreen(onBack = { overlay = null })
            }
            null -> Unit
        }
    }

    // 更新对话框：冷启动自动检查到新版本时弹出
    app.updateInfo?.let { info ->
        if (showUpdateDialog) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("发现新版本 ${info.versionName}") },
                text = { Text(if (info.changelog.isBlank()) "有可用更新，是否立即下载安装？" else info.changelog) },
                confirmButton = {
                    TextButton(onClick = { showUpdateDialog = false; app.startUpdateDownload() }) { Text("立即更新") }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) { Text("稍后") }
                },
            )
        }
    }
}
