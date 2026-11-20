package dev.toffee.companion.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry table, taken from `docs/design/face-style.html`, where the
 * neutral eye is 22 x 50 and each mood overrides it. Every ratio here is of
 * the eye's *width*, so the face scales to any screen without a second table.
 */
class MoodTest {

    @Test
    fun `neutral is a tall pill`() {
        val e = Mood.NEUTRAL.left()
        assertEquals(2.27f, e.heightRatio, 0.01f)
        assertEquals(0.5f, e.topRadiusRatio, 0.01f)
        assertEquals(0.5f, e.bottomRadiusRatio, 0.01f)
    }

    // docs/design/README.md: "Mood is shape plus colour, never colour alone.
    // Sad and sleepy are both cool-toned; what separates them is lid height
    // and droop." If two moods ever share a silhouette, the face has lost
    // half its vocabulary and nobody will notice until it looks wrong.
    @Test
    fun `every mood is distinguishable by shape alone`() {
        val silhouettes = Mood.values().map { it.left() to it.right() }
        assertEquals(
            "two moods share a silhouette, so colour alone would have to tell them apart",
            Mood.values().size,
            silhouettes.toSet().size,
        )
    }

    @Test
    fun `happy rounds the tops and flattens the bottoms`() {
        val e = Mood.HAPPY.left()
        assertEquals(1.18f, e.heightRatio, 0.01f)
        assertTrue(e.topRadiusRatio > e.bottomRadiusRatio)
        assertTrue("happy sits high", e.offsetYRatio < 0f)
    }

    @Test
    fun `sad inverts happy`() {
        val e = Mood.SAD.left()
        assertEquals(1.36f, e.heightRatio, 0.01f)
        assertTrue(e.bottomRadiusRatio > e.topRadiusRatio)
        assertTrue("sad sits low", e.offsetYRatio > 0f)
    }

    @Test
    fun `frustrated tilts the two eyes in opposite directions`() {
        assertEquals(14f, Mood.FRUSTRATED.left().rotationDeg, 0.01f)
        assertEquals(-14f, Mood.FRUSTRATED.right().rotationDeg, 0.01f)
    }

    @Test
    fun `smug raises one eye and not the other`() {
        assertTrue(Mood.SMUG.left().heightRatio > Mood.SMUG.right().heightRatio)
    }

    @Test
    fun `sleepy is a slit, dimmed`() {
        assertEquals(0.36f, Mood.SLEEPY.left().heightRatio, 0.01f)
        assertEquals(0.65f, Mood.SLEEPY.left().alpha, 0.01f)
    }

    @Test
    fun `only frustrated tilts`() {
        for (m in Mood.values()) {
            if (m == Mood.FRUSTRATED) continue
            assertEquals("$m must not be tilted", 0f, m.left().rotationDeg, 0.01f)
            assertEquals("$m must not be tilted", 0f, m.right().rotationDeg, 0.01f)
        }
    }

    // Observed in production during the backend phase: when the model is
    // unavailable the reply arrives with no mood at all. A face that throws
    // on that is a face that goes black exactly when something is wrong.
    @Test
    fun `an unknown or missing mood parses as NEUTRAL`() {
        assertEquals(Mood.NEUTRAL, Mood.parse(null))
        assertEquals(Mood.NEUTRAL, Mood.parse(""))
        assertEquals(Mood.NEUTRAL, Mood.parse("   "))
        assertEquals(Mood.NEUTRAL, Mood.parse("apoplectic"))
    }

    @Test
    fun `a known mood parses whatever its casing`() {
        assertEquals(Mood.SMUG, Mood.parse("smug"))
        assertEquals(Mood.SMUG, Mood.parse("SMUG"))
        assertEquals(Mood.SMUG, Mood.parse(" Smug "))
        assertEquals(Mood.FRUSTRATED, Mood.parse("frustrated"))
    }

    // The spec asks for "a visible, unambiguous listening state whenever the
    // mic is live". Taller than neutral, and the only mood that holds its
    // eyes open — motion alone would be ambiguous across a room.
    @Test
    fun `listening is unmistakable`() {
        assertTrue(Mood.LISTENING.left().heightRatio > Mood.NEUTRAL.left().heightRatio)
        assertFalse(Mood.LISTENING.blinks)
    }

    @Test
    fun `every other mood blinks`() {
        for (m in Mood.values()) {
            if (m == Mood.LISTENING) continue
            assertTrue("$m must blink", m.blinks)
        }
    }

    @Test
    fun `the agent's own mood vocabulary all parses`() {
        // set_mood in supabase/functions/_shared/tools/index.ts offers these.
        for (name in listOf("neutral", "happy", "sad", "frustrated", "loving", "smug", "sleepy")) {
            assertEquals(name, Mood.parse(name).name.lowercase())
        }
    }
}
