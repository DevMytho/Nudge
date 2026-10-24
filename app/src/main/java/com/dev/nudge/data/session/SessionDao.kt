package com.dev.nudge.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    // ------------------------------------------------------------- writes

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: FocusSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegment(segment: SessionSegmentEntity)

    @Update
    suspend fun updateSession(session: FocusSessionEntity)

    @Update
    suspend fun updateSegment(segment: SessionSegmentEntity)

    @Query("DELETE FROM focus_sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM session_segments")
    suspend fun clearSegments()

    // -------------------------------------------------------------- reads

    @Query("SELECT * FROM session_segments WHERE sessionId = :sessionId ORDER BY `index`")
    suspend fun segmentsForSession(sessionId: String): List<SessionSegmentEntity>

    @Query("SELECT * FROM session_segments WHERE sessionId = :sessionId ORDER BY `index`")
    fun observeSegmentsForSession(sessionId: String): Flow<List<SessionSegmentEntity>>

    /** All segments of one local day (day detail sheet / session timeline). */
    @Query(
        "SELECT s.* FROM session_segments s " +
            "INNER JOIN focus_sessions ses ON ses.id = s.sessionId " +
            "WHERE ses.date = :date ORDER BY ses.startedAt, s.sessionId, s.`index`"
    )
    fun observeSegmentsForDate(date: String): Flow<List<SessionSegmentEntity>>

    @Query("SELECT * FROM focus_sessions WHERE date = :date ORDER BY startedAt DESC")
    fun observeSessionsForDate(date: String): Flow<List<FocusSessionEntity>>

    @Query(
        "SELECT * FROM focus_sessions " +
            "WHERE date >= :from AND date <= :to ORDER BY startedAt DESC"
    )
    fun observeSessionsBetween(from: String, to: String): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC")
    fun observeAllSessions(): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun sessionById(id: String): FocusSessionEntity?

    // ----------------------------------------------------------- aggregates

    /** taskTitleSnapshot keeps deleted-task history readable. */
    @Query(
        "SELECT ses.taskTitleSnapshot AS title, SUM(s.actualSec) AS sec " +
            "FROM session_segments s " +
            "INNER JOIN focus_sessions ses ON ses.id = s.sessionId " +
            "WHERE s.type = 'FOCUS' AND s.taskId IS NOT NULL " +
            "GROUP BY s.taskId"
    )
    fun observeFocusSecondsByTaskTitle(): Flow<List<TitleSeconds>>

    @Query(
        "SELECT s.taskId AS taskId, SUM(s.actualSec) AS sec " +
            "FROM session_segments s " +
            "WHERE s.type = 'FOCUS' AND s.taskId IS NOT NULL " +
            "GROUP BY s.taskId"
    )
    fun observeFocusSecondsByTask(): Flow<List<TaskIdSeconds>>

    /** Focused seconds per local day (for the growth chart / calendar). */
    @Query(
        "SELECT ses.date AS date, SUM(s.actualSec) AS sec " +
            "FROM session_segments s " +
            "INNER JOIN focus_sessions ses ON ses.id = s.sessionId " +
            "WHERE s.type = 'FOCUS' " +
            "GROUP BY ses.date"
    )
    fun observeFocusSecondsByDate(): Flow<List<DateSeconds>>

    /** Estimated-accuracy needs per-task actuals within a date range. */
    @Query(
        "SELECT ses.date AS date, s.taskId AS taskId, SUM(s.actualSec) AS sec " +
            "FROM session_segments s " +
            "INNER JOIN focus_sessions ses ON ses.id = s.sessionId " +
            "WHERE s.type = 'FOCUS' AND s.taskId IS NOT NULL " +
            "GROUP BY ses.date, s.taskId"
    )
    fun observeFocusSecondsByDateAndTask(): Flow<List<DateTaskSeconds>>

    @Query(
        "SELECT SUM(s.actualSec) " +
            "FROM session_segments s " +
            "INNER JOIN focus_sessions ses ON ses.id = s.sessionId " +
            "WHERE s.type = 'FOCUS' AND ses.date = :date"
    )
    fun observeFocusSecondsForDate(date: String): Flow<Int?>

    @Query(
        "SELECT SUM(s.actualSec) " +
            "FROM session_segments s " +
            "INNER JOIN focus_sessions ses ON ses.id = s.sessionId " +
            "WHERE s.type = 'FOCUS' AND ses.taskId = :taskId"
    )
    fun observeFocusSecondsForTask(taskId: String): Flow<Int?>
}

// --------------------------------------------------------- projection rows

data class TitleSeconds(val title: String, val sec: Int)

data class TaskIdSeconds(val taskId: String, val sec: Int)

data class DateSeconds(val date: String, val sec: Int)

data class DateTaskSeconds(val date: String, val taskId: String, val sec: Int)
