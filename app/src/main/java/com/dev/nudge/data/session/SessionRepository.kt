package com.dev.nudge.data.session

import android.content.Context
import com.dev.nudge.data.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 🗂 Session persistence: Room is the local source of truth for BOTH guests
 * and logged-in users (append- and query-heavy data). Logged-in sessions are
 * additionally pushed to Firestore `users/{uid}/sessions/{sessionId}` with
 * segments embedded as an array field (one-way push; see docs/DECISIONS.md).
 */
class SessionRepository(private val dao: SessionDao) {

    // ------------------------------------------------------------- writes

    suspend fun startSession(session: FocusSessionEntity) {
        dao.upsertSession(session)
        pushToFirestore(session)
    }

    suspend fun saveSegment(segment: SessionSegmentEntity) {
        dao.upsertSegment(segment)
        segment.sessionId.let { sessionId ->
            dao.sessionById(sessionId)?.let { pushToFirestore(it) }
        }
    }

    suspend fun updateSegment(segment: SessionSegmentEntity) {
        dao.updateSegment(segment)
    }

    suspend fun closeSession(session: FocusSessionEntity) {
        val closed = session.copy(endedAt = session.endedAt ?: System.currentTimeMillis())
        dao.updateSession(closed)
        pushToFirestore(closed)
    }

    suspend fun abandonSession(session: FocusSessionEntity, endedAt: Long) {
        dao.updateSession(
            session.copy(state = SessionState.ABANDONED.name, endedAt = endedAt)
        )
        dao.sessionById(session.id)?.let { pushToFirestore(it) }
    }

    /** 🧹 Used on logout — guest data wipe rule now covers Room too. */
    suspend fun clearLocal() {
        dao.clearSegments()
        dao.clearSessions()
    }

    // -------------------------------------------------------------- reads

    fun observeSessionsForDate(date: String): Flow<List<FocusSessionEntity>> =
        dao.observeSessionsForDate(date)

    fun observeSessionsBetween(from: String, to: String): Flow<List<FocusSessionEntity>> =
        dao.observeSessionsBetween(from, to)

    fun observeFocusSecondsByTask(): Flow<Map<String, Int>> =
        dao.observeFocusSecondsByTask().map { rows -> rows.associate { it.taskId to it.sec } }

    fun observeFocusSecondsByDate(): Flow<Map<String, Int>> =
        dao.observeFocusSecondsByDate().map { rows -> rows.associate { it.date to it.sec } }

    fun observeFocusSecondsByDateAndTask(): Flow<Map<String, Map<String, Int>>> =
        dao.observeFocusSecondsByDateAndTask().map { rows ->
            rows.groupBy({ it.date }, { it.taskId to it.sec })
                .mapValues { (_, pairs) -> pairs.toMap() }
        }

    fun observeSegmentsForSession(sessionId: String): Flow<List<SessionSegmentEntity>> =
        dao.observeSegmentsForSession(sessionId)

    fun observeSegmentsForDate(date: String): Flow<List<SessionSegmentEntity>> =
        dao.observeSegmentsForDate(date)

    suspend fun segmentsForSession(sessionId: String): List<SessionSegmentEntity> =
        dao.segmentsForSession(sessionId)

    suspend fun sessionById(id: String): FocusSessionEntity? = dao.sessionById(id)

    // ------------------------------------------------------- backup (§9)

    /** Everything needed for a backup export: sessions grouped with segments. */
    suspend fun allSessionsWithSegments(): List<Pair<FocusSessionEntity, List<SessionSegmentEntity>>> {
        val segments = dao.allSegments().groupBy { it.sessionId }
        return dao.allSessions().map { it to (segments[it.id] ?: emptyList()) }
    }

    suspend fun allSessionIds(): Set<String> = dao.allSessionIds().toSet()

    /**
     * 📥 Restore sessions from a backup: only ids the device doesn't have
     * are inserted (local wins). Best-effort Firestore push for signed-in
     * users. @return how many sessions were actually added.
     */
    suspend fun restoreSessions(backed: List<com.dev.nudge.data.backup.BackupSession>): Int {
        var added = 0
        for (b in backed) {
            if (dao.sessionById(b.session.id) != null) continue
            dao.upsertSession(b.session)
            b.segments.forEach { dao.upsertSegment(it) }
            added++
            pushToFirestore(b.session)
        }
        return added
    }

    // ---------------------------------------------------- Firestore push

    private suspend fun pushToFirestore(session: FocusSessionEntity) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            val segments = dao.segmentsForSession(session.id)
            FirestoreRepository.saveSession(uid, session, segments)
        } catch (e: Exception) {
            // offline is fine — Firestore retries when back online
            e.printStackTrace()
        }
    }

    companion object {
        @Volatile
        private var instance: SessionRepository? = null

        fun getInstance(context: Context): SessionRepository =
            instance ?: synchronized(this) {
                instance ?: SessionRepository(
                    NudgeDatabase.getInstance(context).sessionDao()
                ).also { instance = it }
            }
    }
}
