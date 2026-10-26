package com.lingion.sleepy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 浮框「调整本次」= 跳转完整编辑器(全屏二级页面), 保存只改这一次 —— 导航接线契约。
 *
 * 需求(PR #225 复审定案):
 * 1. 点浮框「调整本次」不再开底部表单(sheet), 而是 navigate 到 AddCourseScreen 完整编辑器;
 * 2. 编辑器进入"单次课发生态": 只列这一次课, 周/星期/节次全可改, 星期为单选(移动语义);
 * 3. 保存复用 OccurrenceEditor 的发生级管线(without + atDate + destinations + confirmConflicts
 *    + saveOccurrence)—— 只改这一次, 整组其他周原样保留;
 * 4. 浮框路径废弃 startInEdit 表单态: sheet 回归纯两级菜单(长按老路径), 参数整体移除。
 *
 * 行为断言在 AddCourseOccurrenceEditTest(Robolectric compose, 落库结果验证);
 * 此处按仓库先例(EditCourseJumpToClickedSlotTest)锁最容易回退的接线点本身:
 * 回调链存在性、旧状态清除、路由再水合路径、单一来源约束(§2 边界)。
 */
class AddCourseOccurrenceNavigationContractTest {

    private fun src(path: String): String = sequenceOf(
        File("app/src/main/java/com/lingion/sleepy/$path"),
        File("src/main/java/com/lingion/sleepy/$path"),
    ).first { it.isFile }.readText()

    @Test fun `popup edit routes to the editor callback instead of opening the sheet`() {
        val schedule = src("ui/screen/schedule/ScheduleScreen.kt")
        // 浮框调整 = 导航回调; 必须把发生对象交给导航层(openOccurrenceEdit)而非 selectedOccurrence sheet
        assertTrue("ScheduleScreen 必须暴露 onEditOccurrence 回调", schedule.contains("onEditOccurrence"))
        assertTrue("浮框调整必须走 navigator.openOccurrenceEdit 导航", schedule.contains("openOccurrenceEdit"))
        // startInEdit 表单态是本次要移除的错误理解产物, 任何残留都算回退
        assertFalse("occurrenceStartInEdit 状态必须整体移除", schedule.contains("occurrenceStartInEdit"))
        assertFalse("OccurrenceEditor 不再带 startInEdit 参数", src("ui/screen/edit/OccurrenceEditor.kt").contains("startInEdit"))
    }

    @Test fun `the route carries the occurrence and the host rehydrates it for restore`() {
        val routes = src("ui/nav/SleepyRoutes.kt")
        assertTrue("AddCourse 路由必须携带 occurrenceId", routes.contains("occurrenceId"))
        assertTrue("AddCourse 路由必须携带 occurrenceWeek", routes.contains("occurrenceWeek"))
        val navHost = src("ui/nav/SleepyNavHost.kt")
        // 进程恢复: editing=false 的单次路由活过 pop-guard, 条目必须由路由字段重建发生对象
        assertTrue("NavHost 必须从路由字段再水合 CourseOccurrences.resolve", navHost.contains("CourseOccurrences.resolve"))
        assertFalse("NavHost 不再给 sheet 传 startInEdit", navHost.contains("startInEdit"))
        val main = src("MainActivity.kt")
        assertTrue("手机端调用点必须接线 openOccurrenceEdit", main.contains("openOccurrenceEdit"))
    }

    @Test fun `the editor binds the occurrence and saves through the occurrence pipeline only`() {
        val editor = src("ui/screen/edit/AddCourseScreen.kt")
        assertTrue("AddCourseScreen 必须接收 occurrence 参数", editor.contains("occurrence: CourseOccurrence"))
        assertTrue("单次保存必须走 saveOccurrence 事务", editor.contains("saveOccurrence"))
        assertTrue("单次保存必须先摘出这一次 (without)", editor.contains("CourseOccurrences.without"))
        assertTrue("单次保存必须按日期重建该行 (atDate)", editor.contains("CourseOccurrences.atDate"))
        assertTrue("单次保存必须走调休保存预览链", editor.contains("saveDialogs.destinations"))
        assertTrue("单次保存必须走冲突确认", editor.contains("saveDialogs.confirmConflicts"))
        // 单一来源约束: 新管线必须复用 sheet 管线使用的同一组 util 函数与仓储方法(上面四条即锁此),
        // 禁止为编辑器再造第二套发生语义 helper。
    }

    @Test fun `the occurrence mode locks structure to one day and hides group affordances`() {
        val editor = src("ui/screen/edit/AddCourseScreen.kt")
        // 新增时段是组级动作, 单次模式禁止出现; 删除入口同禁
        assertTrue("单次模式必须门控新增时段按钮", editor.contains("occurrence == null"))
        // 星期单选(移动语义)在单次模式生效
        assertTrue("MultiDayPicker 必须支持单选替换模式", editor.contains("singleSelect"))
    }

    @Test fun `the grid popup hands the lifted course to the edit callback`() {
        val table = src("ui/component/CourseTableView.kt")
        assertTrue("弹窗编辑按钮必须走 onOccurrenceEdit 回退链",
            table.contains("(onOccurrenceEdit ?: onCourseLongClick)?.invoke(lifted)"))
    }
}
