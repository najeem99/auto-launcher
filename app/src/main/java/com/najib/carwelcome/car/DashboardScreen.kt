package com.najib.carwelcome.car

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
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
import com.najib.carwelcome.Welcome

/** The front page: a full-screen drawn dashboard plus the Dua, Map and Claude buttons. */
class DashboardScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = DashboardRenderer(carContext)

    init {
        Welcome.attach(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Weather.refresh(carContext) { renderer.draw() }
            }
            override fun onDestroy(owner: LifecycleOwner) = renderer.stop()
        })
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Dua")
                    .setIcon(icon(R.drawable.ic_dua))
                    .setOnClickListener {
                        if (!Welcome.toggleDua(carContext)) {
                            CarToast.makeText(
                                carContext,
                                "Add your Dua al-Safar recording in Najeem Drive on your phone",
                                CarToast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_map))
                    .setOnClickListener { screenManager.push(PlacesScreen(carContext)) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_claude))
                    .setOnClickListener { openClaude() }
                    .build()
            )
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    /** Claude has no Android Auto app, so this opens it on the phone for use when parked. */
    private fun openClaude() {
        val launch = carContext.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
        if (launch == null) {
            CarToast.makeText(carContext, "Install the Claude app on your phone", CarToast.LENGTH_LONG).show()
            return
        }
        try {
            carContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CarToast.makeText(carContext, "Claude is open on your phone. Use it when parked.", CarToast.LENGTH_LONG).show()
        } catch (_: Exception) {
            CarToast.makeText(carContext, "Couldn't open Claude on your phone", CarToast.LENGTH_LONG).show()
        }
    }

    private fun icon(@DrawableRes res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    companion object {
        private const val CLAUDE_PACKAGE = "com.anthropic.claude"
    }
}
