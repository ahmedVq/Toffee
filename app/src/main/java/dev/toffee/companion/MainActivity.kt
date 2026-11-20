package dev.toffee.companion

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.toffee.companion.face.FaceView
import dev.toffee.companion.face.Mood
import dev.toffee.companion.kiosk.BrightnessPolicy
import dev.toffee.companion.kiosk.CornerHold
import dev.toffee.companion.kiosk.Kiosk
import dev.toffee.companion.kiosk.MaintenanceActivity
import dev.toffee.companion.net.KeystoreTokenStore
import dev.toffee.companion.net.Session
import dev.toffee.companion.net.SessionState
import dev.toffee.companion.net.ToffeeClient
import dev.toffee.companion.net.ToffeeHttp
import kotlinx.coroutines.launch

/**
 * The only screen.
 *
 * Task 4 scope: the face plus a real round trip to the agent. `-e mood smug`
 * still launches straight into a mood for the screenshots. Task 7 replaces
 * the tap handler with the actual loops.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var face: FaceView
    private lateinit var session: Session
    private lateinit var client: ToffeeClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        face = FaceView(this)
        setContentView(face)
        Kiosk.enter(this)
        wireMaintenanceGate()
        startBrightnessTicker()

        val http = ToffeeHttp.client()
        session = Session(
            http = http,
            baseUrl = ToffeeConfig.baseUrl,
            apiKey = ToffeeConfig.publishableKey,
            store = KeystoreTokenStore(applicationContext),
        )
        client = ToffeeClient(http, ToffeeConfig.baseUrl, ToffeeConfig.publishableKey, session)

        intent?.getStringExtra("mood")?.let { face.mood = Mood.parse(it) }
        face.wake()

        if (session.state() == SessionState.SignedOut) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        Log.i(TAG, "onCreate: state=${session.state()} wiring tap")
        face.isClickable = true
        face.setOnClickListener {
            Log.i(TAG, "tap")
            say(intent?.getStringExtra("say") ?: "hello")
        }
    }

    private fun say(text: String) {
        face.mood = Mood.LISTENING
        lifecycleScope.launch {
            val reply = client.turn(text)
            Log.d(TAG, "turn -> mood=${reply.mood} offline=${reply.offline} speak=${reply.speak}")
            face.mood = reply.mood
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive mode is not sticky across every system interruption, and
        // a status bar creeping back is a way out of the kiosk.
        if (hasFocus) Kiosk.hideSystemUi(this)
    }

    /** Hold the top-left corner for five seconds. The only way back in once
     *  Device Owner removes Settings and the launcher. */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireMaintenanceGate() {
        val handler = Handler(Looper.getMainLooper())
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        val density = resources.displayMetrics.density

        val open = Runnable {
            if (CornerHold.held(downX, downY, downAt, downAt + CornerHold.HOLD_MS)) {
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
                        handler.postDelayed(open, CornerHold.HOLD_MS)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(open)
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(open)
            }
            true
        }
    }

    /** Presence arrives in Plan 3; until then the policy is driven by the
     *  clock alone, which is what makes it stop glowing at 3am. */
    private fun startBrightnessTicker() {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val hour = java.time.ZonedDateTime
                    .now(java.time.ZoneId.of("Africa/Cairo")).hour
                Kiosk.apply(
                    this@MainActivity,
                    BrightnessPolicy.levelFor(
                        presentSinceMs = System.currentTimeMillis(),
                        lastSeenMs = System.currentTimeMillis(),
                        nowMs = System.currentTimeMillis(),
                        localHour = hour,
                    ),
                )
                handler.postDelayed(this, 60_000)
            }
        }
        handler.post(tick)
    }

    private companion object {
        const val TAG = "Toffee"
    }
}
