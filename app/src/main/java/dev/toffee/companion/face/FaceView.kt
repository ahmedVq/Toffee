package dev.toffee.companion.face

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Toffee's face.
 *
 * This class does nothing but draw. Every number it uses — the silhouette for
 * a mood, the colour, the wake curve, whether an eye is mid-blink, where the
 * whole face has drifted to — comes from a pure function next door that has
 * its own tests. If logic appears in here, it is in the wrong file.
 *
 * A custom View rather than a WebView: this is on screen every waking hour of
 * every day on a 2016 tablet, and the wake animation's timing is the first
 * thing anyone sees it do.
 */
class FaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Eye width as a fraction of the shorter screen edge.
     *
     *  Derived, not guessed: in face-style.html the stage is 230 tall and a
     *  neutral eye is 104, so the eye owns 45% of the panel's height. At a
     *  2.27 height-to-width ratio that makes the width 0.45 / 2.27 of the
     *  shorter edge. Smaller than this and the face reads as two dots on a
     *  screen rather than something looking back at you. */
    private val eyeWidthFraction = 0.45f / 2.27f

    /** Gap between the eyes, as a multiple of eye width. The approved
     *  `.cf-face { gap: 46px }` against 46px-wide pills: exactly one eye. */
    private val gapRatio = 1.0f

    private val density = resources.displayMetrics.density

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val glowDst = RectF()
    private val path = Path()
    private val radii = FloatArray(8)

    private val blink = Blink()

    /** Null until [wake] is called: before that the eyes are simply shut. */
    private var wokeAtMs: Long? = null

    /** Animated so a mood change reads as a change of expression rather than
     *  a cut. The approved CSS uses a 1s ease on colour. */
    private var renderedMood: Mood = Mood.NEUTRAL
    private var targetMood: Mood = Mood.NEUTRAL
    private var moodBlend = 1f
    private var moodAnimator: ValueAnimator? = null

    private var glowLeft: Glow? = null
    private var glowRight: Glow? = null

    // Caches. This view redraws every frame for as long as the tablet is
    // powered, so anything allocated in onDraw is allocated for ever: a
    // RadialGradient and two PorterDuffColorFilters a frame came to roughly
    // 180 objects a second, several holding native shader memory.
    private var vignetteKey: Long = Long.MIN_VALUE
    private var litColor: Int = 0
    private var litFilter: PorterDuffColorFilter? = null

    var mood: Mood
        get() = targetMood
        set(value) {
            if (value == targetMood) return
            renderedMood = currentShapeSource()
            targetMood = value
            moodBlend = 0f
            moodAnimator?.cancel()
            moodAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = MOOD_FADE_MS
                addUpdateListener {
                    moodBlend = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    init {
        setBackgroundColor(Color.BLACK)
    }

    /** Eyes shut, then opening. Call once, when the face first appears. */
    fun wake() {
        wokeAtMs = SystemClock.elapsedRealtime()
        invalidate()
    }

    /** Back to shut, with no animation. */
    fun sleep() {
        wokeAtMs = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        moodAnimator?.cancel()
        glowLeft = null
        glowRight = null
        litFilter = null
        vignettePaint.shader = null
    }

    /** Shape snaps at the midpoint of a mood change. A pill interpolating
     *  through a silhouette that is not any mood looks like a rendering bug. */
    private fun currentShapeSource(): Mood = if (moodBlend >= 0.5f) targetMood else renderedMood

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val woke = wokeAtMs
        val sinceWake = if (woke == null) 0L else now - woke

        val bg = blendColor(
            Palette.backgroundColor(renderedMood),
            Palette.backgroundColor(targetMood),
            moodBlend,
        )
        val eyeColor = blendColor(
            Palette.eyeColor(renderedMood),
            Palette.eyeColor(targetMood),
            moodBlend,
        )
        val shapeMood = currentShapeSource()

        canvas.drawColor(bg)
        drawVignette(canvas, eyeColor)

        val drift = Drift.offsetAt(now)
        val cx = width / 2f + drift.x * density
        val cy = height / 2f + drift.y * density

        val eyeWidth = minOf(width, height) * eyeWidthFraction
        val gap = eyeWidth * gapRatio
        val glowPx = Palette.glowRadiusDp(shapeMood, now) * density

        eyePaint.color = eyeColor

        drawEye(
            canvas, shapeMood, shapeMood.left(), Blink.Eye.LEFT,
            cx - gap / 2f - eyeWidth / 2f, cy, eyeWidth,
            sinceWake, woke != null, now, 0L, eyeColor, glowPx,
        )
        drawEye(
            canvas, shapeMood, shapeMood.right(), Blink.Eye.RIGHT,
            cx + gap / 2f + eyeWidth / 2f, cy, eyeWidth,
            sinceWake, woke != null, now, Wake.RIGHT_DELAY_MS, eyeColor, glowPx,
        )

        postInvalidateOnAnimation()
    }

    private fun drawEye(
        canvas: Canvas,
        mood: Mood,
        shape: EyeShape,
        eye: Blink.Eye,
        cx: Float,
        cy: Float,
        eyeWidth: Float,
        sinceWakeMs: Long,
        awake: Boolean,
        nowMs: Long,
        delayMs: Long,
        color: Int,
        glowPx: Float,
    ) {
        val wakeScale = if (awake) Wake.scaleYAt(sinceWakeMs - delayMs) else Wake.scaleYAt(0)
        val blinkScale = when {
            !awake -> 1f
            sinceWakeMs < Wake.DURATION_MS -> 1f
            !mood.blinks -> 1f
            blink.isBlinkingAt(nowMs, eye) -> Blink.CLOSED_SCALE
            else -> 1f
        }
        val brightness = if (awake) Wake.brightnessAt(sinceWakeMs - delayMs) else 0.35f

        val restHeight = eyeWidth * shape.heightRatio
        val height = restHeight * wakeScale * blinkScale
        val top = cy + shape.offsetYRatio * eyeWidth - height / 2f
        rect.set(cx - eyeWidth / 2f, top, cx + eyeWidth / 2f, top + height)

        val alpha = (shape.alpha * 255f).toInt().coerceIn(0, 255)
        val lit = scaleRgb(color, brightness)

        canvas.save()
        if (shape.rotationDeg != 0f) canvas.rotate(shape.rotationDeg, cx, cy)

        // The glow, as `box-shadow: 0 0 26px, 0 0 70px` in the approved CSS.
        //
        // A BlurMaskFilter straight onto the screen is silently ignored under
        // hardware acceleration — the first build drew flat eyes with no light
        // at all — and concentric expanded copies band into visible rings. So
        // the blur is rendered once into a bitmap on a software canvas and
        // blitted every frame.
        //
        // Baked white and tinted at draw time, so a mood crossfade does not
        // rebuild it sixty times a second; built at the eye's *resting* height,
        // so a blink does not either.
        val glow = glowFor(eye, shape, eyeWidth, restHeight, glowPx)
        if (glow != null) {
            val mx = glow.marginPx
            val my = mx * (height / restHeight).coerceAtLeast(0.05f)
            glowDst.set(rect.left - mx, rect.top - my, rect.right + mx, rect.bottom + my)
            glowPaint.colorFilter = tintFor(lit)
            glowPaint.alpha = alpha
            canvas.drawBitmap(glow.bitmap, null, glowDst, glowPaint)
        }

        eyePaint.color = lit
        eyePaint.alpha = alpha
        drawPill(canvas, rect, shape, eyeWidth, eyePaint)
        canvas.restore()
    }

    /**
     * A pill with its own radius top and bottom.
     *
     * `drawRoundRect` takes one radius for all four corners, which silently
     * throws away the difference between a domed top and a flat bottom — and
     * that difference is the entire mood vocabulary. Happy and loving are
     * nearly the same height; which end is domed is what separates them.
     */
    private fun drawPill(canvas: Canvas, r: RectF, shape: EyeShape, w: Float, paint: Paint) {
        val h = r.height()
        val top = minOf(shape.topRadiusRatio * w, w / 2f, h / 2f)
        val bottom = minOf(shape.bottomRadiusRatio * w, w / 2f, h / 2f)
        radii[0] = top; radii[1] = top
        radii[2] = top; radii[3] = top
        radii[4] = bottom; radii[5] = bottom
        radii[6] = bottom; radii[7] = bottom
        path.reset()
        path.addRoundRect(r, radii, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    private class Glow(val bitmap: Bitmap, val marginPx: Float, val key: String)

    private fun glowFor(
        eye: Blink.Eye,
        shape: EyeShape,
        width: Float,
        restHeight: Float,
        blurPx: Float,
    ): Glow? {
        if (blurPx <= 0.5f || width <= 1f || restHeight <= 1f) return null

        // The pulse on LISTENING would otherwise rebuild the bitmap on every
        // frame; eight buckets is finer than the eye can see in a 1.2s swell.
        val bucket = (blurPx / 4f).toInt()
        val key = "${shape.topRadiusRatio}|${shape.bottomRadiusRatio}|" +
            "${width.toInt()}|${restHeight.toInt()}|$bucket"

        val cached = if (eye == Blink.Eye.LEFT) glowLeft else glowRight
        if (cached != null && cached.key == key) return cached

        val blur = (bucket * 4f).coerceAtLeast(1f)
        val margin = blur * 2.2f
        // Half resolution: a blur has no high frequencies to lose, and it
        // quarters both the memory and the time to build it.
        val ds = 0.5f
        val bw = ((width + margin * 2f) * ds).toInt().coerceIn(1, 2048)
        val bh = ((restHeight + margin * 2f) * ds).toInt().coerceIn(1, 2048)

        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            maskFilter = BlurMaskFilter(blur * ds, BlurMaskFilter.Blur.NORMAL)
        }
        val r = RectF(
            margin * ds,
            margin * ds,
            (margin + width) * ds,
            (margin + restHeight) * ds,
        )
        drawPill(Canvas(bitmap), r, shape, width * ds, paint)

        val glow = Glow(bitmap, margin, key)
        // Dropped, not recycled: under hardware acceleration the previous
        // bitmap may still be referenced by a display list the RenderThread
        // has not finished with. GC reclaims it safely; recycle() does not.
        if (eye == Blink.Eye.LEFT) glowLeft = glow else glowRight = glow
        return glow
    }

    private fun drawVignette(canvas: Canvas, tint: Int) {
        // "a soft vignette so the face sits in a pool of its own light".
        // The approved rule is `radial-gradient(38% 44% at 50% 50%, mood 0%,
        // transparent 70%)` — tight and centred, not a wash over the panel.
        val cx = width / 2f
        val cy = height / 2f
        val r = width * 0.44f
        if (r <= 0f) return
        // Only rebuilt when the colour or the size actually changes.
        val key = (tint.toLong() shl 32) xor (width.toLong() shl 16) xor height.toLong()
        if (key != vignetteKey || vignettePaint.shader == null) {
            vignettePaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(withAlpha(tint, 56), withAlpha(tint, 20), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            vignetteKey = key
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
    }

    /** Brightness during the wake flare, applied to the colour rather than
     *  through a ColorMatrix — the glow already spends its one colour filter
     *  on tinting the baked white bitmap. */
    private fun scaleRgb(color: Int, f: Float): Int {
        if (f in 0.99f..1.01f) return color
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun tintFor(color: Int): PorterDuffColorFilter {
        val cached = litFilter
        if (cached != null && color == litColor) return cached
        val fresh = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        litColor = color
        litFilter = fresh
        return fresh
    }

    private fun withAlpha(color: Int, alpha: Int) =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun blendColor(from: Int, to: Int, t: Float): Int {
        if (t >= 1f) return to
        if (t <= 0f) return from
        fun mix(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + ((b - a) * t)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private companion object {
        const val MOOD_FADE_MS = 1000L
    }
}
