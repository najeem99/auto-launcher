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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Phone-side settings: your name, the Dua al-Safar recording (pick a file or
 * download a link), syncing its lyrics, clock format, and a test button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var recordingStatus: TextView
    private lateinit var syncPanel: LinearLayout
    private lateinit var syncLine: TextView
    private lateinit var syncButton: Button
    private lateinit var checkInStatus: TextView
    private val main = Handler(Looper.getMainLooper())
    private var syncTimes = mutableListOf<Int>()

    private lateinit var overlayButton: Button

    override fun onResume() {
        super.onResume()
        if (::overlayButton.isInitialized) {
            val ok = Settings.canDrawOverlays(this)
            overlayButton.text = if (ok) "Allowed: Maps and Claude can open" else "Allow opening Maps and Claude"
            overlayButton.isEnabled = !ok
        }
        if (::checkInStatus.isInitialized) refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Welcome.attach(this)
        // Fine location lets the YouTube player tell from GPS speed that the car is parked.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, "com.google.android.gms.permission.CAR_SPEED"), 1)
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
        column.addView(label("Allow \"Display over other apps\" so the car's Maps, Home, Office and Claude buttons can open those apps."))
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

        column.addView(heading("Dua al-Safar recording"))
        recordingStatus = label("")
        column.addView(recordingStatus)
        column.addView(Button(this).apply {
            text = "Replace with an audio file on this phone"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"),
                    PICK_AUDIO
                )
            }
        })
        column.addView(label("Or paste a direct link to an MP3:"))
        val urlField = EditText(this).apply { hint = "https://…/dua-al-safar.mp3"; isSingleLine = true }
        column.addView(urlField)
        column.addView(Button(this).apply {
            text = "Download"
            setOnClickListener { download(urlField.text.toString().trim()) }
        })
        column.addView(Button(this).apply {
            text = "Use the built-in recording"
            setOnClickListener {
                DuaPlayer.stop()
                DuaPlayer.file(this@MainActivity).delete()
                prefs.duaTimings = null
                toast("Using the built-in recording")
                refreshStatus()
            }
        })
        column.addView(Button(this).apply {
            text = "Play / stop"
            setOnClickListener {
                if (DuaPlayer.isPlaying) DuaPlayer.stop()
                else if (!DuaPlayer.play(this@MainActivity)) toast("Add a recording first")
            }
        })

        column.addView(heading("Sync the lyrics"))
        column.addView(label("Makes the Arabic on the car screen change exactly with the recording. Tap Start, then tap Next line the moment each new line begins."))
        syncPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        syncLine = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.END
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, dp(8), 0, dp(8))
        }
        syncButton = Button(this).apply { text = "Next line"; setOnClickListener { markNextLine() } }
        syncPanel.addView(syncLine)
        syncPanel.addView(syncButton)
        column.addView(Button(this).apply {
            text = "Start syncing"
            setOnClickListener { startSync() }
        })
        column.addView(syncPanel)
        column.addView(Button(this).apply {
            text = "Reset lyric timing"
            setOnClickListener { prefs.duaTimings = null; toast("Lyric timing reset"); refreshStatus() }
        })

        column.addView(heading("Work clock-in"))
        column.addView(label("The car's Clock in button opens your HR site hidden on this phone, signs in and presses Clock in. It never presses Clockout: if it sees Clockout you are already clocked in."))
        column.addView(label("Site address:"))
        column.addView(field(prefs.checkInUrl, "https://alfardan.darwinbox.com/") { prefs.checkInUrl = it })
        column.addView(label("Username (only for \"Sign in with Darwinbox Credentials\"; leave empty if you use Single Sign On):"))
        column.addView(field(Secrets.user(this), "Employee ID or email") { Secrets.setUser(this, it.trim()) })
        column.addView(label("Password (kept encrypted on this phone):"))
        column.addView(field(Secrets.password(this), "Password", password = true) { Secrets.setPassword(this, it) })
        column.addView(Button(this).apply {
            text = "Open the site and sign in once"
            setOnClickListener { startActivity(Intent(this@MainActivity, CheckInSetupActivity::class.java)) }
        })
        checkInStatus = label("")
        column.addView(Button(this).apply {
            text = "Clock in now (hidden, like the car)"
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

        column.addView(heading("Setup"))
        column.addView(label(SETUP_TEXT))
        setContentView(ScrollView(this).apply { addView(column) })
        refreshStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_AUDIO || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        thread {
            val ok = runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    saveRecording { out -> input.copyTo(out) }
                }
            }.isSuccess
            main.post { afterNewRecording(ok) }
        }
    }

    private fun download(link: String) {
        if (!link.startsWith("http")) { toast("Paste a link that starts with https://"); return }
        toast("Downloading…")
        thread {
            val ok = runCatching {
                val conn = URL(link).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                conn.inputStream.use { input -> saveRecording { out -> input.copyTo(out) } }
                conn.disconnect()
            }.isSuccess
            main.post { afterNewRecording(ok) }
        }
    }

    private fun saveRecording(write: (java.io.OutputStream) -> Unit) {
        DuaPlayer.stop()
        val tmp = File(filesDir, "dua_download.tmp")
        tmp.outputStream().use(write)
        check(tmp.length() > 10_000) { "File too small to be a recording" }
        val dest = DuaPlayer.file(this)
        dest.delete()
        check(tmp.renameTo(dest))
    }

    private fun afterNewRecording(ok: Boolean) {
        if (ok) {
            prefs.duaTimings = null
            toast("Recording saved. Tap Start syncing to line up the words.")
        } else {
            toast("Couldn't save that recording. Try another file or link.")
        }
        refreshStatus()
    }

    private fun startSync() {
        if (!DuaPlayer.play(this) { main.post { finishSync() } }) { toast("Add a recording first"); return }
        syncTimes = mutableListOf(0)
        syncPanel.visibility = View.VISIBLE
        showSyncLine()
    }

    private fun markNextLine() {
        if (!DuaPlayer.isPlaying) return
        syncTimes += DuaPlayer.positionMs
        if (syncTimes.size >= DuaSafar.LINES.size) {
            prefs.duaTimings = syncTimes.toList()
            toast("Lyrics synced")
            syncButton.isEnabled = false
        }
        showSyncLine()
    }

    private fun showSyncLine() {
        val i = (syncTimes.size - 1).coerceIn(DuaSafar.LINES.indices)
        syncLine.text = DuaSafar.LINES[i].arabic
        syncButton.isEnabled = syncTimes.size < DuaSafar.LINES.size
        syncButton.text = if (syncButton.isEnabled) "Next line (${syncTimes.size + 1} of ${DuaSafar.LINES.size})" else "All lines marked"
    }

    private fun finishSync() {
        if (syncTimes.size in 2 until DuaSafar.LINES.size) toast("The recording ended before all lines were marked. Try again.")
        syncPanel.visibility = View.GONE
        syncButton.isEnabled = true
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
        recordingStatus.text = when {
            !DuaPlayer.hasCustomRecording(this) && prefs.duaTimings == null ->
                "Using the built-in recording, with its lyrics already timed. You can replace it below."
            !DuaPlayer.hasCustomRecording(this) -> "Using the built-in recording, with your own lyric timing."
            prefs.duaTimings != null -> "Using your own recording. Lyrics are synced."
            else -> "Using your own recording. Lyrics use automatic timing; sync them below for a perfect match."
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
        private const val PICK_AUDIO = 7
        private const val SETUP_TEXT = """1. Allow location when asked, so the dashboard can show the outside temperature.
2. Dua al-Safar is built in. To use a different recording, add it above and sync the lyrics once.
3. Android Auto settings: tap "Version" 10 times to turn on developer mode, then three-dot menu > Developer settings > tick "Unknown sources".
4. In the car, open the app launcher and pick "Najeem Drive"."""
    }
}
