package com.najib.carwelcome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Phone-side settings: your name, what plays on start, clock format, and a test button. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(label("Your name (used in the welcome)"))
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
            text = "Recite Dua al-Safar after the welcome"
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
            setOnClickListener {
                Weather.refresh(this@MainActivity)
                Welcome.run(this@MainActivity)
            }
        })

        column.addView(label(SETUP_TEXT).apply { setPadding(0, pad, 0, 0) })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    companion object {
        private const val SETUP_TEXT = """Setup (one time):
1. Allow location when asked, so the dashboard can show the outside temperature.
2. For Dua al-Safar in Arabic, install the Arabic voice: Settings > Speech > Text-to-speech > Google > Install voice data > Arabic. Without it the meaning is read in English.
3. Android Auto settings: tap "Version" 10 times to turn on developer mode, then three-dot menu > Developer settings > tick "Unknown sources".
4. In the car, open the app launcher and pick "Najeem Drive"."""
    }
}
