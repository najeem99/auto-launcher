package com.najib.carwelcome.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.najib.carwelcome.CrashLog

/**
 * Shows YouTube's mobile site on the car screen. A web view runs on a private
 * virtual display that draws into Android Auto's surface; taps on the car screen
 * are passed to it. While the car is not parked the video is paused, taps are
 * ignored and a notice covers the screen.
 */
class YouTubeLayer(
    private val carContext: CarContext,
    startUrl: String,
    private val parked: ParkedDetector,
) : SurfaceCallback {

    var currentUrl: String = startUrl
        private set

    /** Video fills the whole car screen, page chrome hidden. */
    var fullScreen = false
        private set

    /** Called on the main thread whenever [fullScreen] changes. */
    var onFullScreenChanged: (() -> Unit)? = null

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var web: WebView? = null
    private var notice: TextView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var visible: Rect? = null
    private var surfaceW = 0
    private var surfaceH = 0
    private var state = ParkedDetector.State.CHECKING
    private val handler = Handler(Looper.getMainLooper())

    private val watch = object : Runnable {
        override fun run() {
            CrashLog.guard("YouTube parked check") { applyState(parked.state()) }
            handler.postDelayed(this, 500L)
        }
    }

    /** The surface the player is on, so it can be rebuilt if the web page's process dies. */
    private var shownOn: SurfaceContainer? = null

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        detach()
        shownOn = surfaceContainer
        surfaceW = surfaceContainer.width
        surfaceH = surfaceContainer.height
        try {
            val dm = carContext.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay(
                "najeem-youtube", surfaceContainer.width, surfaceContainer.height,
                surfaceContainer.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            display = vd
            val p = Presentation(carContext, vd.display)
            p.setContentView(buildViews(p))
            p.show()
            presentation = p
            applyPadding()
            state = ParkedDetector.State.CHECKING
            applyState(parked.state(), force = true)
            handler.removeCallbacks(watch)
            handler.post(watch)
        } catch (e: Exception) {
            CrashLog.caught("YouTube player start", e)
            detach()
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        applyPadding()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        shownOn = null
        detach()
    }

    /**
     * The web page runs in a separate Android process, which can die (out of memory
     * during a long video, or a WebView update). Unhandled, Android then ends this whole
     * app, and the car screen drops out. Instead, throw that web view away and open
     * YouTube again on the same screen.
     */
    private fun onPageProcessGone(view: WebView) {
        CrashLog.caught("YouTube page process ended", IllegalStateException("WebView renderer gone"))
        if (view !== web) { runCatching { view.destroy() }; return }
        web = null
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.destroy() }
        handler.post {
            val c = shownOn ?: return@post
            if (c.surface?.isValid == true) onSurfaceAvailable(c)
        }
    }

    /** Keeps the page clear of Android Auto's buttons, except in full screen. */
    private fun applyPadding() {
        val r = root ?: return
        val area = visible
        if (fullScreen || area == null || surfaceW <= 0) {
            r.setPadding(0, 0, 0, 0)
        } else {
            r.setPadding(area.left, area.top,
                (surfaceW - area.right).coerceAtLeast(0), (surfaceH - area.bottom).coerceAtLeast(0))
        }
    }

    /**
     * Stretches the playing video over the whole car screen. [onResult] gets false
     * (and nothing changes) when the page has no video to show.
     */
    fun enterFullScreen(onResult: (Boolean) -> Unit) {
        val w = web ?: return onResult(false)
        w.evaluateJavascript(FS_ON_JS) { res ->
            val ok = res?.contains("ok") == true
            if (ok && web === w) setFullScreen(true)
            onResult(ok && web === w)
        }
    }

    fun exitFullScreen() {
        fullscreenView?.let { v ->
            fullscreenCallback?.onCustomViewHidden()
            // In case the page didn't call onHideCustomView itself.
            (v.parent as? ViewGroup)?.removeView(v)
            fullscreenView = null
            fullscreenCallback = null
            web?.visibility = View.VISIBLE
        }
        web?.evaluateJavascript(FS_OFF_JS, null)
        setFullScreen(false)
    }

    private fun setFullScreen(on: Boolean) {
        if (fullScreen == on) return
        fullScreen = on
        applyPadding()
        onFullScreenChanged?.invoke()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildViews(p: Presentation): View {
        val ctx = p.context
        val frame = FrameLayout(ctx).apply { setBackgroundColor(Color.parseColor("#0B1220")) }
        val w = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Stay on YouTube; don't hand links to other apps on the phone.
                    val host = request.url.host.orEmpty()
                    return !(host.endsWith("youtube.com") || host.endsWith("google.com") ||
                        host.endsWith("youtu.be") || host.endsWith("gstatic.com"))
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onPageProcessGone(view)
                    return true // handled: keep the app running
                }
                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    if (state != ParkedDetector.State.PARKED) pauseVideo()
                    // A reload drops the injected style; put it back.
                    if (fullScreen && fullscreenView == null) view.evaluateJavascript(FS_ON_JS, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenView?.let { frame.removeView(it) }
                    fullscreenView = view
                    fullscreenCallback = callback
                    frame.addView(view, 0, FrameLayout.LayoutParams(MATCH, MATCH))
                    this@apply.visibility = View.INVISIBLE
                    notice?.bringToFront()
                    setFullScreen(true) // YouTube's own full-screen button
                }
                override fun onHideCustomView() {
                    fullscreenView?.let { frame.removeView(it) }
                    fullscreenView = null
                    fullscreenCallback = null
                    this@apply.visibility = View.VISIBLE
                    this@apply.evaluateJavascript(FS_OFF_JS, null)
                    setFullScreen(false)
                }
            }
            loadUrl(currentUrl)
        }
        web = w
        frame.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        val n = TextView(ctx).apply {
            setBackgroundColor(Color.argb(235, 11, 18, 32))
            setTextColor(Color.parseColor("#EEF2FA"))
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            visibility = View.GONE
        }
        notice = n
        frame.addView(n, FrameLayout.LayoutParams(MATCH, MATCH))
        root = frame
        return frame
    }

    /** Pauses or allows playback as the parked state changes. */
    private fun applyState(newState: ParkedDetector.State, force: Boolean = false) {
        val changed = newState != state
        state = newState
        val n = notice ?: return
        if (newState == ParkedDetector.State.PARKED) {
            if (changed || force) n.visibility = View.GONE
        } else {
            n.text = "Video is paused while driving.\nIt comes back when you stop, or tap the P button if you're parked."
            n.visibility = View.VISIBLE
            n.bringToFront()
            pauseVideo() // keep it paused even if the page tries to autoplay
        }
    }

    fun pauseVideo() {
        web?.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();});", null)
    }

    /** A tap on the car screen, passed to the page only while parked. */
    override fun onClick(x: Float, y: Float) {
        if (state != ParkedDetector.State.PARKED) return
        val r = root ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        r.dispatchTouchEvent(down)
        r.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (state != ParkedDetector.State.PARKED || fullScreen) return
        scrollBy(distanceY)
    }

    fun pageDown() = scrollPage(0.8f)
    fun pageUp() = scrollPage(-0.8f)

    private fun scrollPage(fraction: Float) {
        web?.evaluateJavascript("window.scrollBy(0, window.innerHeight * $fraction);", null)
    }

    private fun scrollBy(px: Float) {
        val css = px / (carContext.resources.displayMetrics.density.coerceAtLeast(1f))
        web?.evaluateJavascript("window.scrollBy(0, $css);", null)
    }

    fun load(url: String) {
        currentUrl = url
        web?.loadUrl(url)
    }

    /** Back inside YouTube; returns false when there is nothing to go back to. */
    fun goBack(): Boolean {
        if (fullScreen) { exitFullScreen(); return true }
        val w = web ?: return false
        if (!w.canGoBack()) return false
        w.goBack()
        return true
    }

    private fun detach() {
        handler.removeCallbacks(watch)
        web?.let {
            runCatching { currentUrl = it.url ?: currentUrl }
            runCatching { it.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();});", null) }
            runCatching { (it.parent as? ViewGroup)?.removeView(it) }
            runCatching { it.stopLoading() }
            runCatching { it.destroy() }
        }
        web = null
        notice = null
        root = null
        fullscreenView = null
        fullscreenCallback = null
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { display?.release() }
        display = null
    }

    fun release() = detach()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val HOME = "https://m.youtube.com/"

        /**
         * Pins YouTube's player to the whole viewport and hides the page around it.
         * Re-marks the player every second, since YouTube swaps elements as it
         * moves between videos. Returns "ok", or "novideo" when there is nothing to show.
         */
        private val FS_ON_JS = """
(function(){
  function player(){
    var v=document.querySelector('video');
    return document.querySelector('#movie_player')||(v&&v.closest('.html5-video-player'))||(v&&v.parentElement)||null;
  }
  if(!player()) return 'novideo';
  var s=document.getElementById('najeem-fs-style');
  if(!s){
    s=document.createElement('style');s.id='najeem-fs-style';
    s.textContent=
      'html,body{overflow:hidden!important;background:#000!important}'+
      '.najeem-fs-anc{transform:none!important;filter:none!important;contain:none!important;will-change:auto!important;z-index:auto!important}'+
      '.najeem-fs{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;transform:none!important;z-index:2147483647!important;background:#000!important}'+
      '.najeem-fs .html5-video-container{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important}'+
      '.najeem-fs video{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important}'+
      'ytm-mobile-topbar-renderer,header,ytm-pivot-bar-renderer,.mobile-topbar-header{display:none!important}';
    (document.head||document.documentElement).appendChild(s);
  }
  function mark(){
    var p=player(); if(!p) return;
    if(!p.classList.contains('najeem-fs')){
      document.querySelectorAll('.najeem-fs').forEach(function(e){e.classList.remove('najeem-fs');});
      document.querySelectorAll('.najeem-fs-anc').forEach(function(e){e.classList.remove('najeem-fs-anc');});
      p.classList.add('najeem-fs');
      for(var a=p.parentElement;a&&a!==document.body&&a!==document.documentElement;a=a.parentElement) a.classList.add('najeem-fs-anc');
      window.dispatchEvent(new Event('resize'));
    }
  }
  mark();
  window.scrollTo(0,0);
  if(window.__najeemFs) clearInterval(window.__najeemFs);
  window.__najeemFs=setInterval(mark,1000);
  setTimeout(function(){window.dispatchEvent(new Event('resize'));},300);
  return 'ok';
})();
"""

        private val FS_OFF_JS = """
(function(){
  if(window.__najeemFs){clearInterval(window.__najeemFs);window.__najeemFs=0;}
  var s=document.getElementById('najeem-fs-style'); if(s) s.remove();
  document.querySelectorAll('.najeem-fs').forEach(function(e){e.classList.remove('najeem-fs');});
  document.querySelectorAll('.najeem-fs-anc').forEach(function(e){e.classList.remove('najeem-fs-anc');});
  window.dispatchEvent(new Event('resize'));
  return 'off';
})();
"""

        fun searchUrl(q: String) = "https://m.youtube.com/results?search_query=" + android.net.Uri.encode(q)
    }
}
