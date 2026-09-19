package com.lingion.sleepy.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import com.lingion.sleepy.data.entity.CourseEntity

/**
 * issue#45: 导航意图的唯一出口。
 *
 * 页面只调语义方法(openEditTable / pop / …),不自己拼路由字符串、不自己碰
 * NavHostController — 路由格式与"进页前要先改哪些会话态"都收在这一个类里。
 * 自研 Overlay 栈时代这些副作用散在 AppRoot 的 14 个 if 分支里,每加一页都要
 * 同步改 push 点 + BackHandler + SaveableStateProvider key,漏一处就是返回丢状态。
 *
 * 生命周期: 由 AppRoot remember 持有(与 NavHostController 同作用域),不是 ViewModel —
 * 它只持 nav/session/vm 的引用,自身无可丢状态。
 */
class SleepyNavigator(
    val navController: NavHostController,
    val session: NavSession,
) {
    private val nav get() = navController
    /**
     * 进一个覆盖页。
     *
     * 同路由连续 push 去重(双击底栏/连点入口会打两次 navigate),
     * 但**不**用 popUpTo 压栈: General→Holiday 这类嵌套要求 Holiday 压在
     * General 之上,返回只弹一层(v7.10.8 返回键分层修复的既有语义)。
     */
    fun push(route: String) {
        if (nav.currentBackStackEntry?.destination?.route == route) return
        nav.navigate(route)
    }

    /** 退一层;栈已空(停在起点)时返回 false,由调用方决定退出行为。 */
    fun pop(): Boolean = nav.popBackStack()

    /** 回主 Tab 页并清掉整摞覆盖页(如删表后必须离开编辑页)。 */
    fun popToMain() {
        nav.popBackStack(Routes.MAIN, inclusive = false)
    }

    // ---- 覆盖页入口 ----------------------------------------------------

    fun openAddCourse(courseId: Long = NavSession.NO_ID, editing: Boolean = false) =
        push(Routes.addCourse(courseId, editing))

    fun openAllTables() = push(Routes.ALL_TABLES)
    fun openAppearance() = push(Routes.APPEARANCE)
    fun openGeneral() = push(Routes.GENERAL)
    fun openHoliday() = push(Routes.HOLIDAY)
    fun openExport() = push(Routes.EXPORT)
    fun openReminder() = push(Routes.REMINDER)
    fun openAbout() = push(Routes.ABOUT)
    fun openLicense() = push(Routes.LICENSE)
    fun openWidgetManagement() = push(Routes.WIDGET_MANAGEMENT)
    fun openPeriodTables() = push(Routes.PERIOD_TABLES)

    fun openWidgetEdit(widgetId: Int) = push(Routes.widgetEdit(widgetId))

    fun openPeriodEdit(periodId: Long, isNew: Boolean = false) =
        push(Routes.periodEdit(periodId, isNew))

    /**
     * 打开课表编辑页。
     *
     * pendingNew/prevDefault 走**路由参数**而不是会话字段: 谁建的表、要不要弃,
     * 跟着返回栈一起存,旋转/进程恢复后弃表语义不丢(旧实现靠三个平行的
     * rememberSaveable 变量,与 overlayStack 的保存时机耦合,是历史 bug 源)。
     */
    fun openEditTable(
        tableId: Long = NavSession.NO_ID,
        pendingNew: Long = NavSession.NO_ID,
        prevDefault: Long = NavSession.NO_ID,
    ) = push(Routes.editTable(tableId, pendingNew, prevDefault))

    /** 新建时间节次表并直接进编辑页(isNew=true → 未保存返回即弃残留行)。 */
    fun createPeriodTableAndEdit(newId: Long) = openPeriodEdit(newId, isNew = true)
}

/**
 * 导航会话态: 无法进路由的东西。
 *
 * 只剩 editingCourse 一项 — CourseEntity 不可 Bundle 化,所以它**故意不持久化**:
 * 旋转/进程恢复后编辑会话安全丢弃(基线 §1.3),绝不恢复成"编辑课程"空表单,
 * 否则用户会当成新课程重复添加。AddCourse 路由带 editing=true 标记,
 * AppRoot 在恢复后据此把这种路由弹掉。
 *
 * 其余旧会话字段(pendingNewTableId / previousDefaultTableId / pendingNewPeriodTableId)
 * 已下沉为路由参数,不再需要跨页同步。
 */
class NavSession {
    var editingCourse: CourseEntity? by mutableStateOf(null)
        private set

    /** 只经 beginEditCourse/clearEditCourse 改,防止页面绕过例外规则直接赋值。 */
    fun beginEditCourse(course: CourseEntity) { editingCourse = course }
    fun clearEditCourse() { editingCourse = null }

    companion object {
        /** 可空 id 的哨兵值(NavType 的 Long 参数不可空)。 */
        const val NO_ID = Routes.NO_ID
    }
}
