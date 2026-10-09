package com.mit.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mit.reader.data.Book
import com.mit.reader.data.serverId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

enum class PageStatus { IDLE, RUNNING, DONE, FAILED }

data class PageState(
    val status: PageStatus = PageStatus.IDLE,
    val jobId: String? = null,
    val translatedFile: File? = null,
    val error: String? = null,
)

class ReaderViewModel(private val app: Application) : AndroidViewModel(app) {

    private val readerApp get() = app as ReaderApp

    var book by mutableStateOf<Book?>(null)
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    var showOriginal by mutableStateOf(false)
        private set
    var autoMode by mutableStateOf(false)
        private set

    val pageStates = mutableStateMapOf<Int, PageState>()

    private val pages: List<File> get() = book?.pageFiles ?: emptyList()
    private var watchJob: Job? = null

    fun load(b: Book) {
        book = b
        // 从上次读到的页继续（没有记录就从第 0 页/封面开始）
        currentPage = readerApp.library.readingProgress(b.id)?.page
            ?.coerceIn(0, (b.pageCount - 1).coerceAtLeast(0)) ?: 0
        showOriginal = false
        pageStates.clear()
        // 本地译文缓存还在的页，直接标记 DONE（旋转/重启后不用重翻，也不用再问服务端）
        for (i in b.pageFiles.indices) {
            val f = readerApp.library.translatedCacheFile(b.id, i)
            if (f.exists() && f.length() > 0) {
                pageStates[i] = PageState(status = PageStatus.DONE, translatedFile = f)
            }
        }
        // 打开阅读器时从服务端补拉译文页（所有书都拉：同步书别的设备新翻的、非同步书服务端已翻好的都补齐）。
        // 只补本地缺的/指纹变了的，不全量。
        refreshFromServer()
        // 事件驱动：后台「全书翻译」每下好一页就发事件，这里按书过滤、更新 pageStates（免轮询）
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            launch {
                readerApp.pageTranslated.collect { (bookId, pageIndex) ->
                    if (bookId == b.id && pageIndex in b.pageFiles.indices) {
                        val f = readerApp.library.translatedCacheFile(b.id, pageIndex)
                        pageStates[pageIndex] = PageState(status = PageStatus.DONE, translatedFile = f)
                    }
                }
            }
            launch {
                readerApp.pageTranslationFailed.collect { (bookId, pageIndex, error) ->
                    if (bookId == b.id && pageIndex in b.pageFiles.indices) {
                        pageStates[pageIndex] = PageState(status = PageStatus.FAILED, error = error)
                    }
                }
            }
        }
    }

    /** 从服务端补拉译文页到本地（只补缺失/指纹变化的页），再标 DONE。优先拉当前页附近的页。 */
    fun refreshFromServer() {
        val b = book ?: return
        readerApp.refreshBookFromServer(b)
    }

    fun setPage(i: Int) {
        if (i < 0 || i >= pages.size) return
        currentPage = i
        // 翻页后默认展示译文（若该页已翻好），用户点「看原图」才临时切回原文
        showOriginal = false
        if (autoMode) prefetchAfter(i)
    }

    fun setAuto(on: Boolean) {
        autoMode = on
        if (on) prefetchAfter(currentPage)
    }

    fun toggleOriginal() {
        if (pageStates[currentPage]?.status == PageStatus.DONE) {
            showOriginal = !showOriginal
        }
    }

    /** 当前页手动翻译；已翻过则强制重翻（force=true）。 */
    fun translateCurrent() {
        val done = pageStates[currentPage]?.status == PageStatus.DONE
        translatePage(currentPage, force = done)
    }

    /** 自动预翻：当前页及其后 3 页（含当前页没翻过的）。 */
    fun prefetchAfter(fromIndex: Int) {
        for (i in fromIndex until minOf(fromIndex + 4, pages.size)) {
            translatePage(i)
        }
    }

    private fun translatePage(index: Int, force: Boolean = false) {
        val b = book ?: return
        if (index !in pages.indices) return
        if (pageStates[index]?.status == PageStatus.RUNNING) return          // 已在跑
        if (!force && pageStates[index]?.status == PageStatus.DONE) return   // 已翻过
        pageStates[index] = PageState(status = PageStatus.RUNNING)
        readerApp.translateBookPage(b, index, force)
    }
}
