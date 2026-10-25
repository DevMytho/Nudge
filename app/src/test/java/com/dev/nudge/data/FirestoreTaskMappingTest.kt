package com.dev.nudge.data

import com.dev.nudge.model.Task
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 🔥 Regression guard for the release crash:
 * "Found conflicting getters for name isCritical on class com.dev.nudge.model.Task".
 *
 * Firestore's CustomClassMapper builds a BeanMapper per class and throws in
 * its constructor when two getters resolve to the same document property
 * (getCritical() and the old isCritical() both mapped to "critical").
 * Loading the mapper here exercises that exact path without a device.
 */
class FirestoreTaskMappingTest {

    @Test
    fun `firestore bean mapper accepts Task with no conflicting getters`() {
        val mapperClass =
            Class.forName("com.google.firebase.firestore.util.CustomClassMapper")
        val load = mapperClass.getDeclaredMethod(
            "loadOrCreateBeanMapperForClass",
            Class::class.java
        )
        load.isAccessible = true

        // Throws RuntimeException("Found conflicting getters …") if Task's
        // getters ever collide again; returns the cached mapper otherwise.
        assertNotNull(load.invoke(null, Task::class.java))
    }
}
