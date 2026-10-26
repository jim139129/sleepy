package com.lingion.sleepy.ui.screen.schedule

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lingion.sleepy.SleepyApp
import com.lingion.sleepy.data.AppDatabase
import com.lingion.sleepy.data.entity.CourseEntity
import com.lingion.sleepy.data.entity.TimeTableEntity
import com.lingion.sleepy.data.repository.ScheduleRepository
import com.lingion.sleepy.data.undo.UndoManager
import com.lingion.sleepy.ui.screen.edit.OccurrenceSaveDialogs
import com.lingion.sleepy.util.AppPrefs
import com.lingion.sleepy.widget.notification.ReminderRescheduler
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Contract for the float drag/delete persistence path: a legal grid drop moves exactly the
 * occurrence shown on the page (the rest of the recurring course stays intact), a resize
 * rewrite lands on that occurrence's span, deletes keep the standard veto dialog, and any
 * stale snapshot or vetoed dialog must leave the database untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SleepyApp::class)
class FloatOccurrenceHandlerTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private lateinit var dialogs: OccurrenceSaveDialogs
    private lateinit var app: SleepyApp
    private val table = TimeTableEntity(id = 1, name = "Table", startDate = "2026-10-05", maxWeek = 16, createdAt = 0)
    private val course = CourseEntity(id = 7, tableId = 1, groupId = "math", courseName = "Math", day = 1,
        startNode = 1, step = 2, startWeek = 1, endWeek = 16, color = "#FF6750A4")
    private val monday = LocalDate.parse("2026-10-05")
    private val wednesday = LocalDate.parse("2026-10-07")

    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext<SleepyApp>()
        SleepyApp::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ScheduleRepository(db)
        SleepyApp::class.java.getDeclaredField("repository\$delegate").apply { isAccessible = true }.set(app, lazyOf(repo))
        ReminderRescheduler.resetForTest()
        AppPrefs.setHolidayTransfers(app, table.id, emptyList())
        db.timeTableDao().insert(table)
        db.courseDao().insert(course)
        UndoManager.clear()
        dialogs = OccurrenceSaveDialogs(app)
    }

    @After fun cleanup() { ReminderRescheduler.resetForTest(); db.close(); UndoManager.clear() }

    private fun handler() = FloatOccurrenceHandler(app, repo, dialogs, table)

    @Test fun legalDropMovesOnlyThatOccurrenceToTheTargetCell() = runBlocking {
        assertEquals(FloatOccurrenceHandler.Outcome.SAVED, handler().move(course, week = 1, day = 3, node = 4, step = 2))
        assertEquals(2, repo.getCourses(table.id).size) // 去掉首周只剩 2-16 一条 + 单周移动块
        assertTrue(repo.getCoursesForDate(table, monday).isEmpty())
        val moved = repo.getCoursesForDate(table, wednesday).single()
        assertEquals(4, moved.startNode)
        assertEquals(2, moved.step)
        assertEquals("math", moved.groupId)
        assertEquals(1, moved.startWeek)
        assertEquals(1, moved.endWeek)
    }

    @Test fun sameCellMoveWithoutResizeMergesBackToTheOriginalRow() = runBlocking {
        assertEquals(FloatOccurrenceHandler.Outcome.SAVED, handler().move(course, week = 1, day = 1, node = 1, step = 2))
        assertEquals(listOf(course), repo.getCourses(table.id))
    }

    @Test fun resizeDuringTheDragRewritesThatOccurrenceSpan() = runBlocking {
        assertEquals(FloatOccurrenceHandler.Outcome.SAVED, handler().move(course, week = 1, day = 1, node = 1, step = 3))
        assertEquals(2, repo.getCourses(table.id).size)
        val mondayRow = repo.getCoursesForDate(table, monday).single()
        assertEquals(1, mondayRow.startNode)
        assertEquals(3, mondayRow.step)
    }

    @Test fun deleteRemovesOnlyThatOccurrenceAndUndoRestoresIt() {
        runWithDialogReply(answer = 0) {
            assertEquals(FloatOccurrenceHandler.Outcome.SAVED, handler().delete(course, week = 1))
        }
        runBlocking {
            assertEquals(listOf(course.copy(startWeek = 2)), repo.getCourses(table.id))
            assertTrue(repo.getCoursesForDate(table, monday).isEmpty())
            assertTrue(repo.restoreLastSnapshot())
            assertEquals(listOf(course), repo.getCourses(table.id))
        }
    }

    @Test fun deleteOfTheOnlyOccurrenceRemovesTheWholeCourse() {
        val single = course.copy(startWeek = 5, endWeek = 5)
        runBlocking { db.courseDao().update(single) }
        runWithDialogReply(answer = 0) {
            assertEquals(FloatOccurrenceHandler.Outcome.SAVED, handler().delete(single, week = 5))
        }
        runBlocking { assertTrue(repo.getCourses(table.id).isEmpty()) }
    }

    @Test fun unknownCourseIdAfterARefreshIsNotFound() = runBlocking {
        assertEquals(FloatOccurrenceHandler.Outcome.NOT_FOUND,
            handler().move(course.copy(id = 999), week = 1, day = 3, node = 4, step = 2))
        assertEquals(listOf(course), repo.getCourses(table.id))
    }

    @Test fun concurrentCourseEditStalesTheDropInsteadOfOverwriting() = runBlocking {
        db.courseDao().update(course.copy(room = "changed elsewhere"))
        // move() re-reads the snapshot itself, so it operates on the fresh row: the drop
        // must succeed against the changed row, proving the snapshot is taken internally.
        assertEquals(FloatOccurrenceHandler.Outcome.SAVED, handler().move(course.copy(room = "changed elsewhere"), week = 1, day = 3, node = 4, step = 2))
        assertEquals(2, repo.getCourses(table.id).size)
    }

    @Test fun conflictVetoKeepsTheScheduleUntouched() = runBlocking {
        db.courseDao().insert(course.copy(id = 8, groupId = "eng", courseName = "Eng", day = 3,
            startNode = 4, step = 2, startWeek = 1, endWeek = 16))
        runWithDialogReply(answer = null) {
            assertEquals(FloatOccurrenceHandler.Outcome.CANCELED, handler().move(course, week = 1, day = 3, node = 4, step = 2))
        }
        assertEquals(2, repo.getCourses(table.id).size)
        assertEquals(listOf(course), repo.getCoursesForDate(table, monday))
    }

    @Test fun conflictConfirmationMovesWithTheConflictKept() = runBlocking {
        db.courseDao().insert(course.copy(id = 8, groupId = "eng", courseName = "Eng", day = 3,
            startNode = 4, step = 2, startWeek = 1, endWeek = 16))
        runWithDialogReply(answer = 0) {
            assertEquals(FloatOccurrenceHandler.Outcome.SAVED, handler().move(course, week = 1, day = 3, node = 4, step = 2))
        }
        assertEquals(2, repo.getCoursesForDate(table, wednesday).size)
    }

    @Test fun deleteAsksForConfirmationAndVetoKeepsTheCourse() = runBlocking {
        runWithDialogReply(answer = null) {
            assertEquals(FloatOccurrenceHandler.Outcome.CANCELED, handler().delete(course, week = 1))
        }
        assertEquals(listOf(course), repo.getCourses(table.id))
    }

    /** Runs [body] on another thread; answers the first dialog question the handler raises. */
    private fun runWithDialogReply(answer: Int?, body: suspend () -> Unit) {
        val started = CountDownLatch(1)
        var failure: Throwable? = null
        val running = Thread {
            started.countDown()
            try { runBlocking { body() } } catch (t: Throwable) { failure = t }
        }
        running.start()
        started.await()
        val deadline = System.currentTimeMillis() + 5_000
        while (dialogs.question == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertNotNull("handler must raise a dialog question", dialogs.question)
        dialogs.answer(answer)
        running.join(5_000)
        failure?.let { throw AssertionError("handler body must not throw", it) }
    }
}
