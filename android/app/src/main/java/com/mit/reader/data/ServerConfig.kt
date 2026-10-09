package com.mit.reader.data

import android.content.Context
import android.content.SharedPreferences

/** 服务器配置（可改）：地址支持 http/https + 域名/端口；API Key 做接口鉴权。 */
object ServerConfig {
    // 默认指向本机（后端 mit-app-api）。真机/模拟器都在同一局域网时用这个 IP；
    // 换电脑或改端口在「设置」里改。Android 模拟器里 10.0.2.2 = 宿主机。
    const val DEFAULT_URL = "http://192.168.0.90:8020"

    private const val PREFS = "server"
    private const val KEY_URL = "base_url"
    private const val KEY_KEY = "api_key"
    private const val KEY_LIB_FOLDER = "library_folder_uri"
    private const val KEY_LIB_FOLDER_NAME = "library_folder_name"
    private const val KEY_LIBRARY_STORAGE = "library_storage_dir"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs?.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        set(value) {
            prefs?.edit()?.putString(KEY_URL, value.trim().trimEnd('/'))?.apply()
        }

    /** API Key 兼作账号：同一个 Key 的多台设备互通，不同 Key 互不可见（服务端用其 SHA-256 作 owner）。 */
    var apiKey: String
        get() = prefs?.getString(KEY_KEY, "")?.trim().orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_KEY, value.trim())?.apply()
        }

    /** 书库文件夹（SAF 树 URI，用于自动扫描导入 epub/mobi）。 */
    var libraryFolderUri: String?
        get() = prefs?.getString(KEY_LIB_FOLDER, null)
        set(value) {
            prefs?.edit()?.putString(KEY_LIB_FOLDER, value)?.apply()
        }

    /** 书库文件夹的显示名（方便设置页展示）。 */
    var libraryFolderName: String?
        get() = prefs?.getString(KEY_LIB_FOLDER_NAME, null)
        set(value) {
            prefs?.edit()?.putString(KEY_LIB_FOLDER_NAME, value)?.apply()
        }

    /** App 数据保存位置。null = 旧版内部数据目录；可选择 SD 卡上的 App 专用目录。 */
    var libraryStorageDir: String?
        get() = prefs?.getString(KEY_LIBRARY_STORAGE, null)
        set(value) {
            prefs?.edit()?.putString(KEY_LIBRARY_STORAGE, value)?.apply()
        }

    /** Kmoe WebView 上次停留的网址（退出重进恢复页面用）。 */
    var kmoeLastUrl: String?
        get() = prefs?.getString("kmoe_last_url", null)
        set(value) {
            prefs?.edit()?.putString("kmoe_last_url", value)?.apply()
        }
}
