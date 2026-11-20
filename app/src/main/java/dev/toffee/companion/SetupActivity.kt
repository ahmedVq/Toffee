package dev.toffee.companion

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.toffee.companion.net.KeystoreTokenStore
import dev.toffee.companion.net.Session
import dev.toffee.companion.net.ToffeeHttp
import kotlinx.coroutines.launch

/**
 * Typed once, in Ahmed's office, and then never again.
 *
 * The identity comes from `deno run -A scripts/provision-device.ts tablet`,
 * which prints it once and stores it nowhere. After this screen the tablet
 * holds only a refresh token, in the Keystore.
 *
 * No logic here beyond a sign-in call; everything that can go wrong about
 * sessions is in [Session], which is tested.
 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad * 3, pad, pad * 3, pad)
            setBackgroundColor(Color.BLACK)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#8A8A8E"))
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 6)
        }

        val email = EditText(this).apply {
            hint = "tablet@toffee.local"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#48484A"))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val password = EditText(this).apply {
            setTextColor(Color.WHITE)
            // Order matters: setSingleLine() rewrites inputType, so setting
            // the password variation first leaves it rendering in clear text
            // on a screen someone is holding up in an office.
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // The soft keyboard covers the button on a 1200px-tall panel, and
            // this screen is used once by someone holding a password they
            // cannot retype from memory. Done submits.
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val status = TextView(this).apply {
            setTextColor(Color.parseColor("#FF7043"))
            textSize = 15f
            setPadding(0, pad / 2, 0, 0)
        }

        val title = TextView(this).apply {
            text = "Toffee"
            setTextColor(Color.parseColor("#4FD4E4"))
            textSize = 34f
        }
        val subtitle = TextView(this).apply {
            text = "Sign this tablet in. Once."
            setTextColor(Color.parseColor("#8A8A8E"))
            textSize = 16f
            setPadding(0, pad / 6, 0, pad)
        }

        val signIn = Button(this).apply {
            text = "Sign in"
            isAllCaps = false
        }

        val store = KeystoreTokenStore(applicationContext)
        val session = Session(
            http = ToffeeHttp.client(),
            baseUrl = ToffeeConfig.baseUrl,
            apiKey = ToffeeConfig.publishableKey,
            store = store,
        )

        // Samsung's keyboard sends IME_ACTION_UNSPECIFIED with a raw ENTER
        // KeyEvent rather than the DONE action it was asked for, so matching
        // on DONE alone meant the button — which the keyboard covers — was
        // the only way to sign in. Accept anything that means "go".
        password.setOnEditorActionListener { _, action, event ->
            val go = action == EditorInfo.IME_ACTION_DONE ||
                action == EditorInfo.IME_ACTION_GO ||
                action == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (go) { signIn.performClick(); true } else false
        }

        signIn.setOnClickListener {
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (e.isEmpty() || p.isEmpty()) {
                status.text = "Both, please."
                return@setOnClickListener
            }
            signIn.isEnabled = false
            status.text = "…"
            lifecycleScope.launch {
                val result = session.signIn(e, p)
                signIn.isEnabled = true
                if (result.isSuccess) {
                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                } else {
                    // The reason, not a shrug: this screen is used once, by
                    // the owner, and "it didn't work" is useless to him.
                    // Never echoes what was typed.
                    status.text = result.exceptionOrNull()?.message
                        ?: "Refused. Check the identity and try again."
                }
            }
        }

        for (v in listOf(title, subtitle, status, label("Identity"), email, label("Password"), password, signIn)) {
            root.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.BLACK)
                isFillViewport = true
                addView(root)
            },
        )
    }
}
