package com.najib.carwelcome

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Phone-side settings screen: greeting text, reciter, and a test button. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var greeter: Greeter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(label("Greeting spoken when the phone connects to the car"))
        val greeting = EditText(this).apply {
            setText(prefs.greetingText)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.greetingText = s?.toString()?.ifBlank { Prefs.DEFAULT_GREETING }
                        ?: Prefs.DEFAULT_GREETING
                }
            })
        }
        column.addView(greeting)

        column.addView(Button(this).apply {
            text = "Test greeting"
            setOnClickListener {
                val g = greeter ?: Greeter(this@MainActivity).also { greeter = it }
                g.speak(prefs.greetingText) {}
            }
        })

        column.addView(CheckBox(this).apply {
            text = "Greet me when I connect to the car"
            isChecked = prefs.greetOnConnect
            setOnCheckedChangeListener { _, v -> prefs.greetOnConnect = v }
        })
        column.addView(CheckBox(this).apply {
            text = "Play the last surah after the greeting"
            isChecked = prefs.autoPlayAfterGreeting
            setOnCheckedChangeListener { _, v -> prefs.autoPlayAfterGreeting = v }
        })

        column.addView(label("Reciter"))
        column.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                RECITERS.map { it.name }
            )
            setSelection(RECITERS.indexOf(reciterById(prefs.reciterId)))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.reciterId = RECITERS[pos].id
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
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

    override fun onDestroy() {
        greeter?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SETUP_TEXT = """Setup on your phone (one time):
1. Open Settings > Apps > Android Auto > Additional settings in the app (or open the Android Auto app).
2. Scroll to "Version" and tap it 10 times, then accept to turn on developer mode.
3. Tap the three-dot menu > Developer settings > tick "Unknown sources".
4. In Android Auto settings, turn on "Start music automatically" (wording varies by version).
5. Connect to the car, open the app launcher on the car screen and pick "Car Welcome" once. From then on Android Auto resumes it when you connect, and you'll hear your greeting."""
    }
}
