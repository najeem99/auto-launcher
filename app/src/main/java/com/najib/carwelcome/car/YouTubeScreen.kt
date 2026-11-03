package com.najib.carwelcome.car

import androidx.annotation.DrawableRes
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
import com.najib.carwelcome.Welcome

/** YouTube inside the dashboard. Plays only while the car is parked. */
class YouTubeScreen(carContext: CarContext) : Screen(carContext) {

    private val parked = ParkedDetector(carContext)
    private val layer = YouTubeLayer(carContext, YouTubeLayer.HOME, parked)

    init {
        Welcome.stopAll() // no dua over the video
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                parked.start()
                SurfaceHub.show(layer)
            }
            override fun onStop(owner: LifecycleOwner) {
                layer.pauseVideo()
                parked.stop()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                SurfaceHub.release(layer)
                layer.release()
                parked.stop()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Search")
                    .setIcon(icon(R.drawable.ic_search))
                    .setOnClickListener {
                        screenManager.push(YouTubeSearchScreen(carContext) { q -> layer.load(YouTubeLayer.searchUrl(q)) })
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_back))
                    .setOnClickListener { if (!layer.goBack()) screenManager.pop() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_close))
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
        val scroll = ActionStrip.Builder()
            .addAction(Action.PAN) // lets a finger drag scroll the page
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_up)).setOnClickListener { layer.pageUp() }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_down)).setOnClickListener { layer.pageDown() }.build())
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(scroll)
            .build()
    }

    private fun icon(@DrawableRes res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}
