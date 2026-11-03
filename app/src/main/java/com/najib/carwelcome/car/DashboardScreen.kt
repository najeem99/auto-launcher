package com.najib.carwelcome.car

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.najib.carwelcome.CheckIn
import com.najib.carwelcome.R
import com.najib.carwelcome.Weather
import com.najib.carwelcome.Welcome

/** The front page: a full-screen drawn dashboard plus the Dua, Map, Utilities and YouTube buttons. */
class DashboardScreen(carContext: CarContext) : Screen(carContext) {

    private var shownButton = Welcome.duaButton()
    private val renderer = DashboardRenderer(carContext, onTile = { tile -> onTile(tile) }) {
        // Refresh the button strip when the Dua button needs to change.
        val now = Welcome.duaButton()
        if (now != shownButton) { shownButton = now; invalidate() }
    }
    private val main = Handler(Looper.getMainLooper())
    /** Car (or GPS) speed, for the blinking P when the car is stopped. */
    private val parked = ParkedDetector(carContext)

    init {
        Welcome.attach(carContext)
        renderer.isStopped = { parked.isStopped() }
        SurfaceHub.reset()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(SurfaceHub)
        SurfaceHub.show(renderer)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SurfaceHub.show(renderer) // take the screen back after YouTube
                parked.start()
                // The weather arrives on a background thread; draw on the main thread only.
                Weather.refresh(carContext) { main.post { renderer.draw() } }
                if (CheckIn.dueAutomatically(carContext)) {
                    CheckIn.markAutoRun(carContext)
                    startCheckIn(carContext, renderer::draw)
                }
            }
            override fun onStop(owner: LifecycleOwner) = parked.stop()
            override fun onDestroy(owner: LifecycleOwner) {
                SurfaceHub.release(renderer)
                renderer.stop()
            }
        })
    }

    override fun onGetTemplate(): Template {
        shownButton = Welcome.duaButton()
        val strip = ActionStrip.Builder()
        if (shownButton != Welcome.DuaButton.HIDDEN) {
            val playing = shownButton == Welcome.DuaButton.PAUSE
            strip.addAction(
                Action.Builder()
                    .setTitle(if (playing) "Pause" else "Dua")
                    .setIcon(icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play))
                    .setOnClickListener {
                        if (!Welcome.pressDua(carContext)) {
                            CarToast.makeText(carContext, "Couldn't play Dua al-Safar", CarToast.LENGTH_LONG).show()
                        }
                        shownButton = Welcome.duaButton()
                        invalidate()
                    }
                    .build()
            )
        }
        strip
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_map))
                    .setOnClickListener { screenManager.push(PlacesScreen(carContext)) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_grid))
                    .setOnClickListener { screenManager.push(UtilitiesScreen(carContext)) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_video))
                    // Android Auto itself only runs this tap while the car is parked (it
                    // shows its own "not while driving" message otherwise), so YouTube
                    // opens unlocked.
                    .setOnClickListener(ParkedOnlyOnClickListener.create { openYouTube(hostSaysParked = true) })
                    .build()
            )
        return NavigationTemplate.Builder().setActionStrip(strip.build()).build()
    }
    /** Big buttons drawn in the compact (split-screen) layout. */
    private fun onTile(tile: DashboardRenderer.Tile) {
        when (tile) {
            DashboardRenderer.Tile.DUA -> {
                if (!Welcome.pressDua(carContext)) {
                    CarToast.makeText(carContext, "Couldn't play Dua al-Safar", CarToast.LENGTH_LONG).show()
                }
                shownButton = Welcome.duaButton()
                invalidate()
                renderer.draw()
            }
            DashboardRenderer.Tile.MAP -> screenManager.push(PlacesScreen(carContext))
            DashboardRenderer.Tile.CLAUDE -> openClaude(carContext)
            DashboardRenderer.Tile.UTILITIES -> screenManager.push(UtilitiesScreen(carContext))
            DashboardRenderer.Tile.YOUTUBE -> openYouTube(hostSaysParked = false)
            DashboardRenderer.Tile.HOME -> PlacesScreen.navigateTo(carContext, "Home")
            DashboardRenderer.Tile.WORK -> PlacesScreen.navigateTo(carContext, "Work", "Office")
            DashboardRenderer.Tile.CHECKIN -> startCheckIn(carContext, renderer::draw)
        }
    }

    /** YouTube inside the dashboard; it only plays while the car is parked. */
    private fun openYouTube(hostSaysParked: Boolean) {
        screenManager.push(YouTubeScreen(carContext, hostSaysParked))
    }

    private fun icon(@DrawableRes res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    companion object {
        private const val CLAUDE_PACKAGE = "com.anthropic.claude"

        /** Claude has no Android Auto app, so this opens it on the phone for use when parked. */
        fun openClaude(carContext: CarContext) {
            val launch = carContext.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
            if (launch == null) {
                CarToast.makeText(carContext, "Install the Claude app on your phone", CarToast.LENGTH_LONG).show()
                return
            }
            if (!android.provider.Settings.canDrawOverlays(carContext)) {
                // Android blocks opening apps on the phone from the background without this.
                PlacesScreen.askForPermission(carContext)
                return
            }
            try {
                carContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CarToast.makeText(carContext, "Claude is open on your phone. Use it when parked.", CarToast.LENGTH_LONG).show()
            } catch (_: Exception) {
                CarToast.makeText(carContext, "Couldn't open Claude on your phone", CarToast.LENGTH_LONG).show()
            }
        }

        /** Clock in on the HR site, hidden on the phone; the result shows as a toast and on the Clock in chip. */
        fun startCheckIn(carContext: CarContext, redraw: () -> Unit = {}) {
            if (CheckIn.isRunning) {
                CarToast.makeText(carContext, CheckIn.message.ifBlank { "Clocking in…" }, CarToast.LENGTH_SHORT).show()
                return
            }
            val started = CheckIn.start(carContext) {
                redraw()
                if (!CheckIn.isRunning) {
                    runCatching { CarToast.makeText(carContext, CheckIn.message, CarToast.LENGTH_LONG).show() }
                }
            }
            if (!started) {
                CarToast.makeText(carContext, "Add your HR site in the phone app first", CarToast.LENGTH_LONG).show()
            } else {
                CarToast.makeText(carContext, "Clocking in…", CarToast.LENGTH_SHORT).show()
            }
            redraw()
        }
    }
}
