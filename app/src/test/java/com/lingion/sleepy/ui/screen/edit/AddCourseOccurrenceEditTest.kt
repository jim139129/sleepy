package com.lingion.sleepy.ui.screen.edit

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lingion.sleepy.R
import com.lingion.sleepy.SleepyApp
import com.lingion.sleepy.data.AppDatabase
import com.lingion.sleepy.data.entity.CourseEntity
import com.lingion.sleepy.data.entity.TimeTableEntity
import com.lingion.sleepy.data.repository.ScheduleRepository
import com.lingion.sleepy.data.undo.UndoManager
import com.lingion.sleepy.ui.screen.schedule.ScheduleViewModel
import com.lingion.sleepy.ui.theme.SleepyThemeProvider
import com.lingion.sleepy.util.AppPrefs
import com.lingion.sleepy.util.CourseOccurrence
import com.lingion.sleepy.util.HolidayTransferEntry
import com.lingion.sleepy.widget.notification.ReminderRescheduler
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * 浮框「调整本次」跳转完整编辑器(二级页面)后的行为契约: 编辑器直接以全屏表单打开这一次发生,
 * 周/星期/节次全可改, 星期为单选(移动语义), 保存只改这一次 —— 复用发生级管线
 * (without 摘出 + atDate 重建 + destinations 调休预览 + confirmConflicts 冲突确认 + saveOccurrence 事务)。
 *
 * 落库断言全部走真实 Room(内存库)与真实 repo 事务, 验证的是最终行状态而非内部调用。
 * 学期起点周一 2026-09-07; 目标发生 = 第 5 周周二 2026-10-06; 整组第 1-16 周仅此一行。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rUS-w360dp-h640dp-port-xhdpi", application = SleepyApp::class)
class AddCourseOccurrenceEditTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: SleepyApp
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private lateinit var model: ScheduleViewModel
    private var saved = false

    private val base = CourseEntity(id = 7, groupId = "g1", tableId = 1, courseName = "线性代数",
        day = 2, startNode = 3, step = 3, startWeek = 1, endWeek = 16, type = 0, color = "#FF6750A4")
    private val oct6 = LocalDate.of(2026, 10, 6)
    private val occurrence = CourseOccurrence(base, oct6, oct6)

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        SleepyApp::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ScheduleRepository(db)
        SleepyApp::class.java.getDeclaredField("repository\$delegate").apply { isAccessible = true }.set(app, lazyOf(repo))
        ReminderRescheduler.resetForTest()
        AppPrefs.setHolidayTransfers(app, 1, emptyList())
        runBlocking {
            db.timeTableDao().insert(TimeTableEntity(id = 1, name = "Table", startDate = "2026-09-07", isDefault = true))
            db.courseDao().insertKeepId(base)
        }
        compose.runOnIdle { model = ScheduleViewModel() }
        UndoManager.clear()
    }

    @After fun cleanup() {
        compose.runOnIdle { model.viewModelScope.cancel() }
        ReminderRescheduler.resetForTest()
        db.close()
        UndoManager.clear()
    }

    private fun showOccurrenceEditor() {
        compose.setContent {
            SleepyThemeProvider(darkTheme = false, themeKey = "default") {
                AddCourseScreen(onBack = {}, onSaved = { saved = true }, occurrence = occurrence, viewModel = model)
            }
        }
        waitFor { model.state.value.effectiveCurrentTable != null }
    }

    private fun waitFor(predicate: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            predicate()
        }
    }

    /** M3 TextField 合并语义: label 文本并入输入框节点, 先滚动到可见再输入。 */
    private fun editText(label: String, value: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        compose.onAllNodes(hasText(label)).onFirst().performTextInput(value)
    }

    private fun clickDay(name: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
        compose.onAllNodes(hasText(name)).onFirst().performClick()
    }

    private fun rows() = runBlocking { repo.getCourses(1) }

    private fun clickSave() {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(app.getString(R.string.save_course)))
        compose.onNodeWithText(app.getString(R.string.save_course)).performClick()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    // zh-rUS 限定符落到繁中资源(zh-rTW 优先于默认值), 显示文本一律从资源现取, 禁硬编码简体字面量。
    private fun dayName(index: Int) = app.resources.getStringArray(R.array.day_names)[index]
    private fun summaryText(dayIndex: Int) = dayName(dayIndex) + " · " +
        app.getString(R.string.slot_summary_week_single, 5) + " · " +
        app.getString(R.string.week_every)

    @Test fun openingAnOccurrenceGoesStraightToTheFullEditorLockedToItsWeekday() {
        showOccurrenceEditor()
        compose.onNodeWithText(app.getString(R.string.edit_course)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.meeting_slots)).assertIsDisplayed()
        scrollTo(summaryText(1)) // 周二 = day_names[1]
        compose.onNodeWithText(summaryText(1)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.slot_summary_period_range, 3, 5, 3)).assertIsDisplayed()
        // 单次模式禁组级动作: 滚到底后再断言缺失, 排除视口裁剪带来的假阴性
        scrollTo(app.getString(R.string.save_course))
        compose.onNodeWithText(app.getString(R.string.add_slot)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.delete_course)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.create_course_btn)).assertDoesNotExist()
        // 星期单选(移动语义): 点周三 = 这一次挪到周三, 不叠加成两个星期
        clickDay(dayName(2))
        scrollTo(summaryText(2)) // 视口还停在底部, 滚回卡顶再断言可见
        compose.onNodeWithText(summaryText(2)).assertIsDisplayed()
        compose.onNodeWithText(summaryText(1)).assertDoesNotExist()
    }

    @Test fun savingInOccurrenceModeRewritesOnlyThatWeekAndKeepsTheRestOfTheGroup() {
        showOccurrenceEditor()
        editText(app.getString(R.string.course_room), "A101")
        clickSave()
        waitFor { saved }
        val rows = rows()
        assertEquals(3, rows.size) // 前段 w1-4 / 本次 w5-5 / 后段 w6-16 —— 只拆出这一次
        val target = rows.single { it.startWeek == 5 && it.endWeek == 5 }
        assertEquals("A101", target.room)
        assertEquals(2, target.day)
        assertEquals(3, target.startNode)
        assertEquals(3, target.step)
        assertEquals("g1", target.groupId)
        assertEquals("线性代数", target.courseName)
        val before = rows.single { it.startWeek == 1 }
        assertEquals(7, before.id)     // without() 把原 id 留给前段, 事务不丢主键连续性
        assertEquals(4, before.endWeek)
        val after = rows.single { it.endWeek == 16 }
        assertEquals(6, after.startWeek)
        assertEquals("", after.room)   // 其余周原样: 不带走本次改动
    }

    @Test fun changingTheWeekdayMovesJustThatOccurrenceAndReanchorsTheDate() {
        showOccurrenceEditor()
        clickDay(dayName(3)) // 周四
        clickSave()
        waitFor { saved }
        val rows = rows()
        val moved = rows.single { it.day == 4 }
        assertEquals(5, moved.startWeek)
        assertEquals(5, moved.endWeek)
        assertTrue(rows.none { it.day == 2 && it.inWeek(5) })  // 周二第 5 周已不存在
        assertEquals(2, rows.count { it.day == 2 })            // w1-4 + w6-16 原样保留
    }

    @Test fun anOverlappingOccurrenceIsConfirmedAndVetoKeepsEveryRowUnchanged() {
        runBlocking { db.courseDao().insertKeepId(base.copy(id = 10, groupId = "g2", courseName = "体育课", startWeek = 5, endWeek = 5)) }
        showOccurrenceEditor()
        editText(app.getString(R.string.course_room), "B202")
        clickSave()
        compose.onNodeWithText(app.getString(R.string.conflict_detail_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.conflict_detail_go_back)).performClick()
        compose.runOnIdle {
            assertFalse(saved)
            assertEquals(2, rows().size) // 未落库: 原组行 + 体育课行都在
        }
        // 仍然保存 → 冲突放行, 依旧只拆这一次
        clickSave()
        compose.onNodeWithText(app.getString(R.string.conflict_detail_save_anyway)).performClick()
        waitFor { saved }
        val target = rows().single { it.startWeek == 5 && it.endWeek == 5 && it.groupId == "g1" }
        assertEquals("B202", target.room)
        assertEquals(1, rows().count { it.groupId == "g2" }) // 体育课整组原样
    }

    @Test fun aStaleTransferRosterRejectsTheSaveAndChangesNothing() {
        // 浮框投影之后调休表被改动: sourceDate 已不再是该 displayDate 的来源 → 拒绝陈旧落库
        AppPrefs.setHolidayTransfers(app, 1, listOf(HolidayTransferEntry(oct6, LocalDate.of(2026, 10, 7), "seg")))
        showOccurrenceEditor()
        editText(app.getString(R.string.course_room), "C303")
        clickSave()
        compose.onNodeWithText(app.getString(R.string.occurrence_transfer_changed)).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(saved)
            assertEquals(1, rows().size)
        }
    }
}
