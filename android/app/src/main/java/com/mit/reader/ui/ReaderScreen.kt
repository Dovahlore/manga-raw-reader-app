package com.mit.reader.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.mit.reader.PageStatus
import com.mit.reader.ReaderApp
import com.mit.reader.ReaderViewModel
import com.mit.reader.data.ReadingMode
import com.mit.reader.data.RemotePageAction
import com.mit.reader.data.RemoteReaderSnapshot
import com.mit.reader.data.RemoteReaderCommandHandler
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel()
    val app = LocalContext.current.applicationContext as ReaderApp
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        app.updateReaderScreenActive(true)
        onDispose { app.updateReaderScreenActive(false) }
    }

    LaunchedEffect(bookId) {
        app.library.book(bookId)?.let(vm::load)
    }

    // 后台「全书翻译」进行中时，定期从服务端补拉译文页：
    // pageTranslated 是 SharedFlow 不 replay——阅读器不在前台期间（比如切去浏览收藏夹）事件会被丢，
    // 这里每 3 秒兜底一次，保证「回到阅读器 / 切到别的收藏夹再回来」也能实时看到新翻好的页。
    val translatingBookId = app.translatingBookId
    val queuedBookIds = app.queuedBookIds
    val isTranslatingThis = translatingBookId == bookId || bookId in queuedBookIds
    LaunchedEffect(bookId, isTranslatingThis) {
        if (!isTranslatingThis) return@LaunchedEffect
        while (true) {
            vm.refreshFromServer()
            delay(3000)
        }
    }

    val book = vm.book ?: return
    val pagerState = rememberPagerState(
        initialPage = vm.currentPage,
        pageCount = { book.pageCount },
    )

    val volumeAndRemoteHandler = remember(book, pagerState) {
        RemoteReaderCommandHandler { action ->
            val target = (vm.currentPage + if (action == RemotePageAction.NEXT) 1 else -1)
                .coerceIn(0, book.pageCount - 1)
            vm.setPage(target)
            app.recordReadingProgress(book, target)
            scope.launch { pagerState.scrollToPage(target) }
            RemoteReaderSnapshot(book.id, book.title, target, book.pageCount)
        }
    }
    DisposableEffect(book.id, volumeAndRemoteHandler) {
        app.remoteControl.registerReader(
            bookId = book.id,
            title = book.title,
            pageCount = book.pageCount,
            currentPage = pagerState.currentPage,
            handler = volumeAndRemoteHandler,
        )
        onDispose { app.remoteControl.unregisterReader(volumeAndRemoteHandler) }
    }

    LaunchedEffect(pagerState.currentPage) {
        vm.setPage(pagerState.currentPage)
        // 记录阅读进度（本地 + 云端书防抖推后端，跨设备同步）
        app.recordReadingProgress(book, pagerState.currentPage)
        app.remoteControl.updateReader(
            bookId = book.id,
            title = book.title,
            page = pagerState.currentPage,
            pageCount = book.pageCount,
        )
    }

    val st = vm.pageStates[vm.currentPage]
    val status = st?.status

    // UI 自动隐藏：显示后 3 秒无操作自动隐藏；点空白区域切换显隐
    var uiVisible by remember { mutableStateOf(true) }
    var uiTick by remember { mutableIntStateOf(0) }
    var remoteDialogOpen by remember { mutableStateOf(false) }
    var bottomMenuHeight by remember { mutableStateOf(72.dp) }
    val density = LocalDensity.current
    fun keepUiAlive() { uiTick++ }
    var sliderValue by remember { mutableFloatStateOf(vm.currentPage.toFloat()) }
    LaunchedEffect(pagerState.currentPage) {
        sliderValue = pagerState.currentPage.toFloat()
    }
    LaunchedEffect(uiVisible, uiTick) {
        if (uiVisible) {
            delay(10000)
            uiVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(
            state = pagerState,
            reverseLayout = book.mode == ReadingMode.MANGA,
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            val pageState = vm.pageStates[i]
            // 已翻好的页直接展示译文（翻页不再先闪一下原图）；仅「当前页 + 手动切原图」时显示原图
            val show = pageState?.translatedFile != null &&
                !(i == vm.currentPage && vm.showOriginal)
            val file = if (show) pageState?.translatedFile else book.pageFiles[i]
            ZoomableImage(
                model = file,
                imageCacheKey = file?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" },
                contentDescription = "第 ${i + 1} 页",
                onImageTap = {
                    if (uiVisible) {
                        uiVisible = false
                    } else {
                        vm.toggleOriginal()
                    }
                },
                onMenuTap = {
                    uiVisible = !uiVisible
                    uiTick++
                },
                menuZoneHeight = bottomMenuHeight,
                pagerState = pagerState,
                reverseLayout = book.mode == ReadingMode.MANGA,
                pageCount = book.pageCount,
                modifier = Modifier.fillMaxSize(),
            )
        }

        vm.pageStates[vm.currentPage]?.takeIf { it.status == PageStatus.FAILED }?.let { state ->
            Text(
                "翻译失败：${state.error?.take(240) ?: "未知错误"}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        if (uiVisible) {
            // 顶部：返回 + 页码（半透明悬浮，避开状态栏）
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .statusBarsPadding()
                    .clickable { uiVisible = false; uiTick++ }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { keepUiAlive(); onBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    "${vm.currentPage + 1} / ${book.pageCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { keepUiAlive(); remoteDialogOpen = true }) {
                    Text("遥控", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = {
                        keepUiAlive()
                        val nextMode = if (book.mode == ReadingMode.MANGA) ReadingMode.NORMAL else ReadingMode.MANGA
                        app.setLibraryBookMode(book.id, nextMode)
                        vm.load(book.copy(mode = nextMode))
                    },
                ) {
                    Text(
                        if (book.mode == ReadingMode.MANGA) "日漫" else "普通",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            // 底部：跳页 + 自动翻译 + 翻译（半透明悬浮，避开底部手势条）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .navigationBarsPadding()
                    .onGloballyPositioned {
                        with(density) { bottomMenuHeight = it.size.height.toDp() }
                    }
                    .clickable { uiVisible = false; uiTick++ }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                if (book.pageCount > 1) {
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it; keepUiAlive() },
                        onValueChangeFinished = {
                            scope.launch {
                                pagerState.scrollToPage(sliderValue.toInt().coerceIn(0, book.pageCount - 1))
                            }
                        },
                        valueRange = 0f..(book.pageCount - 1).toFloat(),
                    )
                }
                // 底行三段布局：自动（左）· 遥控器信息（中，仅已连接时显示）· 翻译按钮（右）。
                // 未连接遥控器时中间留空，自动/翻译仍钉在左右两侧，位置不随连接状态漂移。
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("自动", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = vm.autoMode,
                            onCheckedChange = { vm.setAuto(it); keepUiAlive() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color.White.copy(alpha = 0.5f),
                                uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                            ),
                        )
                    }
                    app.remoteControl.connectedControllerName?.let { controllerName ->
                        if (RemoteControlUi.acceptedControllerName == controllerName) {
                            Text(
                                "退出被遥控",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .clickable {
                                        app.disconnectRemoteHostController()
                                        keepUiAlive()
                                    }
                                    .padding(horizontal = 8.dp),
                            )
                        } else {
                            Text(
                                "遥控 · $controllerName",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .widthIn(max = 120.dp),
                            )
                        }
                    }
                    Button(
                        onClick = { keepUiAlive(); vm.translateCurrent() },
                        enabled = status != PageStatus.RUNNING,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        when (status) {
                            PageStatus.RUNNING -> {
                                CircularProgressIndicator(
                                    Modifier.padding(end = 8.dp).size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text("翻译中…")
                            }
                            PageStatus.FAILED -> Text("重试")
                            PageStatus.DONE -> Text("重新翻译")
                            else -> Text("翻译本页")
                        }
                    }
                }
            }
        }

        if (!uiVisible) {
            app.remoteControl.connectedControllerName?.let { controllerName ->
                Text(
                    "遥控 · $controllerName",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .background(Color.Black.copy(alpha = 0.35f)),
                )
            }
        }
    }

    if (remoteDialogOpen) {
        AlertDialog(
            onDismissRequest = { remoteDialogOpen = false },
            title = { Text("遥控器模式") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                app.localTransferEndpoint()?.let {
                    Text("地址：$it", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "配对与配对码在「书库 · 设备」页；遥控请求到达时本机会弹窗确认，接受后才会被遥控。",
                    style = MaterialTheme.typography.bodySmall,
                )
                    app.remoteControl.connectedControllerName?.let {
                        Text("已连接：$it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { remoteDialogOpen = false }) { Text("关闭") }
            },
            dismissButton = app.remoteControl.connectedControllerName?.let {
                {
                    TextButton(
                        onClick = {
                            app.disconnectRemoteHostController()
                            remoteDialogOpen = false
                        },
                    ) { Text("断开") }
                }
            },
        )
    }
}

/**
 * 可缩放的图片：
 * - 双指捏合放大/缩小，放大后单指拖动平移（1x 下单指横滑仍留给 pager 翻页）
 * - 点图片本体：单击 → 切换原文/译文；双击 → 放大 / 恢复原始大小
 * - 点底部菜单条或图片未覆盖的屏幕区域（留白/黑边）→ 召唤/收起菜单
 */
@Composable
private fun ZoomableImage(
    model: Any?,
    imageCacheKey: Any?,
    contentDescription: String?,
    onImageTap: () -> Unit,
    onMenuTap: () -> Unit,
    menuZoneHeight: Dp,
    pagerState: PagerState,
    reverseLayout: Boolean,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val viewport = remember { mutableStateOf(IntSize.Zero) }
    val intrinsic = remember { mutableStateOf(Size.Zero) }
    // 显示尺寸 = 图片固有尺寸按视口等比缩放（fit 到屏幕内）。随视口变化（如进出全屏）自动重算。
    val fittedSize by remember { derivedStateOf {
        val s = intrinsic.value
        val vp = viewport.value
        if (s.width > 0f && s.height > 0f && vp.width > 0 && vp.height > 0) {
            val factor = minOf(vp.width / s.width, vp.height / s.height)
            IntSize(
                (s.width * factor).roundToInt().coerceAtLeast(1),
                (s.height * factor).roundToInt().coerceAtLeast(1),
            )
        } else {
            IntSize.Zero
        }
    } }
    val viewConfig = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val imageRequest = remember(model, imageCacheKey) {
        ImageRequest.Builder(context)
            .data(model)
            .memoryCacheKey(imageCacheKey?.toString())
            .diskCacheKey(imageCacheKey?.toString())
            .build()
    }

    // 单击/双击判定：单击延迟到双击超时后再触发，双击放大
    var tapSeq by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var pendingSingleTap by remember { mutableStateOf(false) }
    val currentOnImageTap by rememberUpdatedState(onImageTap)
    val currentOnMenuTap by rememberUpdatedState(onMenuTap)

    fun toggleZoom() {
        scale.floatValue = if (scale.floatValue > 1f) 1f else DOUBLE_TAP_ZOOM
        offset.value = Offset.Zero
    }

    // 判断某屏幕坐标是否落在图片（缩放/平移后）的范围内；图片未就绪时按整屏算
    fun isOnImage(pos: Offset): Boolean {
        val vp = viewport.value
        val fit = fittedSize
        if (vp == IntSize.Zero || fit == IntSize.Zero) return true
        val cx = vp.width / 2f + offset.value.x
        val cy = vp.height / 2f + offset.value.y
        val halfW = fit.width * scale.floatValue / 2f
        val halfH = fit.height * scale.floatValue / 2f
        return abs(pos.x - cx) <= halfW && abs(pos.y - cy) <= halfH
    }

    fun registerTap(pos: Offset) {
        val now = SystemClock.uptimeMillis()
        // 双击只看时间间隔，不比对两次点击位置（手指有抖动，位置判定太严格会误伤）
        val isDouble = lastTapAt != 0L && now - lastTapAt <= viewConfig.doubleTapTimeoutMillis
        if (isDouble) {
            lastTapAt = 0L
            pendingSingleTap = false
            toggleZoom()
        } else {
            lastTapAt = now
            pendingSingleTap = true
            tapSeq++
        }
    }
    val currentRegisterTap by rememberUpdatedState<(Offset) -> Unit>(::registerTap)

    LaunchedEffect(tapSeq) {
        if (pendingSingleTap) {
            delay(viewConfig.doubleTapTimeoutMillis)
            if (pendingSingleTap) {
                pendingSingleTap = false
                lastTapAt = 0L   // 单击完成后清掉，避免下一次点击被误判成双击
                currentOnImageTap()
            }
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { viewport.value = it.size }
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    var totalMove = 0f
                    var lastPos = downPos
                    var maxPointers = 1
                    var overscrollX = 0f
                    var lastVelocity = Offset.Zero
                    var lastEventTime = 0L
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > maxPointers) maxPointers = pressed
                        // 只有还有手指按着才算移动；抬起那一下 calculateCentroid 会返回 (0,0)，会误判成大位移
                        if (pressed > 0) {
                            val centroid = event.calculateCentroid()
                            totalMove += (centroid - lastPos).getDistance()
                            lastPos = centroid
                        }
                        // 只在双指捏合、或已经放大后的单指拖动时接管手势；
                        // 1x 下单指横滑不消费，交给 pager 翻页。
                        if (pressed >= 2 || scale.floatValue > 1f) {
                            val panChange = event.calculatePan()
                            // 记录平移速度（松手后做惯性滑动用）
                            val nowMs = SystemClock.uptimeMillis()
                            if (lastEventTime != 0L) {
                                val dt = ((nowMs - lastEventTime).coerceAtLeast(1L)) / 1000f
                                lastVelocity = Offset(panChange.x / dt, panChange.y / dt)
                            }
                            lastEventTime = nowMs
                            // 只有双指才改缩放；单指 calculateZoom 是「手指到屏幕原点的距离比」，
                            // 会随手指位置漂移导致缩放乱跳（也是拖动像有阻力的元凶）
                            val newScale = if (pressed >= 2) {
                                (scale.floatValue * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            } else {
                                scale.floatValue
                            }
                            scale.floatValue = newScale

                            if (pressed == 1 && newScale > 1f) {
                                // 放大后的单指平移：水平滑到边缘继续外滑 → 累计翻页距离；垂直始终平移
                                val maxX = ((fittedSize.width * newScale - viewport.value.width) / 2f).coerceAtLeast(0f)
                                val maxY = ((fittedSize.height * newScale - viewport.value.height) / 2f).coerceAtLeast(0f)
                                val panX = panChange.x
                                val panY = panChange.y
                                val atLeft = offset.value.x <= -maxX + 0.5f
                                val atRight = offset.value.x >= maxX - 0.5f
                                val newX = if ((atLeft && panX < 0f) || (atRight && panX > 0f)) {
                                    overscrollX += panX
                                    offset.value.x
                                } else {
                                    overscrollX = 0f
                                    (offset.value.x + panX).coerceIn(-maxX, maxX)
                                }
                                val newY = (offset.value.y + panY).coerceIn(-maxY, maxY)
                                offset.value = Offset(newX, newY)
                            } else {
                                offset.value = clampOffset(offset.value + panChange, newScale, fittedSize, viewport.value)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    // 单指、几乎没移动 → 判定为一次点击
                    if (maxPointers == 1 && totalMove < viewConfig.touchSlop) {
                        val menuZonePx = with(density) { menuZoneHeight.toPx() }
                        val inMenuZone = viewport.value.height > 0 &&
                            downPos.y >= viewport.value.height - menuZonePx
                        // 召唤菜单热区 = 底部菜单条 + 图片未覆盖的屏幕区域（两侧/上下留白）；
                        // 点在图片本体上仍是双击放大 / 单击切换原文译文
                        if (inMenuZone || !isOnImage(downPos)) {
                            pendingSingleTap = false
                            lastTapAt = 0L
                            currentOnMenuTap()
                        } else {
                            currentRegisterTap(downPos)
                        }
                    }

                    // 放大后滑到边缘继续滑 → 松手翻页（阈值用固定 dp，避免太容易误翻）
                    if (maxPointers == 1 && scale.floatValue > 1f && viewport.value.width > 0) {
                        val threshold = with(density) { 88.dp.toPx() }
                        if (abs(overscrollX) > threshold) {
                            val forward = if (reverseLayout) overscrollX > 0f else overscrollX < 0f
                            val target = (pagerState.currentPage + if (forward) 1 else -1)
                                .coerceIn(0, pageCount - 1)
                            scale.floatValue = 1f
                            offset.value = Offset.Zero
                            if (target != pagerState.currentPage) {
                                val t = target
                                scope.launch { pagerState.animateScrollToPage(t) }
                            }
                        }
                    } else if (maxPointers == 1 && scale.floatValue > 1f && lastVelocity.getDistance() > 500f) {
                        // 松手后按速度惯性滑动（放大浏览大图更省劲）
                        val v0 = lastVelocity
                        scope.launch {
                            var v = v0
                            while (v.getDistance() > 25f) {
                                offset.value = clampOffset(
                                    offset.value + Offset(v.x * 0.016f, v.y * 0.016f),
                                    scale.floatValue,
                                    fittedSize,
                                    viewport.value,
                                )
                                v = Offset(v.x * 0.92f, v.y * 0.92f)
                                delay(16)
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 先按比例把图片 fit 到屏幕内（拿到实际显示尺寸），再对「图片本身」做缩放/平移，
        // 而不是对整屏盒子做变换——这样放大后图片直接铺满屏幕（无黑边），
        // 平移范围也按图片实际尺寸算，拖动 1:1 跟手。
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    intrinsic.value = state.painter.intrinsicSize
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.floatValue
                    scaleY = scale.floatValue
                    translationX = offset.value.x
                    translationY = offset.value.y
                },
        )
    }
}

/** 把平移量限制在图片放大后刚好不脱出视口的范围（按图片实际 fit 尺寸算，1x 时强制归零）。 */
private fun clampOffset(o: Offset, scale: Float, fitted: IntSize, viewport: IntSize): Offset {
    if (fitted == IntSize.Zero || viewport == IntSize.Zero) return Offset.Zero
    val maxX = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}
