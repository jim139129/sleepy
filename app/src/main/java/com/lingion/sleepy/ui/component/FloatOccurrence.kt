package com.lingion.sleepy.ui.component

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingion.sleepy.R
import com.lingion.sleepy.data.entity.CourseEntity
import com.lingion.sleepy.ui.theme.noRippleClickable

/**
 * 长按浮起交互的状态容器 — 与 [QuickAddCourseState] 同级模式(由调用方持有,
 * 翻页/周切换不丢)。卡片从网格浮到顶层: 背景高斯模糊 + 编辑/删除小浮框;
 * 拖拽开始后模糊与浮框消失, 只有卡片跟手; 合法落点吸附节次并回调移动该次课,
 * 非法落点弹回原位、浮框回归。
 */
@Stable
class CourseFloatState {
    var course: CourseEntity? by mutableStateOf(null)
    var day: Int by mutableStateOf(1)
    /** 浮起时的周(1 起, 页+1) — 屏根空白确认路径看不到网格 currentWeek, 时间域随浮起快照存这里。 */
    var week: Int by mutableStateOf(1)
    var startNode: Int by mutableStateOf(1)
    var endNode: Int by mutableStateOf(1)
    var isGrey: Boolean by mutableStateOf(false)
    var dragging: Boolean by mutableStateOf(false)
    /** 网格坐标系(dp, 相对 grid 主体 Box 原点): 浮卡当前/原始位置与尺寸。 */
    var x: Dp by mutableStateOf(0.dp)
    var y: Dp by mutableStateOf(0.dp)
    var width: Dp by mutableStateOf(0.dp)
    var height: Dp by mutableStateOf(0.dp)
    var originX: Dp = 0.dp
    var originY: Dp = 0.dp
    // 拉起时的原始跨度快照 — 改跨度只拖把手不即时落库(二期 D 契约):
    // spanChanged 判 pending, revertSpan 供确认后否决复原, 落库几何只读状态不量网格。
    var originStartNode: Int = 1
    var originEndNode: Int = 1
    var originHeight: Dp = 0.dp
    val step: Int get() = (endNode - startNode + 1).coerceAtLeast(1)
    val active: Boolean get() = course != null
    /** 浮卡当前跨度与拉起时不同 — 空白点击/返回键须先走「是否调整持续节次」确认。 */
    val spanChanged: Boolean get() = course != null && (startNode != originStartNode || endNode != originEndNode)

    /** 把手拉伸未获确认 → 位置/高度回到拉起时的原样, 浮卡本身留在原位。 */
    fun revertSpan() {
        startNode = originStartNode
        endNode = originEndNode
        y = originY
        height = originHeight
    }

    /**
     * 空白点击/返回键的统一分派 (D 契约): 无 pending 拉伸 → 直接收层;
     * 有 pending → [submit] 走与落点同一个移动管线 (resizeOnly=true, 目标 = 当前格 +
     * 拉伸后的起止), 落库成功收层, 否决 → [revertSpan] 复原原跨度。
     */
    fun confirmPendingResizeBeforeDismiss(
        submit: (lifted: CourseEntity, day: Int, node: Int, step: Int, onResult: (Boolean) -> Unit) -> Unit,
    ) {
        val lifted = course ?: return
        if (!spanChanged) { dismiss(); return }
        submit(lifted, lifted.day, startNode, step) { saved -> if (saved) dismiss() else revertSpan() }
    }
    internal var cardBounds: Rect? by mutableStateOf(null)
    internal var popupBounds: Rect? by mutableStateOf(null)

    fun lift(
        course: CourseEntity,
        day: Int,
        week: Int,
        startNode: Int,
        endNode: Int,
        x: Dp,
        y: Dp,
        width: Dp,
        height: Dp,
        isGrey: Boolean,
    ) {
        this.course = course
        this.day = day
        this.week = week
        this.startNode = startNode
        this.endNode = endNode
        this.x = x; this.y = y; this.width = width; this.height = height
        this.isGrey = isGrey
        originX = x; originY = y
        originStartNode = startNode; originEndNode = endNode; originHeight = height
        dragging = false
        cardBounds = null
        popupBounds = null
    }

    fun dismiss() {
        course = null
        dragging = false
        cardBounds = null
        popupBounds = null
    }
}

/** 浮起时模糊整个网格内容层 — API 31+ 高斯模糊; 26-30 无 RenderEffect, 由遮罩压暗兜底。 */
@SuppressLint("NewApi")
internal fun Modifier.gridFloatBlur(): Modifier = graphicsLayer {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val radius = 20.dp.toPx()
        renderEffect = BlurEffect(radius, radius)
    }
}

