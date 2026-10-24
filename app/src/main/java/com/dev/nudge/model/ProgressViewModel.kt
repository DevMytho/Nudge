package com.dev.nudge.model

import android.content.Context
import com.dev.nudge.data.SettingsStorage
import com.dev.nudge.data.UserSettings
import com.dev.nudge.data.session.SessionRepository
import com.dev.nudge.data.session.SessionSegmentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 📈 Progress tab state (spec §8): focused seconds per day, settings used
 * by the charts/streak (daily goal, streak threshold, week start) and the
 * per-day segment flow behind the calendar day-detail sheet.
 *
 * Pure logic lives in [StatsCalculator] / [StreakCalculator]; this class
 * only exposes flows.
 */
class ProgressViewModel(
    context: Context,
    private val sessions: SessionRepository = SessionRepository.getInstance(context)
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** ISO date → focused seconds (all history; drives charts + calendar). */
    val focusSecondsByDate: StateFlow<Map<String, Int>> =
        sessions.observeFocusSecondsByDate()
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings

    init {
        scope.launch {
            _settings.value = SettingsStorage.load(context)
        }
    }

    /** Segments of one local day, ordered by session start (day detail). */
    fun segmentsForDate(date: String): Flow<List<SessionSegmentEntity>> =
        sessions.observeSegmentsForDate(date)

    fun close() {
        scope.cancel()
    }
}