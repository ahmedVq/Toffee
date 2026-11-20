package dev.toffee.companion.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The approved background rule, from `docs/design/face-background.html`
 * option B: "the field behind the eyes is the opposite hue at 6-10%
 * lightness".
 *
 * Tested as a rule, not as a lookup table, because it is implemented as one.
 * A table would drift the first time a mood is added.
 */
class PaletteTest {

    // android.graphics.Color is stubbed in JVM unit tests, so the conversion
    // lives here rather than calling Color.colorToHSV.
    private fun hsl(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val l = (mx + mn) / 2f
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        val s = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
        return Triple((h + 360f) % 360f, s, l)
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    @Test
    fun `the background is the complement of the eyes`() {
        for (m in Mood.values()) {
            val eye = hsl(Palette.eyeColor(m))
            val bg = hsl(Palette.backgroundColor(m))
            assertEquals(
                "$m: the field must be the opposite hue",
                180f,
                hueDistance(eye.first, bg.first),
                3f,
            )
        }
    }

    // "All three sit between 6% and 10% lightness, so none of them will light
    // up a dark room at 2am." The tablet is on a desk, awake, all night.
    @Test
    fun `the background never lights up a dark room`() {
        for (m in Mood.values()) {
            val l = hsl(Palette.backgroundColor(m)).third
            assertTrue("$m background lightness was $l, outside 6-10%", l in 0.055f..0.105f)
        }
    }

    @Test
    fun `the approved mood colours are exactly the ones rendered`() {
        assertEquals(0xFF4AD96B.toInt(), Palette.eyeColor(Mood.HAPPY))
        assertEquals(0xFF5B8DD6.toInt(), Palette.eyeColor(Mood.SAD))
        assertEquals(0xFFFF7043.toInt(), Palette.eyeColor(Mood.FRUSTRATED))
        assertEquals(0xFFFF6FAE.toInt(), Palette.eyeColor(Mood.LOVING))
        assertEquals(0xFFE8B63C.toInt(), Palette.eyeColor(Mood.SMUG))
        assertEquals(0xFF8A7FD4.toInt(), Palette.eyeColor(Mood.SLEEPY))
        assertEquals(0xFF4FD4E4.toInt(), Palette.eyeColor(Mood.NEUTRAL))
    }

    @Test
    fun `every colour is fully opaque`() {
        for (m in Mood.values()) {
            assertEquals("$m eye alpha", 0xFF, (Palette.eyeColor(m) ushr 24) and 0xFF)
            assertEquals("$m bg alpha", 0xFF, (Palette.backgroundColor(m) ushr 24) and 0xFF)
        }
    }

    @Test
    fun `the computed background matches what was rendered and approved`() {
        // The three swatches in face-background.html, option B.
        for ((mood, approved) in listOf(
            Mood.HAPPY to 0xFF190A17.toInt(),
            Mood.FRUSTRATED to 0xFF06181D.toInt(),
            Mood.LOVING to 0xFF07190D.toInt(),
        )) {
            val got = Palette.backgroundColor(mood)
            val a = hsl(approved)
            val b = hsl(got)
            assertEquals("$mood hue", 0f, hueDistance(a.first, b.first), 20f)
            assertEquals("$mood lightness", a.third, b.third, 0.03f)
        }
    }

    // Offline is the one mood that must not look like a mood. The spec: it
    // "does not pretend to be thinking. Faking it would be worse than
    // admitting it."
    @Test
    fun `offline is drained of colour`() {
        val s = hsl(Palette.eyeColor(Mood.OFFLINE)).second
        assertTrue("offline saturation was $s; it must look switched off", s < 0.3f)
        for (m in Mood.values()) {
            if (m == Mood.OFFLINE) continue
            assertTrue(
                "$m must be more saturated than offline",
                hsl(Palette.eyeColor(m)).second > s,
            )
        }
    }

    @Test
    fun `only listening pulses its glow`() {
        val still = (0..2400 step 100).map { Palette.glowRadiusDp(Mood.NEUTRAL, it.toLong()) }
        assertEquals("a resting face must not throb", 1, still.toSet().size)

        val pulsing = (0..2400 step 100).map { Palette.glowRadiusDp(Mood.LISTENING, it.toLong()) }
        assertTrue("listening must visibly pulse", pulsing.toSet().size > 5)
        assertTrue(
            "the pulse must be big enough to read across a desk",
            pulsing.max() - pulsing.min() > 15f,
        )
    }

    @Test
    fun `the glow never collapses to nothing`() {
        for (m in Mood.values()) {
            for (t in 0..2400 step 50) {
                assertTrue(
                    "$m glow vanished at ${t}ms",
                    Palette.glowRadiusDp(m, t.toLong()) > 0f,
                )
            }
        }
    }
}
