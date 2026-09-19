package com.lingion.sleepy.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * issue#45: 自研 Overlay 栈 → Navigation Compose。
 *
 * 迁移理由(不是"官方即正确",而是四条需求都压在自研栈的短板上):
 * - 预测性返回: NavHost 的 pop 动画由系统返回手势进度直接驱动,自研栈要自己接
 *   PredictiveBackHandler 并手搓手势进度动画,且每次系统手势语义变更都得跟;
 * - 页间过渡: enter/exit/popEnter/popExit 原生成对,不用手工维护进出场配对;
 * - 页面状态: 每个 NavBackStackEntry 自带 saveable 作用域(滚动/折叠/输入),
 *   替代手写 SaveableStateProvider + 自定义 Saver + 一堆伴生参数持久化
 *   (旧实现注释里记录过 3 次这类事故: 返回分层、旋转参数丢失、编辑会话丢弃);
 * - 生命周期: 非可见 entry 自动 ON_STOP,后台页停止取数与动画。
 *
 * 约定: 可空 Long 参数一律用 -1 哨兵(NavType.LongType 不可空),解析后转回 null。
 */
object Routes {
    const val MAIN = "main"
    const val ADD_COURSE = "add_course?courseId={courseId}&editing={editing}"
    const val ALL_TABLES = "all_tables"
    const val EDIT_TABLE = "edit_table?tableId={tableId}&pendingNew={pendingNew}&prevDefault={prevDefault}"
    const val APPEARANCE = "appearance"
    const val GENERAL = "general"
    const val HOLIDAY = "holiday"
    const val EXPORT = "export"
    const val REMINDER = "reminder"
    const val ABOUT = "about"
    const val LICENSE = "license"
    const val WIDGET_MANAGEMENT = "widget_management"
    const val WIDGET_EDIT = "widget_edit/{widgetId}"
    const val PERIOD_TABLES = "period_tables"
    const val PERIOD_EDIT = "period_edit/{periodId}?isNew={isNew}"

    /** 可空 id 的哨兵值。 */
    const val NO_ID = -1L

    fun addCourse(courseId: Long = NO_ID, editing: Boolean = false) =
        "add_course?courseId=$courseId&editing=$editing"

    fun editTable(tableId: Long = NO_ID, pendingNew: Long = NO_ID, prevDefault: Long = NO_ID) =
        "edit_table?tableId=$tableId&pendingNew=$pendingNew&prevDefault=$prevDefault"

    fun widgetEdit(widgetId: Int) = "widget_edit/$widgetId"

    fun periodEdit(periodId: Long, isNew: Boolean = false) = "period_edit/$periodId?isNew=$isNew"
}

/** 路由里的可空 id: -1 哨兵 → null(EditTable 的 tableId=null 语义是"编辑当前课表",不可混)。 */
fun NavBackStackEntry.longArg(key: String): Long? {
    val raw = arguments?.getLong(key) ?: Routes.NO_ID
    return if (raw == Routes.NO_ID) null else raw
}

fun NavBackStackEntry.intArg(key: String): Int = arguments?.getInt(key) ?: -1

fun NavBackStackEntry.boolArg(key: String): Boolean = arguments?.getBoolean(key) ?: false

val addCourseArgs = listOf(
    navArgument("courseId") { type = NavType.LongType; defaultValue = Routes.NO_ID },
    navArgument("editing") { type = NavType.BoolType; defaultValue = false },
)

val courseIdArgs = listOf(
    navArgument("courseId") { type = NavType.LongType; defaultValue = Routes.NO_ID },
)

val editTableArgs = listOf(
    navArgument("tableId") { type = NavType.LongType; defaultValue = Routes.NO_ID },
    navArgument("pendingNew") { type = NavType.LongType; defaultValue = Routes.NO_ID },
    navArgument("prevDefault") { type = NavType.LongType; defaultValue = Routes.NO_ID },
)

val widgetEditArgs = listOf(
    navArgument("widgetId") { type = NavType.IntType },
)

val periodEditArgs = listOf(
    navArgument("periodId") { type = NavType.LongType },
    navArgument("isNew") { type = NavType.BoolType; defaultValue = false },
)

/**
 * MD3E 共享轴(shared axis)过渡 — 前进沿 X 轴推入,返回反向抽出。
 *
 * 弹簧取自 MotionScheme.expressive(): 位移走 spatial 通道(Expressive 默认
 * dampingRatio 0.8 / stiffness 380,允许轻微过冲),透明度走 effects 通道
 * (1.0 / 1600,不过冲)。位移量取 1/4 屏宽而非整屏: 上一页保持可见,方向感更明确,
 * 也跟预测性返回的手势跟手幅度对得上。
 *
 * 必须在 AppRoot 组合期求值再捕获进 NavHost 的 transition lambda —
 * 那个 lambda 不是 composable 上下文,不能就地读 MaterialTheme。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun sleepySharedAxisEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return slideInHorizontally(
        initialOffsetX = { it / 4 },
        animationSpec = motion.defaultSpatialSpec<IntOffset>(),
    ) + fadeIn(motion.defaultEffectsSpec<Float>())
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun sleepySharedAxisExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return slideOutHorizontally(
        targetOffsetX = { -it / 8 },
        animationSpec = motion.defaultSpatialSpec<IntOffset>(),
    ) + fadeOut(motion.defaultEffectsSpec<Float>())
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun sleepySharedAxisPopEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return slideInHorizontally(
        initialOffsetX = { -it / 8 },
        animationSpec = motion.defaultSpatialSpec<IntOffset>(),
    ) + fadeIn(motion.defaultEffectsSpec<Float>())
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun sleepySharedAxisPopExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return slideOutHorizontally(
        targetOffsetX = { it / 4 },
        animationSpec = motion.defaultSpatialSpec<IntOffset>(),
    ) + fadeOut(motion.defaultEffectsSpec<Float>())
}
