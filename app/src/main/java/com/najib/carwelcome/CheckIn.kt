package com.najib.carwelcome

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Clocks you in on your HR site (Darwinbox by default) from the car.
 *
 * A web view runs on a private, invisible display on the phone, so it behaves
 * like a real browser tab. It opens the site, signs in if needed (saved
 * session cookies, or your saved username and password), then presses the
 * "Clock in" button. It never presses the opposite button ("Clockout"): seeing
 * that one means you are already clocked in.
 */
object CheckIn {

    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var message = ""
        private set
    @Volatile var finishedAt = 0L
        private set

    private val main = Handler(Looper.getMainLooper())
    private var host: HiddenBrowser? = null
    private var listeners = mutableListOf<() -> Unit>()
    private var appContext: Context? = null

    val isRunning get() = phase == Phase.RUNNING

    /** Short text for the car screen. */
    fun statusLine(prefs: Prefs): String = when (phase) {
        Phase.RUNNING -> message.ifBlank { "Working…" }
        Phase.DONE, Phase.FAILED -> message
        Phase.IDLE -> if (prefs.checkInUrl.isBlank()) "Set up in the phone app" else "Tap to clock in"
    }

    /** Starts a hidden run. Returns false if one is already running or nothing is set up. */
    fun start(context: Context, onUpdate: () -> Unit = {}): Boolean {
        val prefs = Prefs(context)
        if (prefs.checkInUrl.isBlank()) return false
        appContext = context.applicationContext
        if (isRunning) { listeners += onUpdate; return true }
        listeners = mutableListOf(onUpdate)
        update(Phase.RUNNING, "Opening the site…")
        main.post {
            val h = try { HiddenBrowser(context) } catch (e: Exception) {
                update(Phase.FAILED, "Couldn't start the browser"); return@post
            }
            host = h
            Runner(h.web, prefs, visible = false) { ph, msg -> update(ph, msg) }.start()
        }
        return true
    }

    /** True once on a weekday morning (Mon–Fri, 6–11 AM) when the automatic option is on. */
    fun dueAutomatically(context: Context): Boolean {
        val prefs = Prefs(context)
        if (!prefs.autoCheckIn || prefs.checkInUrl.isBlank() || isRunning) return false
        val c = Calendar.getInstance()
        val weekday = c.get(Calendar.DAY_OF_WEEK) in Calendar.MONDAY..Calendar.FRIDAY
        val morning = c.get(Calendar.HOUR_OF_DAY) in 6..10
        return weekday && morning && prefs.lastAutoCheckInDay != today()
    }

    fun markAutoRun(context: Context) { Prefs(context).lastAutoCheckInDay = today() }

    /** Saves the outcome so the phone app can show the last result. */
    fun remember(context: Context, msg: String) {
        val stamp = SimpleDateFormat("EEE d MMM, h:mm a", Locale.US).format(Date())
        Prefs(context).lastCheckInResult = "$msg ($stamp)"
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun update(ph: Phase, msg: String) {
        main.post {
            phase = ph
            message = msg
            if (ph == Phase.DONE || ph == Phase.FAILED) {
                finishedAt = System.currentTimeMillis()
                appContext?.let { remember(it, msg) }
                host?.release()
                host = null
            }
            listeners.toList().forEach { runCatching { it() } }
        }
    }

    /** A web view on an invisible display, so pages load and run as in a visible tab. */
    private class HiddenBrowser(context: Context) {
        private val reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2).apply {
            // Throw frames away so the page never stalls waiting for a free buffer.
            setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, Handler(Looper.getMainLooper()))
        }
        private val display: VirtualDisplay = context.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("najeem-checkin", W, H, 420, reader.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        private val presentation = Presentation(context, display.display)
        val web: WebView

        init {
            val frame = FrameLayout(presentation.context)
            web = WebView(presentation.context)
            frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            presentation.setContentView(frame)
            presentation.show()
        }

        fun release() {
            runCatching { web.stopLoading(); web.destroy() }
            runCatching { presentation.dismiss() }
            runCatching { display.release() }
            runCatching { reader.close() }
            CookieManager.getInstance().flush()
        }

        companion object { const val W = 1080; const val H = 2200 }
    }

