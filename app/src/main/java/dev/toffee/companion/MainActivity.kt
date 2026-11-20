package dev.toffee.companion

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.toffee.companion.face.FaceView
import dev.toffee.companion.face.Mood
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
        goFullscreen()

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

    private fun goFullscreen() {
        // The spec: the screen is never fully off while powered, because
        // presence detection and the arrival ambush both depend on it.
        face.keepScreenOn = true
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private companion object {
        const val TAG = "Toffee"
    }
}
