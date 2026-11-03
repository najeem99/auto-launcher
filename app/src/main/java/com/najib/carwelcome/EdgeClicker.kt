package com.najib.carwelcome

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clocks you in through Microsoft Edge, where your Microsoft (SSO) login lives.
 * Android lets this accessibility service read Edge's screen and tap on it, the
 * way your finger would. It is limited to Edge (see res/xml/edge_clicker.xml)
 * and only ever taps: the sign-in button, your Microsoft account, "Yes" on
 * "Stay signed in?", and the clock-in button. It never taps the clock-out button.
 */
class EdgeClicker : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var report: ((CheckIn.Phase, String) -> Unit)? = null
    private var startedAt = 0L
    private var pressedAt = 0L
    private var lastTapAt = 0L
    private var taps = 0

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; stopRun(); super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; stopRun(); return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Opens the site in Edge and works through it. Results go to [onResult]. */
    fun run(url: String, onResult: (CheckIn.Phase, String) -> Unit) {
        stopRun()
        report = onResult
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            finish(CheckIn.Phase.FAILED, "Unlock your phone, then tap Clock in again")
            return
        }
        val edge = edgePackage(this)
        if (edge == null) { finish(CheckIn.Phase.FAILED, "Install Microsoft Edge on your phone"); return }
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(edge)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A system-bound accessibility service may open apps from the background.
        if (runCatching { startActivity(open) }.isFailure) {
            finish(CheckIn.Phase.FAILED, "Couldn't open Edge"); return
        }
        startedAt = SystemClock.elapsedRealtime()
        pressedAt = 0L
        lastTapAt = 0L
        taps = 0
        onResult(CheckIn.Phase.RUNNING, "Opening Darwinbox in Edge…")
        handler.postDelayed(::tick, 2500L)
    }

    private fun stopRun() { handler.removeCallbacksAndMessages(null); report = null }

    private fun finish(ph: CheckIn.Phase, msg: String) {
        val r = report
        stopRun()
        r?.invoke(ph, msg)
    }

    private fun tick() {
        val r = report ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - startedAt > TIMEOUT_MS) {
            finish(CheckIn.Phase.FAILED,
                if (pressedAt > 0) "Pressed Clock in, but couldn't see it confirmed. Check Edge."
                else "Couldn't find the Clock in button in Edge")
            return
        }
        if (taps > MAX_TAPS) { finish(CheckIn.Phase.FAILED, "Stopped: too many taps without reaching Clock in"); return }
        val prefs = Prefs(this)
        val nodes = edgeNodes()
        if (nodes.isEmpty()) { handler.postDelayed(::tick, 1000L); return }

        fun find(label: String) = match(nodes, label)
        val settle = now - lastTapAt > 2500L // give the page time to change after each tap

        when {
            find(prefs.checkInDoneButton) != null -> {
                val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                finish(CheckIn.Phase.DONE, if (pressedAt > 0) "Clocked in at $time" else "Already clocked in")
                return
            }
            !settle -> {}
            pressedAt > 0 -> {
                // A confirmation pop-up after Clock in.
                CONFIRM.firstNotNullOfOrNull { find(it) }?.let { tap(it) }
            }
            find(prefs.checkInButton)?.let { tap(it) } == true -> {
                pressedAt = now
                r(CheckIn.Phase.RUNNING, "Pressed Clock in…")
            }
            nodes.any { it.isPassword && it.isVisibleToUser } -> {
                finish(CheckIn.Phase.FAILED, "Microsoft wants your password. Sign in once in Edge, then try again.")
                return
            }
            find("Stay signed in?") != null && find("Yes") != null -> { tap(find("Yes")!!); r(CheckIn.Phase.RUNNING, "Signing in…") }
            find("Pick an account") != null -> {
                account(nodes)?.let { tap(it); r(CheckIn.Phase.RUNNING, "Choosing your Microsoft account…") }
            }
            find(prefs.checkInSsoButton)?.let { tap(it) } == true -> r(CheckIn.Phase.RUNNING, "Signing in with Microsoft…")
            else -> r(CheckIn.Phase.RUNNING, "Waiting for Darwinbox…")
        }
        handler.postDelayed(::tick, 1000L)
    }

    /** Every node on Edge's screen. */
    private fun edgeNodes(): List<AccessibilityNodeInfo> {
        val roots = buildList {
            runCatching { windows }.getOrNull()?.forEach { w -> w.root?.let { add(it) } }
            rootInActiveWindow?.let { add(it) }
        }.filter { it.packageName?.toString()?.startsWith("com.microsoft.emmx") == true }
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60 || out.size > 4000) return
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        roots.distinctBy { it.windowId }.forEach { walk(it, 0) }
        return out
    }

    private fun textOf(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription ?: "").toString()

    /** Same rule as the web view: ignore case, spaces and dashes; "Clockout" never matches "Clock in". */
    private fun match(nodes: List<AccessibilityNodeInfo>, label: String): AccessibilityNodeInfo? {
        val want = norm(label)
        if (want.isEmpty()) return null
        return nodes.filter { it.isVisibleToUser }
            .mapNotNull { n ->
                val t = norm(textOf(n))
                when {
                    t == want -> n to 0
                    t.startsWith(want) && t.length <= want.length + 3 -> n to 1
                    else -> null
                }
            }
            .minByOrNull { it.second }?.first
    }

    /** On Microsoft's "Pick an account" page, the tile showing an email address. */
    private fun account(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        nodes.firstOrNull { it.isVisibleToUser && Regex("[^\\s@]+@[^\\s@]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE).containsMatchIn(textOf(it)) }

    /** Taps the node: an accessibility click on it or its clickable parent, else a real tap at its centre. */
    private fun tap(node: AccessibilityNodeInfo): Boolean {
        lastTapAt = SystemClock.elapsedRealtime()
        taps++
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 4) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null
        )
    }

    companion object {
        @Volatile var instance: EdgeClicker? = null
            private set

        private const val TIMEOUT_MS = 90_000L
        private const val MAX_TAPS = 12
        private val CONFIRM = listOf("Yes", "Confirm", "OK", "Submit", "Proceed")
        private val EDGE_PACKAGES = listOf("com.microsoft.emmx", "com.microsoft.emmx.beta", "com.microsoft.emmx.dev", "com.microsoft.emmx.canary")

        fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[\\s\\-_]+"), "")

        fun edgePackage(context: Context): String? = EDGE_PACKAGES.firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

        /** Whether the user has turned on this service in Settings > Accessibility. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, EdgeClicker::class.java).flattenToString()
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
