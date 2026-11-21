package dev.toffee.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.toffee.companion.face.FaceView
import dev.toffee.companion.face.Mood
import dev.toffee.companion.face.Palette
import dev.toffee.companion.kiosk.BrightnessPolicy
import dev.toffee.companion.kiosk.CornerHold
import dev.toffee.companion.kiosk.Kiosk
import dev.toffee.companion.kiosk.MaintenanceActivity
import dev.toffee.companion.net.KeystoreTokenStore
import dev.toffee.companion.net.Session
import dev.toffee.companion.net.SessionState
import dev.toffee.companion.net.ToffeeClient
import dev.toffee.companion.net.ToffeeHttp
import dev.toffee.companion.presence.CameraEye
import dev.toffee.companion.presence.Presence
import dev.toffee.companion.presence.PresenceReporter
import dev.toffee.companion.reminders.AlarmScheduler
import dev.toffee.companion.reminders.Mirror
import dev.toffee.companion.reminders.MirrorDao
import dev.toffee.companion.reminders.ReminderAlarmReceiver
import dev.toffee.companion.reminders.ReminderInbox
import dev.toffee.companion.reminders.ReminderPoller
import dev.toffee.companion.reminders.RoomAnnouncedStore
import dev.toffee.companion.reminders.pendingOnIo
import dev.toffee.companion.reminders.toRow
import dev.toffee.companion.reminders.upsertOnIo
import dev.toffee.companion.voice.AndroidVoice
import dev.toffee.companion.voice.CloudEars
import dev.toffee.companion.voice.DeviceEars
import dev.toffee.companion.voice.Ears
import dev.toffee.companion.voice.Heard
import dev.toffee.companion.voice.bestEars
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The whole device.
 *
 * Holds no decisions: it renders [Brain.state] into the face and one line of
 * text, and feeds Brain the three things only Android knows — a tap, a poll,
 * an alarm. What to say and when lives in Brain, which has its own tests and
 * no Android in it.
 *
 * A tap opens the microphone; the front camera decides whether he is
 * there at all.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var face: FaceView
    private lateinit var caption: TextView
    private lateinit var brain: Brain
    private lateinit var session: Session
    private lateinit var voice: AndroidVoice
    private lateinit var ears: Ears
    private val presence = Presence()
    private var eye: CameraEye? = null

    private val ui = Handler(Looper.getMainLooper())
    private var brightnessTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        face = FaceView(this)
        caption = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            val pad = (32 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, pad)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    face,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    caption,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM,
                    ),
                )
            },
        )

        Kiosk.enter(this)
        // Asked for at startup rather than on the first tap, so that on a
        // device that is not yet Device Owner the dialog is out of the way
        // before he ever touches the face. As Device Owner it is granted
        // outright and nothing is shown at all.
        Kiosk.grantOwnPermissions(
            this,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )
        wireMaintenanceGate()
        startBrightnessTicker()
        face.wake()

        val http = ToffeeHttp.client()
        session = Session(
            http = http,
            baseUrl = ToffeeConfig.baseUrl,
            apiKey = ToffeeConfig.publishableKey,
            store = KeystoreTokenStore(applicationContext),
        )

        if (session.state() == SessionState.SignedOut) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        val dao = Mirror.get(this).dao()
        val inbox = ReminderInbox(RoomAnnouncedStore(dao))
        val client = ToffeeClient(http, ToffeeConfig.baseUrl, ToffeeConfig.publishableKey, session)

        // A tablet with no usable engine is a tablet that writes. Saying so
        // once, in the caption, is the only sign it can give: there is no
        // Settings app to go and look in.
        voice = AndroidVoice(this) { why -> ui.post { caption.text = why } }
        // The device's own recogniser is free and this ROM has one; the
        // cloud is the fallback that costs money per minute, so it is only
        // reached when there is nothing on the device to reach instead.
        ears = bestEars(
            DeviceEars(this),
            CloudEars(http, ToffeeConfig.baseUrl, ToffeeConfig.publishableKey, session, this),
        )
        brain = Brain(client, inbox, session::state, lifecycleScope, voice)

        render()

        // `-e mood smug` paints one mood and stops. The face has no unit
        // tests by design — everything it draws is a pure function that does
        // — so scripts/face-shots.sh is its only instrument, and Task 7
        // silently broke it by dropping this extra.
        val forced = intent?.getStringExtra("mood")
        if (forced != null) {
            face.mood = Mood.parse(forced)
            caption.text = forced
            caption.setTextColor(Palette.eyeColor(Mood.parse(forced)))
            return
        }

        // Who says he has arrived: the camera if there is one, otherwise the
        // fact that the app just started. Both would greet him twice — once
        // on launch and again six seconds later when the eye caught up.
        if (!startWatching()) lifecycleScope.launch { brain.onArrival() }
        startPolling(dao)
        listenForAlarms()

        face.setOnClickListener { onTap() }
    }

    /**
     * The whole face is the button.
     *
     * A tap while it is talking is him interrupting, and a companion that
     * talks over an interruption is a radio — so that tap only hushes it. A
     * tap while it is already listening is a double-tap, and starting a
     * second recognition on top of the first would lose both.
     */
    private fun onTap() {
        if (brain.state.value.listening) return
        if (voice.speaking) {
            brain.hush()
            return
        }

        lifecycleScope.launch {
            val missing = Kiosk.grantOwnPermissions(
                this@MainActivity,
                Manifest.permission.RECORD_AUDIO,
            )
            if (missing.isNotEmpty()) {
                brain.onCannotHear("The microphone is switched off and I can't switch it on.")
                return@launch
            }

            brain.onListening()
            when (val heard = ears.listen()) {
                is Heard.Words -> brain.say(heard.text)
                is Heard.Nothing -> brain.onHeardNothing()
                is Heard.Unavailable -> brain.onCannotHear(heard.reason)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReminderAlarmReceiver.listener = null
        if (::voice.isInitialized) voice.shutdown()
        eye?.stop()
        eye = null
        // A self-reposting Runnable holding the Activity outlives finish()
        // otherwise — and on the signed-out path this Activity finishes
        // immediately, so it would have leaked every launch.
        brightnessTick?.let { ui.removeCallbacks(it) }
        brightnessTick = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive mode is not sticky across every system interruption, and
        // a status bar creeping back is a way out of the kiosk.
        if (hasFocus) Kiosk.hideSystemUi(this)
    }

    private fun render() = lifecycleScope.launch {
        brain.state.collectLatest { s ->
            face.mood = s.mood
            caption.text = s.line.orEmpty()
            caption.setTextColor(Palette.eyeColor(if (s.line == null) Mood.NEUTRAL else s.mood))
        }
    }

    private fun startPolling(dao: MirrorDao) {
        val poller = ReminderPoller(
            http = ToffeeHttp.client(),
            baseUrl = ToffeeConfig.baseUrl,
            apiKey = ToffeeConfig.publishableKey,
            session = session,
        )
        val alarms = AlarmScheduler(applicationContext)

        lifecycleScope.launch {
            poller.flow().collect { fired ->
                // A revoked identity reaches the face here, not only when
                // something happens to call Brain — which, on a dead tablet,
                // is never.
                brain.refreshSessionState()

                if (fired.isNotEmpty()) {
                    dao.upsertOnIo(fired.map { it.toRow() })
                    brain.onReminders(ReminderInbox.Source.POLL, fired)
                }

                // The offline backstop. Reminders that have NOT fired are
                // what alarms are set for, so they must be fetched too — and
                // for a while they were not, which left the second route
                // scheduling nothing at all.
                val pending = poller.pollPending()
                if (pending.isNotEmpty()) dao.upsertOnIo(pending.map { it.toRow() })
                alarms.syncFrom(dao.pendingOnIo())
            }
            // The flow completes only when the identity is gone.
            brain.refreshSessionState()
        }
    }

    /**
     * The front camera, and what it is allowed to mean.
     *
     * Without the permission the eye simply does not start: presence stays
     * unknown and the brightness policy falls back to the clock, which is
     * what it did before there was a camera at all. A tablet that dims at
     * noon because it wrongly believes the room is empty would be worse than
     * one that never looks.
     *
     * @return whether the camera is now watching, which is also the answer
     *         to "is anything else going to tell Brain he arrived".
     */
    private fun startWatching(): Boolean {
        if (!Kiosk.granted(this, Manifest.permission.CAMERA)) {
            Log.w(TAG, "no camera permission; presence stays unknown")
            return false
        }

        val reporter = PresenceReporter(
            http = ToffeeHttp.client(),
            baseUrl = ToffeeConfig.baseUrl,
            apiKey = ToffeeConfig.publishableKey,
            session = session,
        )

        eye = CameraEye(this, this) { faces, atMs ->
            val change = presence.sample(faces, atMs) ?: return@CameraEye
            lifecycleScope.launch {
                when (change) {
                    is Presence.Change.Arrived -> {
                        reporter.report(present = true, atMs = atMs, durationS = null)
                        brain.onArrival()
                    }
                    is Presence.Change.Left -> {
                        reporter.report(
                            present = false,
                            atMs = atMs,
                            durationS = change.afterMinutes * 60,
                        )
                        brain.onDeparture()
                    }
                    is Presence.Change.StillHere -> brain.onStillHere(change.minutes)
                }
            }
        }
        eye?.start()
        return true
    }

    /** The route that works with the router unplugged. */
    private fun listenForAlarms() {
        ReminderAlarmReceiver.listener = { reminder ->
            lifecycleScope.launch {
                brain.onReminders(ReminderInbox.Source.ALARM, listOf(reminder))
            }
        }
    }

    /** Hold the top-left corner for five seconds. The only way back in once
     *  Device Owner removes Settings and the launcher. */
    private fun isATap(
        event: MotionEvent,
        downXDp: Float,
        downYDp: Float,
        downAtMs: Long,
        density: Float,
    ): Boolean {
        val movedDp = kotlin.math.hypot(
            event.x / density - downXDp,
            event.y / density - downYDp,
        )
        return movedDp < TAP_SLOP_DP && event.eventTime - downAtMs < TAP_MAX_MS
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireMaintenanceGate() {
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        var openedMaintenance = false
        val density = resources.displayMetrics.density

        val open = Runnable {
            if (CornerHold.held(downX, downY, downAt, downAt + CornerHold.HOLD_MS)) {
                openedMaintenance = true
                startActivity(Intent(this, MaintenanceActivity::class.java))
            }
        }

        face.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = event.eventTime
                    downX = event.x / density
                    downY = event.y / density
                    if (downX <= CornerHold.BOX_DP && downY <= CornerHold.BOX_DP) {
                        ui.postDelayed(open, CornerHold.HOLD_MS)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(open)
                    // Only a real tap talks. Without this a five-second
                    // corner hold opened Maintenance AND fired a /turn, and
                    // so did any swipe across the face.
                    if (!openedMaintenance && isATap(event, downX, downY, downAt, density)) {
                        v.performClick()
                    }
                    openedMaintenance = false
                }
                MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(open)
            }
            true
        }
    }

    /**
     * The panel's brightness, once a minute.
     *
     * Now driven by the camera as the spec intended. With no camera
     * permission both values are null, which BrightnessPolicy reads as "no
     * one has ever been seen" — the clock-only behaviour this had before.
     */
    private fun startBrightnessTicker() {
        val tick = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                Kiosk.apply(
                    this@MainActivity,
                    BrightnessPolicy.levelFor(
                        presentSinceMs = presence.presentSinceMs,
                        lastSeenMs = presence.lastSeenMs,
                        nowMs = now,
                        localHour = ZonedDateTime.now(ZoneId.of(TIMEZONE)).hour,
                    ),
                )
                ui.postDelayed(this, 60_000)
            }
        }
        brightnessTick = tick
        ui.post(tick)
    }

    private companion object {
        const val TAG = "Toffee"
        const val TIMEZONE = "Africa/Cairo"


        /** A tap is a tap; a drag or a long hold is not. */
        const val TAP_SLOP_DP = 24f
        const val TAP_MAX_MS = 700L
    }
}
