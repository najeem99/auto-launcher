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
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

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

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var web: WebView? = null
    private var notice: TextView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var visible: Rect? = null
    private var state = ParkedDetector.State.CHECKING
    private val handler = Handler(Looper.getMainLooper())

    private val watch = object : Runnable {
        override fun run() {
            applyState(parked.state())
            handler.postDelayed(this, 500L)
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        detach()
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
            visible?.let { applyInsets(it, surfaceContainer) }
            state = ParkedDetector.State.CHECKING
            applyState(parked.state(), force = true)
            handler.removeCallbacks(watch)
            handler.post(watch)
        } catch (e: Exception) {
            detach()
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        root?.let { r ->
            r.setPadding(visibleArea.left, visibleArea.top,
                (r.width - visibleArea.right).coerceAtLeast(0), (r.height - visibleArea.bottom).coerceAtLeast(0))
        }
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = detach()

    private fun applyInsets(area: Rect, c: SurfaceContainer) {
        root?.setPadding(area.left, area.top, (c.width - area.right).coerceAtLeast(0), (c.height - area.bottom).coerceAtLeast(0))
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
                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    if (state != ParkedDetector.State.PARKED) pauseVideo()
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
                }
                override fun onHideCustomView() {
                    fullscreenView?.let { frame.removeView(it) }
                    fullscreenView = null
                    fullscreenCallback = null
                    this@apply.visibility = View.VISIBLE
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
        if (state != ParkedDetector.State.PARKED) return
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
        fullscreenView?.let { fullscreenCallback?.onCustomViewHidden(); return true }
        val w = web ?: return false
        if (!w.canGoBack()) return false
        w.goBack()
        return true
    }

    private fun detach() {
        handler.removeCallbacks(watch)
        web?.let {
            currentUrl = it.url ?: currentUrl
            it.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();});", null)
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        web = null
        notice = null
        root = null
        fullscreenView = null
        fullscreenCallback = null
        runCatching { presentation?.dismiss() }
        presentation = null
        display?.release()
        display = null
    }

    fun release() = detach()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val HOME = "https://m.youtube.com/"
        fun searchUrl(q: String) = "https://m.youtube.com/results?search_query=" + android.net.Uri.encode(q)
    }
}
