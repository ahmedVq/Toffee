package dev.toffee.companion.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is at the desk, decided from a stream of face counts.
 *
 * The camera is not the hard part; this is. Every transition here costs an
 * LLM call and an interruption, so a detector that flaps — and they all flap
 * — turns a companion into a nuisance within an afternoon. The thresholds
 * are deliberately asymmetric: arriving must be sustained, leaving must be
 * long.
 */
class PresenceTest {

    private fun arrive(p: Presence, at: Long = 0L): Presence.Change? {
        p.sample(1, at)
        return p.sample(1, at + Presence.ARRIVE_AFTER_MS)
    }

    @Test
    fun `one glimpse is not an arrival`() {
        assertNull("someone walking past is not someone sitting down", Presence().sample(1, 0))
    }

    @Test
    fun `sustained faces are an arrival exactly once`() {
        val p = Presence()
        assertNull(p.sample(1, 0))
        assertEquals(Presence.Change.Arrived, p.sample(1, Presence.ARRIVE_AFTER_MS))
        assertNull("it must not greet him twice", p.sample(1, Presence.ARRIVE_AFTER_MS + 4_000))
        assertTrue(p.present)
    }

    // Review Focus 4. He turns to the other monitor, looks down at the
    // keyboard, leans back out of frame. None of that is leaving.
    @Test
    fun `leaning out of frame is not leaving`() {
        val p = Presence()
        arrive(p)
        val t = Presence.ARRIVE_AFTER_MS

        assertNull(p.sample(0, t + 4_000))
        assertNull(p.sample(0, t + 8_000))
        assertNull(p.sample(1, t + 12_000))
        assertTrue("a few seconds out of frame is not an absence", p.present)
    }

    @Test
    fun `one empty frame does not reset how long he has been there`() {
        val p = Presence()
        arrive(p)
        val since = p.presentSinceMs

        p.sample(0, Presence.ARRIVE_AFTER_MS + 4_000)
        p.sample(1, Presence.ARRIVE_AFTER_MS + 8_000)

        assertEquals("the clock on his sitting does not restart", since, p.presentSinceMs)
    }

    @Test
    fun `a long absence is leaving`() {
        val p = Presence()
        arrive(p)
        val gone = Presence.ARRIVE_AFTER_MS

        assertNull(p.sample(0, gone + Presence.LEAVE_AFTER_MS - 1_000))
        val change = p.sample(0, gone + Presence.LEAVE_AFTER_MS)
        assertTrue("he really has gone: $change", change is Presence.Change.Left)
        assertFalse(p.present)
    }

    @Test
    fun `leaving is announced once, not every frame after`() {
        val p = Presence()
        arrive(p)
        val gone = Presence.ARRIVE_AFTER_MS

        p.sample(0, gone + Presence.LEAVE_AFTER_MS)
        assertNull(p.sample(0, gone + Presence.LEAVE_AFTER_MS + 60_000))
        assertNull(p.sample(0, gone + Presence.LEAVE_AFTER_MS + 600_000))
    }

    @Test
    fun `leaving says how long he had been sitting`() {
        val p = Presence()
        p.sample(1, 0)
        p.sample(1, Presence.ARRIVE_AFTER_MS)
        // Seen for thirty minutes, then gone.
        p.sample(1, 1_800_000)
        val change = p.sample(0, 1_800_000 + Presence.LEAVE_AFTER_MS)

        assertEquals(Presence.Change.Left(30), change)
    }

    @Test
    fun `an hour at the desk is worth mentioning`() {
        val p = Presence()
        arrive(p)

        assertEquals(Presence.Change.StillHere(60), p.sample(1, 3_600_000))
        assertNull("once an hour, not once a sample", p.sample(1, 3_600_100))
        assertEquals(Presence.Change.StillHere(120), p.sample(1, 7_200_000))
    }

    @Test
    fun `leaving and coming back arrives again`() {
        val p = Presence()
        arrive(p)
        val gone = Presence.ARRIVE_AFTER_MS + Presence.LEAVE_AFTER_MS
        p.sample(0, gone)
        assertFalse(p.present)

        p.sample(1, gone + 600_000)
        assertEquals(Presence.Change.Arrived, p.sample(1, gone + 600_000 + Presence.ARRIVE_AFTER_MS))
        assertEquals(
            "the second sitting is its own sitting",
            gone + 600_000,
            p.presentSinceMs,
        )
    }

    // What BrightnessPolicy needs, and it must survive him leaving: the panel
    // dims on how long he has been gone, which is unknowable if the last
    // sighting is forgotten the moment he goes.
    @Test
    fun `the last sighting outlives the departure`() {
        val p = Presence()
        arrive(p)
        val seen = Presence.ARRIVE_AFTER_MS
        p.sample(0, seen + Presence.LEAVE_AFTER_MS)

        assertEquals(seen, p.lastSeenMs)
        assertNull("nobody is sitting there", p.presentSinceMs)
    }

    @Test
    fun `an empty room reports nothing at all`() {
        val p = Presence()
        for (t in 0..20) assertNull(p.sample(0, t * 4_000L))
        assertFalse(p.present)
        assertNull(p.lastSeenMs)
    }

    @Test
    fun `two faces are still one presence`() {
        val p = Presence()
        p.sample(2, 0)
        assertEquals(
            "presence is a boolean, never an identity or a count",
            Presence.Change.Arrived,
            p.sample(2, Presence.ARRIVE_AFTER_MS),
        )
    }

    // Found in review. Arrival had no gap tolerance at all: a single missed
    // sample restarted the run. At one sample every four seconds and FAST
    // detection on a 2016 front camera at 320x240, missing one frame while
    // he pulls his chair in is ordinary, and it meant he was greeted minutes
    // late or never.
    @Test
    fun `a dropped frame while he sits down does not restart the arrival`() {
        val p = Presence()
        assertNull(p.sample(1, 0))
        assertNull("one missed detection is not an empty room", p.sample(0, 4_000))
        assertEquals(Presence.Change.Arrived, p.sample(1, 8_000))
    }

    @Test
    fun `a real gap does restart the arrival`() {
        val p = Presence()
        p.sample(1, 0)
        p.sample(0, Presence.ARRIVE_GAP_MS + 1_000)
        p.sample(1, Presence.ARRIVE_GAP_MS + 5_000)
        assertNull(
            "someone crossing the room twice is still not someone sitting down",
            p.sample(1, Presence.ARRIVE_GAP_MS + 7_000),
        )
    }

    // The rule the thresholds exist for, asserted directly rather than left
    // implicit in the arithmetic of the other tests.
    @Test
    fun `leaving is slower to believe than arriving`() {
        assertTrue(
            "a false departure costs two LLM calls and two interruptions",
            Presence.LEAVE_AFTER_MS > Presence.ARRIVE_AFTER_MS,
        )
    }
}
