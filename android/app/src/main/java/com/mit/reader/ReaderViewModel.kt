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
import com.mit.reader.data.ServerConfig
import com.mit.reader.data.serverId
import kotlinx.coroutines.CancellationException
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
    val startedAt: Long = 0L, // 进入 RUNNING 的时刻（死状态/超时判定用），其余状态为 0
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

    /** 当前页 RUNNING 持续超过 [STALE_THRESHOLD_MS] 仍无结果（疑似失败事件丢失的死状态），
     *  Compose 可观察；阅读器重试按钮据此在「翻译中」时解禁。 */
    var translationStale by mutableStateOf(false)
        private set

    val pageStates = mutableStateMapOf<Int, PageState>()

    private val pages: List<File> get() = book?.pageFiles ?: emptyList()
    private var watchJob: Job? = null
    private var restoreJob: Job? = null
    // 单页 RUNNING 轮询协程（按页记录：手动重置重翻时先取消旧轮询，避免双循环抢写状态）
    private val pollJobs = mutableMapOf<Int, Job>()

    init {
        // 死状态看门狗：每秒检查当前页 RUNNING 时长，超阈值置 translationStale（重试按钮解禁）。
        // 覆盖所有 RUNNING 来源：translatePage 发起的、load() 恢复的服务端进行中页。
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val st = pageStates[currentPage]
                translationStale = st?.status == PageStatus.RUNNING &&
                    st.startedAt > 0L &&
                    System.currentTimeMillis() - st.startedAt > STALE_THRESHOLD_MS
            }
        }
    }

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
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            val activePages = try {
                readerApp.activePageJobs(b)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (book?.id == b.id) {
                activePages.forEach { index ->
                    if (index in b.pageFiles.indices) {
                        pageStates[index] = PageState(
                            status = PageStatus.RUNNING,
                            translatedFile = readerApp.library.translatedCacheFile(b.id, index)
                                .takeIf { it.exists() && it.length() > 0L },
                            startedAt = System.currentTimeMillis(),
                        )
                    }
                }
            }
        }
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
                        val cachedFile = readerApp.library.translatedCacheFile(b.id, pageIndex)
                        pageStates[pageIndex] = PageState(
                            status = PageStatus.FAILED,
                            translatedFile = cachedFile.takeIf { it.exists() && it.length() > 0L },
                            error = error,
                        )
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
        if (pageStates[currentPage]?.translatedFile != null) {
            showOriginal = !showOriginal
        }
    }

    /** 当前页手动翻译；已翻过则强制重翻（force=true）。 */
    fun translateCurrent() {
        val state = pageStates[currentPage]
        val done = state?.status == PageStatus.DONE || state?.translatedFile != null
        showOriginal = false
        // 手动重试后重新计时 RUNNING 时长（死状态看门狗以 startedAt 为准，这里立即复位按钮态）
        translationStale = false
        translatePage(currentPage, force = done, manual = true)
    }

    /** 自动预翻：当前页及其后 3 页（含当前页没翻过的）。 */
    fun prefetchAfter(fromIndex: Int) {
        if (fromIndex !in pages.indices) return
        if (!ServerConfig.isTranslationConfigured()) {
            if (pageStates[fromIndex]?.status == PageStatus.IDLE) translatePage(fromIndex)
            return
        }
        for (i in fromIndex until minOf(fromIndex + 4, pages.size)) {
            if (pageStates[i]?.status == PageStatus.IDLE) translatePage(i)
        }
    }

    private fun translatePage(index: Int, force: Boolean = false, manual: Boolean = false) {
        val b = book ?: return
        if (index !in pages.indices) return
        val previous = pageStates[index]
        // manual/force 请求优先于 RUNNING 拦截：手动点按或强制重翻可以把卡死的 RUNNING 重置重翻，
        // 避免「失败事件丢失 → 永久翻译中」的死状态无法恢复（04-高3）
        if (previous?.status == PageStatus.RUNNING && !manual && !force) return
        if (!manual && previous?.status != PageStatus.IDLE) return
        if (!force && previous?.status == PageStatus.DONE) return
        val startedAt = System.currentTimeMillis()
        val preservedFile = if (force || manual) previous?.translatedFile else null
        pageStates[index] = PageState(
            status = PageStatus.RUNNING,
            translatedFile = preservedFile,
            startedAt = startedAt,
        )
        // 取消该页旧轮询再起新轮询（manual 重置 RUNNING 时旧循环还活着，不能叠加）
        pollJobs.remove(index)?.cancel()
        pollJobs[index] = viewModelScope.launch {
            val deadline = startedAt + WATCH_TIMEOUT_MS
            while (pageStates[index]?.status == PageStatus.RUNNING) {
                delay(2_000)
                val cachedFile = readerApp.library.translatedCacheFile(b.id, index)
                if (cachedFile.exists() && cachedFile.length() > 0 && cachedFile.lastModified() >= startedAt) {
                    pageStates[index] = PageState(status = PageStatus.DONE, translatedFile = cachedFile)
                    return@launch
                }
                if (System.currentTimeMillis() >= deadline) {
                    // 轮询总超时：不再无限等待，置 FAILED 让用户可以重试
                    pageStates[index] = PageState(
                        status = PageStatus.FAILED,
                        translatedFile = preservedFile?.takeIf { it.exists() && it.length() > 0L },
                        error = "翻译超时，可重试",
                    )
                    return@launch
                }
            }
        }
        readerApp.translateBookPage(b, index, force)
    }

    private companion object {
        /** RUNNING 持续超过此时长视为疑似死状态（重试按钮解禁）。 */
        const val STALE_THRESHOLD_MS = 90_000L
        /** 单页 RUNNING 轮询总超时：超过后置 FAILED，不再无限等待。 */
        const val WATCH_TIMEOUT_MS = 180_000L
    }
}
