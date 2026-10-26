package com.lingion.sleepy.ui.screen.schedule

import android.content.Context
import com.lingion.sleepy.R
import com.lingion.sleepy.data.entity.CourseEntity
import com.lingion.sleepy.data.entity.TimeTableEntity
import com.lingion.sleepy.data.repository.ScheduleRepository
import com.lingion.sleepy.ui.screen.edit.OccurrenceSaveDialogs
import com.lingion.sleepy.util.AppPrefs
import com.lingion.sleepy.util.CourseOccurrences
import com.lingion.sleepy.util.DateUtils
import com.lingion.sleepy.util.TimeTableUtils
import kotlinx.coroutines.CancellationException

/** 浮起落库的用户动作意图 — 决定首确认标题(挪动 vs 调整持续节次)。 */
enum class FloatEditIntent { MOVE, SPAN_RESIZE }

/**
 * 浮起卡落库管线 — 网格 commitFloatDrop / 浮框删除后的单次课移动与删除。
 * 与 OccurrenceEditor.save 同一口径: 快照 → resolve 本次 → 编辑副本(换节次栅格)
 * → 拆原行剩余 → 冲突预览经 [OccurrenceSaveDialogs] 审批 → saveOccurrence 乐观并发落库。
 * snapshot/transfers 在挂起点之前一次读齐, 与 saveOccurrence 事务内的期望值同源 —
 * 中途被并发改动即 STALE, 绝不覆盖写库。同一屏同一表串行使用; 并发重入由
 * saveOccurrence 的乐观并发校验兜底(后到者读到的快照不等 → STALE), 不另设 busy 闸。
 */
