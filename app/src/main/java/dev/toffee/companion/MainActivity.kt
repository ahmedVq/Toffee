package dev.toffee.companion

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import dev.toffee.companion.face.FaceView
import dev.toffee.companion.face.Mood

/**
 * The only screen.
 *
 * Task 3 scope: the face, and a way to see every mood on the real panel.
 * Tapping cycles moods; `-e mood smug` launches straight into one, which is
 * how the screenshots are taken. Task 7 replaces both with the real thing.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var face: FaceView
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        face = FaceView(this)
        setContentView(face)
        keepScreenOn()

        val requested = Mood.parse(intent?.getStringExtra("mood"))
        face.mood = requested
        face.wake()

        index = Mood.values().indexOf(requested)
        face.setOnClickListener {
            index = (index + 1) % Mood.values().size
            face.mood = Mood.values()[index]
        }
    }

    private fun keepScreenOn() {
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
}
