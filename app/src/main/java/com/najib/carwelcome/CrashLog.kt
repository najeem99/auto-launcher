package com.najib.carwelcome

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why the car app stopped, so a problem in the car can be read (and shared)
 * later in the phone app's Settings.
 *
 * - A crash (an uncaught error) is written to a small file just before the app dies.
 * - An error the app caught and survived is written too, marked "caught", via [guard].
 * - On Android 11 and later, Android's own record of how the app last ended (crash,
 *   "not responding", killed for memory, ...) is added when the report is read.
 */
object CrashLog {
    private const val TAG = "NajeemDrive"
    private const val FILE = "car_problems.txt"
    private const val MAX_BYTES = 24_000
    private var file: File? = null

    /** Called once when the app process starts. */
    fun install(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write("CRASH on ${thread.name}", e) }
            if (previous != null) previous.uncaughtException(thread, e)
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10) }
        }
    }

    /**
     * Runs [block], and if it throws, notes the error and carries on instead of
     * letting it take the car app down. Returns null when it threw.
     */
    inline fun <T> guard(where: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        caught(where, e)
        null
    }

    /** Notes an error that was caught and survived. */
    fun caught(where: String, e: Throwable) {
        Log.w(TAG, "caught in $where", e)
        // The same error every frame is written once a minute, not eight times a second.
        val sig = "$where|$e|${e.stackTrace.firstOrNull()}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (sig == lastSig && now - lastSigAt < 60_000L) return
        lastSig = sig
        lastSigAt = now
        runCatching { write("caught in $where", e) }
    }

    @Volatile private var lastSig = ""
    @Volatile private var lastSigAt = 0L

    @Synchronized
    private fun write(title: String, e: Throwable) {
        val f = file ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        // Keep the report short: the first 25 lines of the trace say where it happened.
        val trace = sw.toString().lineSequence().take(25).joinToString("\n")
        val entry = "=== ${stamp(System.currentTimeMillis())} · $title\n$trace\n\n"
        val old = if (f.exists()) f.readText() else ""
        var text = entry + old // newest first
        if (text.length > MAX_BYTES) text = text.substring(0, MAX_BYTES)
        f.writeText(text)
    }

    /** Everything we know, newest first, for the Settings screen and for sharing. */
    fun report(context: Context): String {
        val sb = StringBuilder()
        val v = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})" }
        }.getOrDefault("?")
        sb.append("Najeem Drive $v · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n")
        val exits = exitReasons(context)
        if (exits.isNotBlank()) sb.append("How the app last ended (from Android):\n").append(exits).append('\n')
        val logged = File(context.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty()
        sb.append(if (logged.isBlank()) "No errors recorded by the app." else "Errors recorded by the app:\n$logged")
        return sb.toString()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /** Android 11+: the last few times the app's process ended, and why. */
    private fun exitReasons(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val list = am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
            buildString {
                for (x in list) {
                    append("• ").append(stamp(x.timestamp)).append(" · ").append(reasonName(x.reason))
                    x.description?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it.take(200)) }
                    append('\n')
                    // A freeze ("not responding") comes with a dump of what the app was doing.
                    if (x.reason == ApplicationExitInfo.REASON_ANR) {
                        val head = runCatching {
                            x.traceInputStream?.bufferedReader()?.useLines { lines ->
                                lines.dropWhile { !it.startsWith("\"main\"") }.take(30).joinToString("\n")
                            }
                        }.getOrNull()
                        if (!head.isNullOrBlank()) append(head).append("\n\n")
                    }
                }
            }
        }.getOrDefault("")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "crashed"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed (native)"
        ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "closed for low memory"
        ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by Android"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "force stopped / updated"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by you"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "closed for using too much battery or CPU"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "closed because a helper died"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "closed after a permission change"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_OTHER -> "closed (other)"
        else -> "ended (code $r)"
    }

    private fun stamp(t: Long) = SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.US).format(Date(t))
}
