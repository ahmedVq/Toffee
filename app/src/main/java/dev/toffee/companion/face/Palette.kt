package dev.toffee.companion.face

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Colour, and the rule behind the background.
 *
 * The eye colours are the six Ahmed picked from the rendered options, plus
 * neutral, listening and offline. The background is **computed**, not
 * tabulated: `docs/design/face-background.html` option B is "the field behind
 * the eyes is the opposite hue at 6-10% lightness", and a lookup table would
 * drift from that rule the first time a mood is added.
 *
 * No `android.graphics.Color` here on purpose. It is stubbed in JVM unit
 * tests and throws, and this is the part of the face most worth testing.
 */
object Palette {

    /** Hue rotation for the background field. */
    private const val COMPLEMENT_DEG = 180f

    /** Saturation and lightness of the field. Low enough that the panel does
     *  not light a dark room at 2am; high enough that the hue still reads. */
    private const val BG_SATURATION = 0.45f
    private const val BG_LIGHTNESS = 0.075f

    private const val GLOW_DP = 26f
    private const val LISTENING_PULSE_MS = 1200f

    fun eyeColor(mood: Mood): Int = when (mood) {
        Mood.HAPPY -> 0xFF4AD96B.toInt()
        Mood.SAD -> 0xFF5B8DD6.toInt()
        Mood.FRUSTRATED -> 0xFFFF7043.toInt()
        Mood.LOVING -> 0xFFFF6FAE.toInt()
        Mood.SMUG -> 0xFFE8B63C.toInt()
        Mood.SLEEPY -> 0xFF8A7FD4.toInt()
        Mood.NEUTRAL -> 0xFF4FD4E4.toInt()
        Mood.LISTENING -> 0xFF4FD4E4.toInt()
        // Not a feeling. Grey-blue, nearly drained, so it reads as a device
        // that has lost its connection rather than a mood it is having.
        Mood.OFFLINE -> 0xFF5A6472.toInt()
    }

    fun backgroundColor(mood: Mood): Int {
        val h = hueOf(eyeColor(mood))
        return hslToArgb((h + COMPLEMENT_DEG) % 360f, BG_SATURATION, BG_LIGHTNESS)
    }

    /**
     * The blur radius of the eye's glow, in dp.
     *
     * Only listening moves. The spec asks for a listening state that is
     * unambiguous, and a slow swell of light is what says "it is hearing you"
     * without a word of text on the screen.
     */
    fun glowRadiusDp(mood: Mood, tMs: Long): Float = when (mood) {
        Mood.LISTENING -> {
            val phase = (tMs % LISTENING_PULSE_MS.toLong()) / LISTENING_PULSE_MS
            val swell = (1f + sin(phase * 2f * Math.PI.toFloat())) / 2f
            GLOW_DP + GLOW_DP * swell
        }
        Mood.SLEEPY -> GLOW_DP * 0.55f
        Mood.OFFLINE -> GLOW_DP * 0.18f
        else -> GLOW_DP
    }

    // ------------------------------------------------------------ colour maths

    private fun hueOf(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        if (d == 0f) return 0f
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return (h + 360f) % 360f
    }

    private fun hslToArgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return (0xFF shl 24) or
            (((r + m) * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            (((g + m) * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            ((b + m) * 255f).roundToInt().coerceIn(0, 255)
    }
}
