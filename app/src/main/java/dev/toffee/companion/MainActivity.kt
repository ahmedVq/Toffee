package dev.toffee.companion

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * The tap stands in for speech. The microphone, the speaker and the camera
 * are Plan 3.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var face: FaceView
    private lateinit var caption: TextView
    private lateinit var brain: Brain
    private lateinit var session: Session

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
        brain = Brain(client, inbox, session::state, lifecycleScope)

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

        lifecycleScope.launch { brain.onArrival() }
        startPolling(dao)
        listenForAlarms()

        face.setOnClickListener { lifecycleScope.launch { brain.say(TAP_PROMPT) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReminderAlarmReceiver.listener = null
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

    /** Presence arrives in Plan 3; until then the policy is driven by the
     *  clock alone, which is what makes it stop glowing at 3am. */
    private fun startBrightnessTicker() {
        val tick = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                Kiosk.apply(
                    this@MainActivity,
                    BrightnessPolicy.levelFor(
                        presentSinceMs = now,
                        lastSeenMs = now,
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
        const val TIMEZONE = "Africa/Cairo"

        /** Standing in for speech until Plan 3 brings the microphone. A tap
         *  is Ahmed asking for attention, so this is what that means. */
        const val TAP_PROMPT = "I tapped you. What should I be doing?"

        /** A tap is a tap; a drag or a long hold is not. */
        const val TAP_SLOP_DP = 24f
        const val TAP_MAX_MS = 700L
    }
}