    /**
     * Drives one web view through sign-in and the clock-in button. Used hidden
     * (from the car) and visibly (the phone's "Sign in once" screen).
     */
    class Runner(
        private val web: WebView,
        private val prefs: Prefs,
        private val visible: Boolean,
        private val report: (Phase, String) -> Unit,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val startedAt = SystemClock.elapsedRealtime()
        private var loginTries = 0
        private var pressedAt = 0L
        private var confirmTries = 0
        private var finished = false
        private var loadFailed: String? = null

        @SuppressLint("SetJavaScriptEnabled")
        fun start(load: Boolean = true) {
            setUp(web)
            web.webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) loadFailed = "Couldn't reach the site. Check the phone's internet."
                }
            }
            if (load) web.loadUrl(prefs.checkInUrl)
            handler.postDelayed(::poll, 3000L)
        }

        fun cancel() { finished = true; handler.removeCallbacksAndMessages(null) }

        private fun finish(ph: Phase, msg: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            CookieManager.getInstance().flush()
            report(ph, msg)
        }

        private fun poll() {
            if (finished) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            loadFailed?.let { finish(Phase.FAILED, it); return }
            if (elapsed > TIMEOUT_MS) {
                finish(Phase.FAILED, if (pressedAt > 0) "Pressed ${prefs.checkInButton}, but couldn't confirm it worked"
                    else "Couldn't find the ${prefs.checkInButton} button")
                return
            }
            val args = JSONObject()
                .put("action", prefs.checkInButton)
                .put("done", prefs.checkInDoneButton)
                .put("signIn", prefs.checkInSignInButton)
                .put("user", Secrets.user(web.context))
                .put("pass", Secrets.password(web.context))
                .put("allowLogin", loginTries < 3)
                .put("pressed", pressedAt > 0)
                .put("confirm", pressedAt > 0 && confirmTries < 3)
            web.evaluateJavascript("($SCRIPT)($args)") { raw -> handle(unquote(raw)) }
        }

        private fun handle(result: String) {
            if (finished) return
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            val next = when {
                result.startsWith("done") -> {
                    finish(Phase.DONE, if (pressedAt > 0) "Clocked in at $time" else "Already clocked in")
                    return
                }
                result == "pressed" -> { pressedAt = SystemClock.elapsedRealtime(); report(Phase.RUNNING, "Pressed ${prefs.checkInButton}…"); 3000L }
                result == "confirmed" -> { confirmTries++; 2500L }
                result == "login" -> { loginTries++; report(Phase.RUNNING, "Signing in…"); 4000L }
                result == "signin" -> { report(Phase.RUNNING, "Signing in…"); 3000L }
                result == "needlogin" -> {
                    finish(Phase.FAILED, "Sign in needed. Open the app on your phone and sign in once.")
                    return
                }
                result == "loading" -> 1500L
                else -> {
                    if (pressedAt == 0L) report(Phase.RUNNING, "Looking for ${prefs.checkInButton}…")
                    1500L
                }
            }
            handler.postDelayed(::poll, next)
        }

        private fun unquote(raw: String?): String =
            raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: ""
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setUp(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // Attendance sites often check where you are; the app already has location permission.
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, true)
            }
        }
    }

    private const val TIMEOUT_MS = 75_000L

    /**
     * Runs in the page. Returns what it did: done, pressed, confirmed, login,
     * signin, needlogin, loading or none. Labels are compared without spaces,
     * dashes or case, so "Clock in", "Clock-In" and "CLOCKIN" all match, while
     * "Clockout" never matches "Clock in".
     */
    private val SCRIPT = """
function(a){
  function norm(s){return (s||'').toString().toLowerCase().replace(/[\s\-_]+/g,'');}
  function vis(el){ if(!el||el.disabled) return false; var r=el.getBoundingClientRect(); if(r.width<2||r.height<2) return false;
    var s=getComputedStyle(el); return s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0'; }
  function docs(){ var out=[document]; var f=document.querySelectorAll('iframe,frame');
    for(var i=0;i<f.length;i++){ try{ var d=f[i].contentDocument; if(d&&d.body) out.push(d);}catch(e){} } return out; }
  function all(sel){ var r=[]; docs().forEach(function(d){ r=r.concat([].slice.call(d.querySelectorAll(sel))); }); return r.filter(vis); }
  function label(el){ return el.innerText||el.value||el.getAttribute('aria-label')||el.getAttribute('title')||''; }
  var CLICK='button,a,input[type=submit],input[type=button],[role=button],[onclick],[tabindex]';
  function find(text){
    var want=norm(text); if(!want) return null;
    var best=null, bestScore=1e9;
    all(CLICK+',span,div,label,li').forEach(function(el){
      var t=norm(label(el)); if(!t) return;
      var exact=t===want, near=!exact&&t.indexOf(want)===0&&t.length<=want.length+3;
      if(!exact&&!near) return;
      var real=el.matches(CLICK)?0:5;
      var score=(exact?0:10)+real+t.length/1000;
      if(score<bestScore){best=el;bestScore=score;}
    });
    if(best&&!best.matches(CLICK)){ var up=best.closest(CLICK); if(up&&vis(up)&&norm(label(up)).length<=norm(label(best)).length+3) best=up; }
    return best;
  }
  function press(el){ try{el.scrollIntoView({block:'center'});}catch(e){}
    ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(n){ try{el.dispatchEvent(new MouseEvent(n,{bubbles:true,cancelable:true,view:window}));}catch(e){} });
    el.click(); }
  function setVal(el,v){ var p=Object.getPrototypeOf(el); var d=Object.getOwnPropertyDescriptor(p,'value');
    if(d&&d.set) d.set.call(el,v); else el.value=v;
    el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }
  function submitNear(input){
    var form=input.form, b=null;
    if(form){ b=[].slice.call(form.querySelectorAll('button[type=submit],input[type=submit],button:not([type])')).filter(vis)[0]; }
    if(!b){ ['sign in','log in','login','signin','continue','next','submit'].some(function(t){ b=find(t); return !!b; }); }
    if(b) press(b); else if(form) form.submit();
  }

  var doc=document;
  if(doc.readyState!=='complete' && !doc.body) return 'loading';

  // 1. Already clocked in, or the press worked.
  if(a.done && find(a.done)) return 'done';

  // 2. After pressing, accept a confirmation pop-up if one appears.
  if(a.confirm){
    var c=null; ['yes','confirm','ok','submit','proceed'].some(function(t){ c=find(t); return !!c; });
    if(!c && a.action) c=find(a.action);
    if(c){ press(c); return 'confirmed'; }
  }

  // 3. The clock-in button itself.
  if(!a.pressed){ var act=find(a.action); if(act){ press(act); return 'pressed'; } }

  // 4. Sign-in pages.
  var pw=all('input[type=password]')[0];
  if(pw){
    if(!a.pass||!a.allowLogin) return 'needlogin';
    var scope=pw.form||pw.ownerDocument;
    var u=[].slice.call(scope.querySelectorAll('input[type=email],input[type=text],input[type=tel],input:not([type])')).filter(vis)[0];
    if(u&&a.user&&!u.value) setVal(u,a.user);
    setVal(pw,a.pass); submitNear(pw); return 'login';
  }
  var userBox=all('input[type=email],input[name*=user i],input[name*=login i],input[name*=email i],input[id*=user i],input[id*=email i]')[0];
  if(userBox&&a.user&&a.allowLogin&&!userBox.value){ setVal(userBox,a.user); submitNear(userBox); return 'login'; }
  if(a.signIn&&a.pass){ var s=find(a.signIn); if(s){ press(s); return 'signin'; } }
  if(a.signIn&&!a.pass&&find(a.signIn)) return 'needlogin';
  return doc.readyState==='complete'?'none':'loading';
}
""".trimIndent()
}
