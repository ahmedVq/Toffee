package dev.toffee.companion.kiosk

import dev.toffee.companion.kiosk.BrightnessPolicy.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * How bright the panel is.
 *
 * The spec's rule, and the reason there is no OFF: "The screen is never fully
 * off while powered, because presence detection and the arrival ambush both
 * depend on it being awake."
 */
class BrightnessPolicyTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun level(
        present: Boolean,
        absentMinutes: Long = 0,
        hour: Int = 14,
    ) = BrightnessPolicy.levelFor(
        presentSinceMs = if (present) now - 5 * minute else null,
        lastSeenMs = if (present) now else now - absentMinutes * minute,
        nowMs = now,
        localHour = hour,
    )

    @Test
    fun `at the desk in the daytime is full brightness`() {
        assertEquals(Level.FULL, level(present = true, hour = 14))
    }

    @Test
    fun `briefly away stays full`() {
        // Fetching coffee is not leaving. Dimming after thirty seconds would
        // make the thing feel like it is sulking every time he stands up.
        assertEquals(Level.FULL, level(present = false, absentMinutes = 3, hour = 14))
        assertEquals(Level.FULL, level(present = false, absentMinutes = 9, hour = 14))
    }

    @Test
    fun `away for ten minutes dims`() {
        assertEquals(Level.DIM, level(present = false, absentMinutes = 10, hour = 14))
        assertEquals(Level.DIM, level(present = false, absentMinutes = 120, hour = 14))
    }

    @Test
    fun `at the desk at two in the morning is dim, not full`() {
        // He is there, so it must be readable; it is 2am, so it must not be
        // the brightest thing in the room.
        assertEquals(Level.DIM, level(present = true, hour = 2))
    }

    @Test
    fun `away at night is the dimmest`() {
        assertEquals(Level.NIGHT, level(present = false, absentMinutes = 30, hour = 3))
    }

    @Test
    fun `the night window runs from 23 to 6 inclusive`() {
        for (hour in listOf(23, 0, 1, 2, 3, 4, 5, 6)) {
            assertEquals("hour $hour should be night", Level.DIM, level(present = true, hour = hour))
        }
        for (hour in listOf(7, 12, 18, 22)) {
            assertEquals("hour $hour should be day", Level.FULL, level(present = true, hour = hour))
        }
    }

    @Test
    fun `never seen at all is treated as away`() {
        assertEquals(
            Level.DIM,
            BrightnessPolicy.levelFor(null, null, now, localHour = 14),
        )
    }

    // Documented as a test because it is a design decision, not an omission.
    @Test
    fun `there is no off`() {
        assertFalse(
            "a level meaning off would break presence and the arrival ambush",
            Level.values().any { it.name.contains("OFF", ignoreCase = true) },
        )
        assertEquals(3, Level.values().size)
    }
}
