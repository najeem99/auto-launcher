package com.najib.carwelcome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Phone-side settings: your name, the welcome and dua switches, clock format,
 * and a test button. The dua recording and its lyric timing are built in.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var checkInStatus: TextView
    private lateinit var edgeButton: Button
    private val main = Handler(Looper.getMainLooper())

    private lateinit var overlayButton: Button
    private lateinit var prayerList: TextView

    override fun onResume() {
        super.onResume()
        if (::overlayButton.isInitialized) {
            val ok = Settings.canDrawOverlays(this)
            overlayButton.text = if (ok) "Allowed: Maps and Claude can open" else "Allow opening Maps and Claude"
            overlayButton.isEnabled = !ok
        }
        if (::checkInStatus.isInitialized) refreshStatus()
        if (::edgeButton.isInitialized) {
            val on = EdgeClicker.isEnabled(this)
            edgeButton.text = if (on) "Edge tapping is on" else "Turn on Edge tapping (Accessibility settings)"
            edgeButton.isEnabled = !on
        }
        if (::prayerList.isInitialized) showPrayerTimes()
    }

    private fun showPrayerTimes() {
        val (place, times) = Prayer.today(this)
        val fmt = java.text.SimpleDateFormat(if (prefs.use24Hour) "HH:mm" else "h:mm a", java.util.Locale.getDefault())
        val next = Prayer.next(this)
        prayerList.text = buildString {
            append("Today in ").append(place).append(":\n")
            for ((name, at) in times) append(name.padEnd(9)).append(fmt.format(java.util.Date(at))).append('\n')
            append("\nNext: ").append(next.name).append(' ').append(fmt.format(java.util.Date(next.at))).append(", ").append(next.countdown())
        }
        prayerList.typeface = android.graphics.Typeface.MONOSPACE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Welcome.attach(this)
        // Fine location lets the YouTube player tell from GPS speed that the car is parked.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, "com.google.android.gms.permission.CAR_SPEED"), 1)
        }
        // The car's Home/Office buttons start navigation through a phone notification.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Without "Display over other apps", Android blocks the car screen from opening
        // Google Maps or Claude on the phone, so the Maps buttons did nothing.
        overlayButton = Button(this).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        column.addView(heading("Maps and Claude from the car"))
        column.addView(label("Allow \"Display over other apps\" so the car's Maps, Home, Office and Claude buttons open those apps straight away. Without it, Home and Office send a notification to this phone; tap it to start navigation."))
        column.addView(overlayButton)
        column.addView(heading("Welcome"))
        column.addView(label("Your name. The car says \"Welcome back, <name>\" in a female voice."))
        column.addView(EditText(this).apply {
            setText(prefs.name)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { prefs.name = s?.toString().orEmpty() }
            })
        })
        column.addView(CheckBox(this).apply {
            text = "Welcome me when I open the dashboard in the car"
            isChecked = prefs.welcomeOnOpen
            setOnCheckedChangeListener { _, v -> prefs.welcomeOnOpen = v }
        })
        column.addView(CheckBox(this).apply {
            text = "Play Dua al-Safar after the welcome"
            isChecked = prefs.reciteDua
            setOnCheckedChangeListener { _, v -> prefs.reciteDua = v }
        })
        column.addView(CheckBox(this).apply {
            text = "24-hour clock"
            isChecked = prefs.use24Hour
            setOnCheckedChangeListener { _, v -> prefs.use24Hour = v }
        })
        column.addView(Button(this).apply {
            text = "Test the welcome"
            setOnClickListener { Welcome.run(this@MainActivity) }
        })

        column.addView(heading("Dua al-Safar"))
        column.addView(label("Your recording is built in, with the Arabic words timed to it."))
        column.addView(Button(this).apply {
            text = "Play / stop"
            setOnClickListener {
                if (DuaPlayer.isPlaying) DuaPlayer.stop() else DuaPlayer.play(this@MainActivity)
            }
        })

        column.addView(heading("Prayer times"))
        column.addView(label("Worked out on this phone with the UAE method (no internet needed), for where you are, or Dubai when the location isn't known."))
        prayerList = label("")
        column.addView(prayerList)

        column.addView(heading("Work clock-in"))
        column.addView(label("The car's Clock in button opens Darwinbox, signs in and presses Clock in. It never presses Clockout: if it sees Clockout you are already clocked in."))
        column.addView(label("Site address:"))
        column.addView(field(prefs.checkInUrl, "https://alfardan.darwinbox.com/") { prefs.checkInUrl = it })
        column.addView(CheckBox(this).apply {
            text = "Use Microsoft Edge (for Microsoft single sign-on). The phone must be unlocked when it runs."
            isChecked = prefs.checkInUseEdge
            setOnCheckedChangeListener { _, v -> prefs.checkInUseEdge = v }
        })
        edgeButton = Button(this).apply {
            setOnClickListener { EdgeClicker.openSettings(this@MainActivity); toast("Tap Installed apps > Najeem Drive clock-in, and turn it on") }
        }
        column.addView(edgeButton)
        column.addView(Button(this).apply {
            text = "Open Darwinbox in Edge to sign in once"
            setOnClickListener {
                val edge = EdgeClicker.edgePackage(this@MainActivity)
                if (edge == null) toast("Install Microsoft Edge first")
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prefs.checkInUrl)).setPackage(edge))
            }
        })
        column.addView(label("Without Edge (Darwinbox Credentials only):"))
        column.addView(label("Username (only for \"Sign in with Darwinbox Credentials\"; leave empty if you use Single Sign On):"))
        column.addView(field(Secrets.user(this), "Employee ID or email") { Secrets.setUser(this, it.trim()) })
        column.addView(label("Password (kept encrypted on this phone):"))
        column.addView(field(Secrets.password(this), "Password", password = true) { Secrets.setPassword(this, it) })
        column.addView(Button(this).apply {
            text = "Open the site here and sign in once"
            setOnClickListener { startActivity(Intent(this@MainActivity, CheckInSetupActivity::class.java)) }
        })
        checkInStatus = label("")
        column.addView(Button(this).apply {
            text = "Clock in now (test, like the car)"
            setOnClickListener {
                checkInStatus.text = "Working…"
                if (!CheckIn.start(this@MainActivity) { checkInStatus.text = CheckIn.message; if (!CheckIn.isRunning) refreshStatus() }) {
                    toast("Add the site address first")
                }
            }
        })
        column.addView(checkInStatus)
        column.addView(CheckBox(this).apply {
            text = "Clock in by itself when the dashboard opens on weekday mornings (Mon–Fri, 6–11 AM)"
            isChecked = prefs.autoCheckIn
            setOnCheckedChangeListener { _, v -> prefs.autoCheckIn = v }
        })
        column.addView(label("Button names, if your site words them differently:"))
        column.addView(field(prefs.checkInButton, "Clock in") { prefs.checkInButton = it })
        column.addView(field(prefs.checkInDoneButton, "Clockout") { prefs.checkInDoneButton = it })
        column.addView(field(prefs.checkInSignInButton, "Sign in with Darwinbox Credentials") { prefs.checkInSignInButton = it })
        column.addView(field(prefs.checkInSsoButton, "Single Sign On") { prefs.checkInSsoButton = it })

        column.addView(heading("Setup"))
        column.addView(label(SETUP_TEXT))
        setContentView(ScrollView(this).apply { addView(column) })
        refreshStatus()
    }

    private fun field(value: String, hint: String, password: Boolean = false, save: (String) -> Unit) = EditText(this).apply {
        setText(value)
        this.hint = hint
        isSingleLine = true
        if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { save(s?.toString().orEmpty()) }
        })
    }

    private fun refreshStatus() {
        if (::checkInStatus.isInitialized && !CheckIn.isRunning) {
            checkInStatus.text = prefs.lastCheckInResult.ifBlank { "Not run yet." }.let { "Last result: $it" }
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(20), 0, 0)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(8), 0, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val SETUP_TEXT = """1. Allow location when asked, so the dashboard can show the outside temperature.
2. Dua al-Safar is built in, with its lyrics timed.
3. Android Auto settings: tap "Version" 10 times to turn on developer mode, then three-dot menu > Developer settings > tick "Unknown sources".
4. In the car, open the app launcher and pick "Najeem Drive"."""
    }
}