internal class FloatOccurrenceHandler(
    private val context: Context,
    private val repo: ScheduleRepository,
    private val dialogs: OccurrenceSaveDialogs,
    private val table: TimeTableEntity,
) {
    enum class Outcome { SAVED, STALE, NOT_FOUND, CANCELED }

    /**
     * [projected] 只需 id 与投影星期有效(渲染期投影); day/node/step 是网格吸附后的目标格。
     * [week] = pager 页 + 1, 与渲染课程同一时间域。
     * [intent] 区分用户动作: 网格落点 = [FloatEditIntent.MOVE](换列或换节次都按「挪动」
     * 确认); 把手拉伸后经空白点击提交 = [FloatEditIntent.SPAN_RESIZE](按「调整持续节次」
     * 确认)。几何完全未变 → 不弹窗直存(前端本不该调到这里, 兜底放行)。
     * 口径与 OccurrenceEditor.save 一致: 本次按当前格日期 resolve; 换列(目标日期≠显示日期)
     * 走 destinations 调休审批, 同列只换节次则保留 sourceDate 直接改栅格。
     */
    suspend fun move(
        projected: CourseEntity,
        week: Int,
        day: Int,
        node: Int,
        step: Int,
        intent: FloatEditIntent = FloatEditIntent.MOVE,
    ): Outcome {
        try {
            val transfers = AppPrefs.getHolidayTransfers(context, table.id)
            val snapshot = repo.getCourses(table.id)
            val original = snapshot.firstOrNull { it.id == projected.id } ?: return Outcome.NOT_FOUND
            val displayDate = DateUtils.dateOfWeek(table.startDate, week, projected.day)
            val occurrence = CourseOccurrences.resolve(original, displayDate, transfers)
                ?: return Outcome.NOT_FOUND // 当前格当天被调休取消 — 无本次可移动
            val targetDate = DateUtils.dateOfWeek(table.startDate, week, day)
            val rows = TimeTableUtils.parseTimeSlotRows(table.timeJson)
            if (node < 1 || step < 1 || (node until node + step).any { n -> rows.none { it.node == n } })
                return Outcome.NOT_FOUND
            // 首确认(浮起管线 UX 契约): 几何与当前格完全一致 → 直存不弹窗(兜底);
            // 拉伸意图 → 「调整持续节次」; 挪动意图换列或换节次 → 「是否挪动这次课程」;
            // 挪动意图只改跨度(拖回本格但拉过伸) → 按「调整持续节次」。文案一条:
            // 课名 + 原格位 → 新格位。调休/冲突链仍在确认后串行。
            val geometryChanged = day != projected.day || node != original.startNode || step != original.step
            if (geometryChanged) {
                val spanConfirm = intent == FloatEditIntent.SPAN_RESIZE ||
                    (day == projected.day && node == original.startNode)
                val titleRes = if (spanConfirm) R.string.occurrence_span_confirm_title
                else R.string.occurrence_move_confirm_title
                if (dialogs.ask(
                        context.getString(titleRes),
                        context.getString(
                            R.string.occurrence_change_confirm_message,
                            original.courseName,
                            spanLabel(context, projected.day, original.startNode, original.step),
                            spanLabel(context, day, node, step),
                        ),
                        context.getString(R.string.ok),
                    ) == null
                ) return Outcome.CANCELED
            }
            // 时间模式沿用原课(ownTime/不规则时段课换格即换成目标节次栅格时间),
            // 与 OccurrenceEditor 的 custom 种子同口径 (ownTime || isIrregularTime)。
            val custom = original.ownTime || original.isIrregularTime
            val start = rows.firstOrNull { it.node == node }?.start.orEmpty()
            val end = rows.firstOrNull { it.node == node + step - 1 }?.end.orEmpty()
            val edited = original.copy(
                startNode = node, step = step,
                ownTime = custom, isIrregularTime = custom,
                isIrregularNode = rows.any { it.node == node && it.edgeClass != null },
                startTime = if (custom) start else "",
                endTime = if (custom) end else "",
            ).normalizeNode(table.timeJson)
            val replacement = if (targetDate == occurrence.displayDate) {
                CourseOccurrences.atDate(edited, occurrence.sourceDate, table, original.fixedDate != null)
            } else {
                dialogs.destinations(
                    listOf(CourseOccurrences.atDate(edited, targetDate, table, false)), table, transfers,
                )?.singleOrNull() ?: return Outcome.CANCELED
            }
            val remaining = CourseOccurrences.without(original, occurrence.sourceDate, table.startDate)
            val details = CourseOccurrences.conflictDetails(
                listOf(replacement), snapshot.filter { it.id != original.id } + remaining,
                table, transfers,
                context.resources.getStringArray(R.array.day_names),
                context.getString(R.string.conflict_detail_line),
            )
            if (!dialogs.confirmConflicts(details)) return Outcome.CANCELED
            return if (repo.saveOccurrence(occurrence, replacement, snapshot, table, transfers))
                Outcome.SAVED else Outcome.STALE
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) { return Outcome.STALE
        }
    }

    /** 确认文案的格位标签: 「周二 第4-5节」— 星期数组 + course_node_format, 全 locale 覆盖。 */
    private fun spanLabel(context: Context, day: Int, startNode: Int, step: Int): String =
        context.getString(R.string.course_node_format, "$startNode-${startNode + step - 1}")
            .let { "${DateUtils.localizedDay(day, context)} $it" }

    /** 删除本次 — 口径与 OccurrenceEditor 删除分支一致: 先确认弹窗, replacement=null 拆行落库。 */
    suspend fun delete(projected: CourseEntity, week: Int): Outcome {
        try {
            val transfers = AppPrefs.getHolidayTransfers(context, table.id)
            val snapshot = repo.getCourses(table.id)
            val original = snapshot.firstOrNull { it.id == projected.id } ?: return Outcome.NOT_FOUND
            val displayDate = DateUtils.dateOfWeek(table.startDate, week, projected.day)
            val occurrence = CourseOccurrences.resolve(original, displayDate, transfers)
                ?: return Outcome.NOT_FOUND
            if (dialogs.ask(
                context.getString(R.string.occurrence_delete_title),
                "${original.courseName}\n${occurrence.displayDate} · ${original.nodeString(context)}",
                context.getString(R.string.occurrence_delete), destructive = true,
            ) == null) return Outcome.CANCELED
            return if (repo.saveOccurrence(occurrence, null, snapshot, table, transfers))
                Outcome.SAVED else Outcome.STALE
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (e: Exception) { return Outcome.STALE
        }
    }
}
