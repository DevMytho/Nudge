package com.dev.nudge.data

import com.dev.nudge.model.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object FirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun userId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User not logged in")
    }

    // 🔥 SAVE TASK
    suspend fun addTask(task: Task) {
        db.collection("users")
            .document(userId())
            .collection("tasks")
            .add(task)
            .await()
        println("🔥 ADDING TASK FOR USER: ${userId()}")
    }

    // 🔥 GET TASKS
    suspend fun getTasks(): List<Task> {
        val snapshot = db.collection("users")
            .document(userId())
            .collection("tasks")
            .get()
            .await()
        println("🔥 FETCHING TASKS FOR USER: ${userId()}")

        return snapshot.toObjects(Task::class.java)
    }

    // 🔥 DELETE TASK (by title for now)
    suspend fun deleteTask(task: Task) {
        val snapshot = db.collection("users")
            .document(userId())
            .collection("tasks")
            .whereEqualTo("title", task.title)
            .get()
            .await()

        snapshot.documents.forEach {
            it.reference.delete()
        }
    }

    // 🔥 MOVE TO COMPLETED
    suspend fun completeTask(task: Task) {

        val uid = userId()

        // add to completed
        db.collection("users")
            .document(uid)
            .collection("completedTasks")
            .add(task)
            .await()

        // delete from active
        deleteTask(task)
    }

    // 🔥 GET COMPLETED
    suspend fun getCompletedTasks(): List<Task> {
        val snapshot = db.collection("users")
            .document(userId())
            .collection("completedTasks")
            .get()
            .await()

        return snapshot.toObjects(Task::class.java)
    }

    suspend fun deleteCompletedTask(task: Task) {

        val uid = userId() ?: return

        val snapshot = db.collection("users")
            .document(uid)
            .collection("completedTasks")
            .whereEqualTo("title", task.title)
            .whereEqualTo("time", task.time)
            .whereEqualTo("date", task.date)
            .get()
            .await()

        snapshot.documents.forEach {
            it.reference.delete()
        }
    }

    suspend fun updateTask(updatedTask: Task) {

        val snapshot = db.collection("users")
            .document(userId())
            .collection("tasks")
            .whereEqualTo("id", updatedTask.id)
            .get()
            .await()

        snapshot.documents.forEach {
            it.reference.set(updatedTask).await()
        }
    }
}