/**
 * 浮层空档处的按下: 只关浮层, 不点击课程、不打开加课胶囊。
 * Initial pass — 与 [dismissQuickAddOnOutsidePress] 同构(祖先节点最先拿到 down):
 * 落在浮卡/浮框矩形内的 down 原样放行(交给拖拽/按钮), 其余空档消费并关浮层,
 * 底层网格因此既不加课也不点课。[onBlankPress] 非空且跨度已被把手拉伸
 * (D 契约: 拉伸不自动落库) → 空档按下改走确认回调, 浮层不直接消失。
 */
fun Modifier.dismissFloatOnOutsidePress(
    state: CourseFloatState,
    onBlankPress: (() -> Unit)? = null,
): Modifier = composed {
    var coordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    val blankPress by rememberUpdatedState(onBlankPress)
    onGloballyPositioned { coordinates = it }.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!state.active || state.dragging || down.isConsumed) return@awaitEachGesture
            val position = coordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            val onLifted = state.cardBounds?.contains(position) == true ||
                state.popupBounds?.contains(position) == true
            if (onLifted) return@awaitEachGesture
            down.consume()
            val pending = blankPress
            if (pending != null && state.spanChanged) pending() else state.dismiss()
        }
    }
}

/**
 * 浮起的课程卡: 复用 [CourseOverlayCard] 的取色/文案渲染(去掉内层点击 —
 * 浮卡只响应拖拽), 上下 14dp 边缘把手与加课胶囊同款手势语义。
 */
@Composable
internal fun FloatingOccurrenceCard(
    state: CourseFloatState,
    scale: Float,
    cornerRatio: Float,
    groupRows: List<CourseEntity>,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onResizeStart: (Boolean) -> Unit,
    onResize: (Boolean, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifted = state.course ?: return
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val resizeStart by rememberUpdatedState(onResizeStart)
    val resize by rememberUpdatedState(onResize)
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((12 * scale * cornerRatio).dp)
    Box(modifier.onGloballyPositioned { state.cardBounds = it.boundsInRoot() }) {
        CourseOverlayCard(
            course = lifted,
            modifier = Modifier
                .fillMaxSize()
                .shadow((6 * scale).dp, shape, clip = false)
                .testTag("float-card")
                .pointerInput(lifted.id) {
                    detectDragGestures(
                        onDragStart = { start() },
                        onDrag = { change, amount -> change.consume(); drag(amount) },
                        onDragEnd = { drop() },
                        onDragCancel = { drop() },
                    )
                },
            isGrey = state.isGrey,
            scale = scale,
            cornerRatio = cornerRatio,
            groupRows = groupRows,
        )
        for (top in listOf(true, false)) {
            Box(
                modifier = Modifier
                    .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(14.dp)
                    .testTag(if (top) "float-top" else "float-bottom")
                    .pointerInput(lifted.id) {
                        detectVerticalDragGestures(
                            onDragStart = { resizeStart(top) },
                            onVerticalDrag = { change, amount -> change.consume(); resize(top, amount) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(16.dp).height(3.dp).background(colors.primary, RoundedCornerShape(50)))
            }
        }
    }
}

/** 浮卡上方的小浮框: 编辑这一节 / 删除本次(复用单次课操作文案, 口径与编辑器一致)。 */
@Composable
internal fun FloatActionPopup(
    state: CourseFloatState,
    scale: Float,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((10 * scale).dp)
    Row(
        modifier = modifier
            .shadow((4 * scale).dp, shape, clip = false)
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, colors.outlineVariant, shape)
            .onGloballyPositioned { state.popupBounds = it.boundsInRoot() }
            .testTag("float-popup"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.occurrence_adjust),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = (12 * scale).sp,
            ),
            color = colors.primary,
            modifier = Modifier.testTag("float-edit").noRippleClickable(onEdit)
                .padding(horizontal = (14 * scale).dp, vertical = (8 * scale).dp),
        )
        Box(Modifier.width(1.dp).height(20.dp).background(colors.outlineVariant))
        Text(
            text = stringResource(R.string.occurrence_delete),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = (12 * scale).sp,
            ),
            color = colors.error,
            modifier = Modifier.testTag("float-delete").noRippleClickable(onDelete)
                .padding(horizontal = (14 * scale).dp, vertical = (8 * scale).dp),
        )
    }
}
