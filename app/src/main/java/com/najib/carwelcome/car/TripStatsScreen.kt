package com.najib.carwelcome.car

import androidx.car.app.CarContext
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
import com.najib.carwelcome.drive.TripRecorder

/** Live stats for the trip in progress, opened from the Trip tile on the Utilities screen. */
class TripStatsScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = TripStatsRenderer(carContext)
    private val onTrip: () -> Unit = { renderer.kick() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SurfaceHub.show(renderer)
                TripRecorder.addListener(onTrip)
            }
            override fun onStop(owner: LifecycleOwner) {
                TripRecorder.removeListener(onTrip)
            }
            override fun onDestroy(owner: LifecycleOwner) {
                SurfaceHub.release(renderer)
                renderer.stop()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Utilities")
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_back)).build())
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
