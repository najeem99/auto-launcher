package com.najib.carwelcome

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * The HR site on the phone screen. Sign in here once; the login is kept for the
 * car's hidden clock-in. "Clock in here" runs the same steps the car does, so
 * you can watch them.
 */
class CheckInSetupActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var runner: CheckIn.Runner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val pad = (8 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            text = "Sign in, then tap Done. Your login is kept for the car."
            setPadding(pad * 2, pad, pad * 2, 0)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, 0)
            addView(Button(context).apply {
                text = "Clock in here"
                setOnClickListener { runHere(prefs) }
            })
            addView(Button(context).apply {
                text = "Done"
                setOnClickListener { finish() }
            })
        }
        web = WebView(this)
        CheckIn.setUp(web)
        web.webViewClient = WebViewClient()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(bar)
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(column)
        web.loadUrl(prefs.checkInUrl.ifBlank { Prefs.DEFAULT_CHECKIN_URL })
    }

    private fun runHere(prefs: Prefs) {
        runner?.cancel()
        status.text = "Working…"
        runner = CheckIn.Runner(web, prefs, visible = true) { phase, msg ->
            runOnUiThread {
                status.text = msg
                if (phase == CheckIn.Phase.DONE || phase == CheckIn.Phase.FAILED) CheckIn.remember(this, msg)
            }
        }.also { it.start(load = true) }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        runner?.cancel()
        web.destroy()
        super.onDestroy()
    }
}
