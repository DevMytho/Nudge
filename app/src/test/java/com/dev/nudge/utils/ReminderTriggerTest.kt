package com.dev.nudge.utils

import com.dev.nudge.model.TaskUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 🔔 Regression guard for the dead-notifications bug: `scheduleTask` used to
 * parse Task.date as `dd-MM-yyyy` after dates migrated to ISO `yyyy-MM-dd`.
 * Lenient parsing turned "2026-10-25" into year 0025, the `> now` check
 * failed, and every reminder was silently dropped. `scheduleTask` now uses
 * [reminderTriggerMillis], so these cases cover the real code path.
 */
class ReminderTriggerTest {

    @Test
    fun `iso date in the future schedules a trigger`() {
        val expected = TaskUtils.parseDateTime("2099-01-15", "09:30")!!.time
        assertEquals(expected, reminderTriggerMillis("2099-01-15", "09:30", now = 0L))
    }

    @Test
    fun `a deadline already in the past schedules nothing`() {
        assertNull(reminderTriggerMillis("2000-01-15", "09:30"))
    }

    @Test
    fun `legacy dd-MM-yyyy dates still schedule (defense in depth)`() {
        val expected = TaskUtils.parseDateTime("2026-06-10", "10:30")!!.time
        assertEquals(expected, reminderTriggerMillis("10-06-2026", "10:30", now = 0L))
    }

    @Test
    fun `blank or garbage inputs schedule nothing`() {
        assertNull(reminderTriggerMillis("", "09:30"))
        assertNull(reminderTriggerMillis("2099-01-15", ""))
        assertNull(reminderTriggerMillis("not-a-date", "09:30"))
    }
}
