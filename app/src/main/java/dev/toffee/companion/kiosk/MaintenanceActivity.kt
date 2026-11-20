package dev.toffee.companion.kiosk

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.toffee.companion.net.KeystoreTokenStore

/**
 * The only screen that is not the face.
 *
 * Reached by holding the top-left corner for five seconds and entering the
 * PIN. Without it, a changed Wi-Fi password bricks the tablet until a factory
 * reset — there is no Settings app to open and no launcher to go home to.
 *
 * Holds no logic worth testing: the gate is [CornerHold] and [Pin], both
 * pure and tested; this is buttons.
 */
class MaintenanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Kiosk.hideSystemUi(this)

        val pins = PinStore(this)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "Maintenance"
            setTextColor(Color.parseColor("#4FD4E4"))
            textSize = 28f
        }
        val note = TextView(this).apply {
            setTextColor(Color.parseColor("#8A8A8E"))
            textSize = 14f
            setPadding(0, pad / 4, 0, pad / 2)
            text = if (Kiosk.isDeviceOwner(this@MaintenanceActivity)) {
                "Device owner. The tablet is pinned."
            } else {
                "Not device owner. The tablet is escapable."
            }
        }

        val pinField = EditText(this).apply {
            hint = "PIN"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#48484A"))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#FF7043"))
            textSize = 14f
            setPadding(0, pad / 4, 0, pad / 2)
        }

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = ViewGroup.GONE
        }

        fun tool(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

        tools.addView(tool("Wi-Fi settings") { Kiosk.openWifiSettings(this) })
        tools.addView(
            tool("Leave kiosk (until next launch)") {
                Kiosk.exit(this)
                finish()
            },
        )
        tools.addView(
            tool("Sign this tablet out") {
                KeystoreTokenStore(applicationContext).clear()
                status.text = "Signed out. It will ask for the identity again."
            },
        )
        tools.addView(
            tool("Back to the face") {
                startActivity(
                    Intent().setClassName(packageName, "dev.toffee.companion.MainActivity"),
                )
                finish()
            },
        )

        val unlock = Button(this).apply {
            text = "Unlock"
            isAllCaps = false
            setOnClickListener {
                // This screen can only check a PIN. It is set during setup,
                // because the tools below it are the way out of the kiosk.
                if (!pins.isSet()) {
                    status.text = "No PIN was set at setup. Sign the tablet in again to set one."
                    tools.visibility = ViewGroup.GONE
                } else if (pins.verify(pinField.text.toString())) {
                    status.text = ""
                    tools.visibility = ViewGroup.VISIBLE
                } else {
                    status.text = "No."
                    tools.visibility = ViewGroup.GONE
                }
                pinField.setText("")
            }
        }

        for (v in listOf(title, note, pinField, unlock, status, tools)) {
            root.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.START },
            )
        }
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(Color.BLACK) })
    }

}
