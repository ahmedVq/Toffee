package dev.toffee.companion

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Task 1's whole screen: proof that the toolchain reaches the tablet.
 *
 * The face replaces this in Task 3.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = "Toffee — build ok"
            setTextColor(Color.parseColor("#4FD4E4"))
            textSize = 32f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }

        setContentView(text)
    }
}
