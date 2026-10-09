package com.mit.reader.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mit.reader.DownloadStatus
import com.mit.reader.ReaderApp
import com.mit.reader.data.ServerConfig
import kotlinx.coroutines.launch

/** Kmoe 默认地址。 */
const val KMOE_URL = "https://www.koz.moe/"

/** 桌面版 User-Agent：让站点返回电脑端页面（手机版功能少）。 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** URL 里的文件名常是 %XX 转义（如 %5BMobi%5D%5BKmoe%5D），解码成可读名并去掉路径非法字符。 */
private fun decodeFileName(raw: String): String {
    val decoded = runCatching {
        // 路径段里的 '+' 是字面量，先保护再解码（URLDecoder 默认会把 + 当空格）
        java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(raw)
    return decoded.replace(Regex("""[/\\\u0000]"""), "_").trim().ifBlank { raw }
}

/** 待确认下载（先弹确认，确认后转入后台下载队列）。 */
private data class PendingDownload(
    val url: String,
    val name: String,
    val title: String,
    val cookie: String?,
    val referer: String?,
    val userAgent: String?,
)

/** 内嵌 WebView 快速访问站点：只允许下载 epub/mobi；先确认再后台下载（进度页看进度）；退出重进恢复上次页面。 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KmoeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    // 用普通数组持有 WebView 引用，避免写回 Compose state 触发重组
    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    fun navigateBack() {
        val wv = webViewRef[0]
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    // 系统返回键/手势：网页有历史就退一页，退到根页才回书库
    BackHandler(onBack = { navigateBack() })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kmoe") },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    // 标题行最右缘：正在下载时显示转圈 + 数量（进度详情在「进度」页）
                    val activeCount = app.downloadTasks.count {
                        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.IMPORTING
                    }
                    if (activeCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("$activeCount", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
            )
        },
    ) { pad ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef[0] = this

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // 桌面模式：用桌面 UA 让站点返回电脑端页面
                    settings.userAgentString = DESKTOP_UA
                    // 允许双指捏合缩放，隐藏系统 +/- 按钮
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    CookieManager.getInstance().setAcceptCookie(true)

                    val webView = this
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                            false   // 所有链接（含 target=_blank）都在本 WebView 内打开，保证返回历史正确

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 记下当前页，退出重进恢复
                            url?.takeIf { it.isNotBlank() }?.let { ServerConfig.kmoeLastUrl = it }
                            // 强制宽视口 + 允许缩放：配合桌面 UA 渲染电脑端布局（无 meta 就注入一个 1280 宽的）
                            view?.evaluateJavascript(
                                "var m=document.querySelector('meta[name=viewport]');" +
                                    "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                                    "m.setAttribute('content','width=1280, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes');",
                                null,
                            )
                        }
                    }

                    // 拦截下载：epub/mobi/漫画压缩包一视同仁；先查重 + 弹确认，确认后进后台队列
                    setDownloadListener { url, userAgent, contentDisposition, _, _ ->
                        val name = decodeFileName(
                            url.substringAfterLast('/').substringBefore('?')
                                .ifBlank { "download_${System.currentTimeMillis()}" },
                        )
                        if (!app.library.isSupportedName(name)) {
                            Toast.makeText(context, "只支持下载 epub / mobi / 漫画压缩包(zip,rar,cbz,cbr)", Toast.LENGTH_SHORT).show()
                            return@setDownloadListener
                        }
                        val cookie = CookieManager.getInstance().getCookie(url)
                        val referer = webView.url
                        pendingDownload = PendingDownload(
                            url,
                            name,
                            app.library.titleFromFileName(name),
                            cookie,
                            referer,
                            userAgent,
                        )
                    }

                    loadUrl(ServerConfig.kmoeLastUrl ?: KMOE_URL)
                }
            },
            modifier = Modifier.fillMaxSize().padding(pad),
        )
    }

    // 下载确认弹窗：确认后转后台下载，进度在「进度」页查看
    pendingDownload?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("确认下载") },
            text = { Text("下载《${p.title}》到书库？\n下载在后台进行，进度可在「进度」页查看。") },
            confirmButton = {
                TextButton(onClick = {
                    app.enqueueDownload(p.url, p.name, p.cookie, p.referer, p.userAgent)
                    Toast.makeText(context, "已开始后台下载：${p.name}", Toast.LENGTH_SHORT).show()
                    pendingDownload = null
                }) { Text("下载") }
            },
            dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text("取消") } },
        )
    }
}
