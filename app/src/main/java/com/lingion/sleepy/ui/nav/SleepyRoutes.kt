package com.lingion.sleepy.ui.nav

import kotlinx.serialization.Serializable
import androidx.navigation3.runtime.NavKey

/** Stable, typed keys for the app's single navigation stack. */
@Serializable
sealed interface SleepyRoute : NavKey {
    @Serializable data object Main : SleepyRoute
    // occurrence 三字段: 浮框「调整本次」→ 完整编辑器单次发生态。路由自带学期内定位
    // (occurrenceId + 周 + 星期), 进程恢复时可由路由字段重建 CourseOccurrence, 不依赖会话态。
    // editing=false: 发生态活过 pop-guard(它只清 editing=true 的编辑路由)。
    @Serializable data class AddCourse(val courseId: Long = NO_ID, val editing: Boolean = false, val tableId: Long = NO_ID, val week: Int = 0, val day: Int = 1, val node: Int = 1, val step: Int = 1, val occurrenceId: Long = NO_ID, val occurrenceWeek: Int = 0, val occurrenceDay: Int = 0) : SleepyRoute
    @Serializable data object AllTables : SleepyRoute
    @Serializable data object CourseList : SleepyRoute
    @Serializable data class EditTable(
        val tableId: Long = NO_ID,
        val pendingNew: Long = NO_ID,
        val prevDefault: Long = NO_ID,
    ) : SleepyRoute
    @Serializable data object Appearance : SleepyRoute
    @Serializable data object PeriodHeaderSettings : SleepyRoute
    @Serializable data object General : SleepyRoute
    @Serializable data object Holiday : SleepyRoute
    @Serializable data object Export : SleepyRoute
    @Serializable data object TableReminder : SleepyRoute
    @Serializable data object Reminder : SleepyRoute
    @Serializable data object About : SleepyRoute
    @Serializable data object License : SleepyRoute
    @Serializable data object WidgetManagement : SleepyRoute
    @Serializable data object WidgetSettings : SleepyRoute
    @Serializable data object WidgetGallery : SleepyRoute
    @Serializable data class WidgetEdit(val widgetId: Int) : SleepyRoute
    @Serializable data object PeriodTables : SleepyRoute
    @Serializable data class PeriodEdit(val periodId: Long, val isNew: Boolean = false) : SleepyRoute

    companion object { const val NO_ID = -1L }
}
