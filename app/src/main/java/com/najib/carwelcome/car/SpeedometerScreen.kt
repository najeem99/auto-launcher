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

/** The full-screen speedometer, opened by tapping the dial on the Utilities screen. */
class SpeedometerScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = SpeedometerRenderer(carContext)
    private val onSpeed: () -> Unit = { renderer.kick() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SurfaceHub.show(renderer)
                DriveSpeed.addListener(onSpeed)
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
