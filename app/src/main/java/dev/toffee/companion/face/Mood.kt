package dev.toffee.companion.face

/**
 * One eye's silhouette, as ratios of the eye's **width**.
 *
 * Width is the only absolute the face has; everything else is derived from it,
 * so the same table drives a 10-inch tablet and a screenshot in a test.
 */
data class EyeShape(
    /** Height, as a multiple of width. Neutral is 2.27 — a tall pill. */
    val heightRatio: Float,
    /** Corner radius at the top. 0.5 is a perfect dome. */
    val topRadiusRatio: Float,
    /** Corner radius at the bottom. */
    val bottomRadiusRatio: Float,
    /** Vertical nudge from centre. Negative is up. */
    val offsetYRatio: Float = 0f,
    /** Tilt, degrees. Only frustrated uses it. */
    val rotationDeg: Float = 0f,
    /** Opacity. Only sleepy dims. */
    val alpha: Float = 1f,
)

/**
 * What the face is doing.
 *
 * The geometry comes from `docs/design/face-style.html`, where the neutral eye
 * is 22 x 50 and each mood overrides it: happy 26 tall with rounded tops, sad
 * 30 with rounded bottoms and dropped, frustrated 18 and tilted, and so on.
 * Those pixel values are divided by 22 here.
 *
 * The rule that matters, from the design README: **mood is shape plus colour,
 * never colour alone.** Sad and sleepy are both cool-toned, so colour cannot
 * be what tells them apart.
 */
enum class Mood(val blinks: Boolean = true) {

    /** Awake, nothing in particular. */
    NEUTRAL,

    HAPPY,
    SAD,
    FRUSTRATED,
    LOVING,
    SMUG,

    /** Dim and nearly shut. Also what absence looks like. */
    SLEEPY,

    /** The mic is live. Taller than neutral and it does not blink — the spec
     *  asks for a listening state that is unambiguous across a room, and
     *  motion alone is not. */
    LISTENING(blinks = false),

    /** No network. Not a feeling; a fact. Drained of colour so it reads as
     *  switched off rather than sulking in character. */
    OFFLINE;

    fun left(): EyeShape = when (this) {
        NEUTRAL -> EyeShape(2.27f, 0.5f, 0.5f)
        HAPPY -> EyeShape(1.18f, 0.545f, 0.136f, offsetYRatio = -0.27f)
        SAD -> EyeShape(1.36f, 0.136f, 0.545f, offsetYRatio = 0.45f)
        FRUSTRATED -> EyeShape(0.82f, 0.18f, 0.18f, rotationDeg = 14f)
        LOVING -> EyeShape(1.09f, 0.545f, 0.09f)
        SMUG -> EyeShape(1.55f, 0.136f, 0.5f)
        SLEEPY -> EyeShape(0.36f, 0.18f, 0.18f, alpha = 0.65f)
        LISTENING -> EyeShape(2.5f, 0.5f, 0.5f)
        OFFLINE -> EyeShape(0.6f, 0.2f, 0.2f, offsetYRatio = 0.5f, alpha = 0.8f)
    }

    fun right(): EyeShape = when (this) {
        // The raised brow is the whole joke; both eyes at 20 is just a squint.
        SMUG -> EyeShape(0.91f, 0.136f, 0.5f)
        FRUSTRATED -> left().copy(rotationDeg = -14f)
        else -> left()
    }

    companion object {
        /**
         * Lenient on purpose. Observed in production: when the model is
         * unavailable the reply arrives with no mood at all, and a face that
         * throws on that goes black exactly when something is already wrong.
         */
        fun parse(raw: String?): Mood {
            val name = raw?.trim()?.uppercase() ?: return NEUTRAL
            return values().firstOrNull { it.name == name } ?: NEUTRAL
        }
    }
}
