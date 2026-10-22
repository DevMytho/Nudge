package com.dev.nudge.data

import com.dev.nudge.model.Task
import com.dev.nudge.model.normalized
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.ListenerRegistration
import java.util.UUID

object FirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun userId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User not logged in")
    }

    private fun tasksRef() = db.collection("users").document(userId()).collection("tasks")

    private fun completedRef() =
        db.collection("users").document(userId()).collection("completedTasks")

    // 🔥 SAVE TASK (doc id == task id so ids are stable & unique)
    suspend fun addTask(task: Task) {
        val id = task.id.ifBlank { UUID.randomUUID().toString() }
        tasksRef().document(id).set(task.copy(id = id)).await()
    }

    // 🔥 GET TASKS
    suspend fun getTasks(): List<Task> {
        val snapshot = tasksRef().get().await()
        return snapshot.documents.mapNotNull { fromDoc(it) }
    }

    // 🔥 DELETE TASK — by stable id (never by title)
    suspend fun deleteTask(task: Task) {
        if (task.id.isBlank()) return
        val snapshot = tasksRef()
            .whereEqualTo("id", task.id)
            .get()
            .await()

        snapshot.documents.forEach {
            it.reference.delete().await()
        }
    }

    // 🔥 MOVE TO COMPLETED
    suspend fun completeTask(task: Task) {
        val id = task.id.ifBlank { UUID.randomUUID().toString() }
        val stamped = task.copy(id = id)

        // add to completed (doc id == task id, so double-completes don't duplicate)
        completedRef().document(id).set(stamped).await()

        // delete from active
        deleteTask(stamped)
    }

    // 🔥 GET COMPLETED
    suspend fun getCompletedTasks(): List<Task> {
        val snapshot = completedRef().get().await()
        return snapshot.documents.mapNotNull { fromDoc(it) }
    }

    suspend fun deleteCompletedTask(task: Task) {
        if (task.id.isBlank()) return

        val snapshot = completedRef()
            .whereEqualTo("id", task.id)
            .get()
            .await()

        snapshot.documents.forEach {
            it.reference.delete().await()
        }
    }

    suspend fun updateTask(updatedTask: Task) {
        if (updatedTask.id.isBlank()) return

        val snapshot = tasksRef()
            .whereEqualTo("id", updatedTask.id)
            .get()
            .await()

        snapshot.documents.forEach {
            it.reference.set(updatedTask).await()
        }
    }

    // 🔥 LIVE UPDATES — snapshot listeners (no manual refresh needed)

    /**
     * 🔑 Shared doc → Task mapping:
     *  - fills a missing id from the doc name (legacy docs),
     *  - runs the pre-v3 field migration (ISO dates, dueTime, priority…).
     */
    private fun fromDoc(doc: com.google.firebase.firestore.DocumentSnapshot): Task? {
        val task = doc.toObject(Task::class.java) ?: return null
        val withId = if (task.id.isBlank()) task.copy(id = doc.id) else task
        if (task.id.isBlank()) {
            // best-effort write-back so the doc heals itself
            doc.reference.update("id", doc.id)
        }
        return withId.normalized()
    }

    fun listenTasks(onChanged: (List<Task>) -> Unit): ListenerRegistration =
        tasksRef().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            onChanged(snapshot.documents.mapNotNull { fromDoc(it) })
        }

    fun listenCompletedTasks(onChanged: (List<Task>) -> Unit): ListenerRegistration =
        completedRef().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            onChanged(snapshot.documents.mapNotNull { fromDoc(it) })
        }
}
