package com.najib.carwelcome.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.najib.carwelcome.R
import com.najib.carwelcome.Weather

/** Opened from the grid button on the dashboard: speedometer, check in, Office/Home, Claude, Parked. */
class UtilitiesScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = UtilitiesRenderer(carContext) { onTile(it) }
    private val onSpeed: () -> Unit = { renderer.kick() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SurfaceHub.show(renderer)
                DriveSpeed.addListener(onSpeed)
                Weather.refresh(carContext)
            }
            override fun onStop(owner: LifecycleOwner) {
                DriveSpeed.removeListener(onSpeed)
            }
            override fun onDestroy(owner: LifecycleOwner) {
                SurfaceHub.release(renderer)
                renderer.stop()
            }
        })
    }

    private fun onTile(tile: UtilitiesRenderer.Tile) {
        when (tile) {
            UtilitiesRenderer.Tile.SPEEDO -> screenManager.push(SpeedometerScreen(carContext))
            UtilitiesRenderer.Tile.CHECKIN -> DashboardScreen.startCheckIn(carContext, renderer::kick)
            UtilitiesRenderer.Tile.OFFICE -> PlacesScreen.navigateTo(carContext, "Work")
            UtilitiesRenderer.Tile.HOME -> PlacesScreen.navigateTo(carContext, "Home")
            UtilitiesRenderer.Tile.PRAYER -> PlacesScreen.navigateTo(carContext, "mosque")
            UtilitiesRenderer.Tile.CLAUDE -> DashboardScreen.openClaude(carContext)
            UtilitiesRenderer.Tile.PARKED -> toggleParked()
        }
    }

    private fun toggleParked() {
        if (!ParkedDetector.manualParked && (DriveSpeed.kmh() ?: 0f) > 10f) {
            CarToast.makeText(carContext, "Only when the car is stopped", CarToast.LENGTH_SHORT).show()
            return
        }
        ParkedDetector.manualParked = !ParkedDetector.manualParked
        CarToast.makeText(
            carContext,
            if (ParkedDetector.manualParked) "Parked: YouTube can play" else "Driving: YouTube locked",
            CarToast.LENGTH_SHORT,
        ).show()
        renderer.kick()
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Home")
                    .setIcon(icon(R.drawable.ic_back))
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    private fun icon(@DrawableRes res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}
