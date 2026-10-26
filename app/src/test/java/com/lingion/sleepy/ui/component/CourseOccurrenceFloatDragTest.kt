package com.lingion.sleepy.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.data.entity.CourseEntity
import com.lingion.sleepy.ui.theme.SleepyThemeProvider
import com.lingion.sleepy.util.TimeTableUtils
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Contract for the long-press float interaction: the card lifts above the grid with a blurred
 * backdrop and a popup (edit / delete), may be resized by its edges, then drags to a new cell
 * where the drop moves exactly that occurrence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rUS-w360dp-h640dp-port-xhdpi")
class CourseOccurrenceFloatDragTest {
    @get:Rule val compose = createComposeRule()
    private val course = CourseEntity(id = 7, tableId = 1, groupId = "math", courseName = "FloatMath",
        day = 1, startNode = 1, step = 2, startWeek = 1, endWeek = 16, color = "#FF6750A4")

    private val slots = TimeTableUtils.timeSlotsFor(TimeTableUtils.DEFAULT_TIME_JSON)
    private val moves = mutableListOf<Move>()
    private val deleted = mutableListOf<Long>()
    private val longPressed = mutableListOf<Long>()
    private var clicks = 0

    data class Move(val id: Long, val day: Int, val node: Int, val step: Int)

    private fun grid(
        state: CourseFloatState = CourseFloatState(),
        onMove: ((CourseEntity, Int, Int, Int) -> Unit)? = { c, d, n, s -> moves.add(Move(c.id, d, n, s)) },
        onDelete: ((CourseEntity) -> Unit)? = { deleted.add(it.id) },
        onLongPress: (CourseEntity) -> Unit = { longPressed.add(it.id) },
    ) {
        compose.setContent {
            SleepyThemeProvider(darkTheme = false, themeKey = "default") {
                Box(Modifier.size(360.dp, 640.dp)) {
                    CardsGridView(courses = listOf(course), timeSlots = slots,
                        floatState = state,
                        onOccurrenceMove = onMove, onOccurrenceDelete = onDelete,
                        onCourseClick = { clicks++ }, onCourseLongClick = onLongPress)
                }
            }
        }
    }

    @Test fun longPressWithFloatEnabledLiftsTheCardWithPopupAndKeepsTheEditorClosed() {
        grid()
        compose.onNodeWithText("FloatMath").performTouchInput { longClick() }
        compose.onNodeWithTag("float-backdrop").assertIsDisplayed()
        compose.onNodeWithTag("float-popup").assertIsDisplayed()
        compose.onNodeWithTag("float-edit").assertIsDisplayed()
        compose.onNodeWithTag("float-delete").assertIsDisplayed()
        compose.onNodeWithTag("float-card").assertIsDisplayed()
        compose.runOnIdle { assertTrue(longPressed.isEmpty()); assertEquals(0, clicks); assertTrue(moves.isEmpty()) }
    }

    @Test fun longPressWithoutMoveCallbackKeepsTheLegacyEditorShortcut() {
        grid(onMove = null)
        compose.onNodeWithText("FloatMath").performTouchInput { longClick() }
        compose.onNodeWithTag("float-popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(7L), longPressed) }
    }

    @Test fun popupEditOpensTheOccurrenceEditorAndDismissesTheFloat() {
        grid()
        compose.onNodeWithText("FloatMath").performTouchInput { longClick() }
        compose.onNodeWithTag("float-edit").performClick()
        compose.runOnIdle { assertEquals(listOf(7L), longPressed); assertTrue(deleted.isEmpty()) }
        compose.onNodeWithTag("float-popup").assertDoesNotExist()
        compose.onNodeWithTag("float-card").assertDoesNotExist()
        compose.onNodeWithTag("float-backdrop").assertDoesNotExist()
    }

    @Test fun popupDeleteAsksForThatOccurrenceAndDismissesTheFloat() {
        grid()
        compose.onNodeWithText("FloatMath").performTouchInput { longClick() }
        compose.onNodeWithTag("float-delete").performClick()
        compose.runOnIdle { assertEquals(listOf(7L), deleted); assertTrue(longPressed.isEmpty()) }
        compose.onNodeWithTag("float-popup").assertDoesNotExist()
        compose.onNodeWithTag("float-card").assertDoesNotExist()
    }

    @Test fun pressingTheBackdropAnywhereElseDismissesTheFloatWithoutSideEffects() {
        grid()
        compose.onNodeWithText("FloatMath").performTouchInput { longClick() }
        compose.onNodeWithTag("add-course-3-2").performTouchInput { click() }
        compose.onNodeWithTag("float-popup").assertDoesNotExist()
        compose.onNodeWithTag("float-card").assertDoesNotExist()
        compose.onNodeWithTag("quick-add-capsule").assertDoesNotExist()
        compose.runOnIdle { assertTrue(moves.isEmpty()); assertTrue(longPressed.isEmpty()); assertEquals(0, clicks) }
    }

    @Test fun draggingTheFloatingCardHidesThePopupAndALegalDropMovesTheOccurrence() {
        val state = CourseFloatState()
        grid(state)
        compose.onNodeWithText("FloatMath").performTouchInput { longClick() }
        val card = compose.onNodeWithTag("float-card").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("add-course-3-4").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("float-card").performTouchInput {
            // down() 用节点局部坐标(scope.center); 位移量才是根坐标差
            down(center)
            moveBy(Offset(target.center.x - card.center.x, target.top - card.top), 300)
        }
        assertTrue(state.dragging)
        compose.onNodeWithTag("float-card").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(Move(7L, 3, 4, 2)), moves)
            assertNull(state.course)
        }
        compose.onNodeWithTag("float-popup").assertDoesNotExist()
    }

    @Test fun resizingTheFloatingCardThenDroppingMovesTheExpandedSpan() {
        val state = CourseFloatState()
        grid(state)
        compose.onNodeWithText("FloatMath").performTouchInput { longClick() }
        val rowHeight = compose.onNodeWithTag("add-course-1-3").fetchSemanticsNode().boundsInRoot.top -
            compose.onNodeWithTag("add-course-1-2").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("float-bottom", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(0f, rowHeight), 300)
        }
        compose.runOnIdle {
            assertEquals(3, state.endNode)
            assertFalse(state.dragging)
            assertTrue(moves.isEmpty())
        }
        val card = compose.onNodeWithTag("float-card").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("add-course-3-1").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("float-card").performTouchInput {
            down(center)
            moveBy(Offset(target.center.x - card.center.x, target.top - card.top), 300)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(Move(7L, 3, 1, 3)), moves) }
    }

    @Test fun droppingOutsideTheGridSpringsBackAndCancelsTheMove() {
        val state = CourseFloatState()
        grid(state)
        compose.onNodeWithText("FloatMath").performTouchInput { longClick() }
        val card = compose.onNodeWithTag("float-card").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("float-card").performTouchInput {
            down(center)
            moveBy(Offset(700f, 150f), 300)
            up()
        }
        compose.runOnIdle {
            assertTrue(moves.isEmpty())
            assertFalse(state.dragging)
            assertNotNull(state.course)
        }
        compose.onNodeWithTag("float-popup").assertIsDisplayed()
        assertEquals(card.top, compose.onNodeWithTag("float-card").fetchSemanticsNode().boundsInRoot.top, 0.5f)
    }
}
