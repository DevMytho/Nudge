package com.dev.nudge.widget

import android.content.Context
import com.dev.nudge.data.SettingsStorage
import com.dev.nudge.data.TaskStorage
import com.dev.nudge.data.session.SessionRepository
import com.dev.nudge.model.StreakCalculator
import com.dev.nudge.model.StreakResult
import com.dev.nudge.model.Task
import com.dev.nudge.model.TaskUtils
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 📦 Everything the home-screen widgets display, loaded in one pass.
 *
 * Sources are the SAME stores the app itself reads:
 *  - tasks: DataStore ([TaskStorage]) — mirrored locally even for signed-in
 *    users, so widgets never touch the network;
 *  - focus seconds: Room ([SessionRepository]);
 *  - streak rule: identical to the Progress tab (focus ≥ streak minimum
 *    **or** a task completed that day).
 */
data class WidgetSnapshot(
    val streak: StreakResult,
    /** Today's tasks (active + completed-today), in Tasks-tab order. */
    val todayTasks: List<Task>,
    val todayDone: Int,
    val todayTotal: Int
)

object WidgetData {

    suspend fun load(context: Context): WidgetSnapshot {
        val settings = SettingsStorage.load(context)
        val active = TaskStorage.loadTasks(context)
        val completed = TaskStorage.loadCompletedTasks(context)
        val focusByDate = SessionRepository.getInstance(context)
            .observeFocusSecondsByDate()
            .first()

        val today = LocalDate.now()
        val todayIso = today.toString()

        // 🔥 same counted-days rule as ProgressScreen
        val focusDays = focusByDate.mapNotNull { (iso, sec) ->
            if (sec >= settings.streakMinMinutes * 60) {
                runCatching { LocalDate.parse(iso) }.getOrNull()
            } else null
        }.toSet()
        val streak = StreakCalculator.compute(
            focusDays + StreakCalculator.completionDays(completed, today),
            today
        )

        val activeToday = active.filter { it.date == todayIso }
        val doneToday = completed.filter { it.date == todayIso }

        return WidgetSnapshot(
            streak = streak,
            todayTasks = TaskUtils.sortTasks(activeToday + doneToday),
            todayDone = doneToday.size,
            todayTotal = activeToday.size + doneToday.size
        )
    }
}