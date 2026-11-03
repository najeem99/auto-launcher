package com.najib.carwelcome.car

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.najib.carwelcome.CheckIn
import com.najib.carwelcome.Prefs
import com.najib.carwelcome.Directions
import com.najib.carwelcome.R

/** The Map button: pick a destination and hand it to Google Maps (or your default nav app). */
class PlacesScreen(carContext: CarContext) : Screen(carContext) {

    private data class Place(val title: String, val query: String, val icon: Int)

    private val places = listOf(
        Place("Home", "Home", R.drawable.ic_home),
        Place("Work", "Work", R.drawable.ic_work),
        Place("Nearest mosque", "mosque", R.drawable.ic_mosque),
        Place("Petrol station", "petrol station", R.drawable.ic_fuel),
        Place("Coffee", "coffee", R.drawable.ic_coffee),
    )

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (Prefs(carContext).checkInUrl.isNotBlank()) {
            // Also reachable here while the dua card covers the Office/Home row.
            list.addItem(
                Row.Builder()
                    .setTitle("Clock in at work")
                    .addText(CheckIn.statusLine(Prefs(carContext)))
                    .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_checkin)).build())
                    .setOnClickListener {
                        DashboardScreen.startCheckIn(carContext)
                        screenManager.pop()
                    }
                    .build()
            )
        }
        places.forEach { p ->
            list.addItem(
                Row.Builder()
                    .setTitle(p.title)
                    .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, p.icon)).build())
                    .setOnClickListener { navigate(p.query) }
                    .build()
            )
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle("Where to?")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun navigate(query: String) {
        if (navigateTo(carContext, query)) screenManager.pop()
    }

    companion object {

        /** When we last started Maps on the phone, so a bounced car request isn't sent twice. */
        @Volatile private var phoneStartedAt = 0L

        /**
         * Starts Google Maps navigation to [query] ("Home", "Work", "mosque", …).
         *
         * Android doesn't let the car screen open Maps on the phone by itself. With
         * "Display over other apps" allowed, Maps starts right away; otherwise a
         * notification appears on the phone, and one tap on it starts navigation.
         * Either way Android Auto then shows Maps' turn-by-turn on the car screen.
         */
        fun navigateTo(carContext: CarContext, query: String, title: String = query): Boolean {
            if (!Directions.hasMaps(carContext)) {
                CarToast.makeText(carContext, "Install Google Maps to use directions", CarToast.LENGTH_LONG).show()
                return false
            }
            if (Settings.canDrawOverlays(carContext) && Directions.startNow(carContext, query)) {
                phoneStartedAt = System.currentTimeMillis()
                CarToast.makeText(carContext, "Starting Google Maps…", CarToast.LENGTH_SHORT).show()
                return true
            }
            if (Directions.notify(carContext, title, query)) {
                CarToast.makeText(carContext, "Tap the notification on your phone to start", CarToast.LENGTH_LONG).show()
                return true
            }
            askForPermission(carContext)
            return false
        }

        /**
         * Android Auto handed a navigation request back to this app (it is a navigation
         * app too). Pass it on to Maps.
         */
        fun onNavigateBounced(carContext: CarContext, intent: Intent) {
            if (System.currentTimeMillis() - phoneStartedAt < 5_000L) return
            val q = intent.data?.let { it.getQueryParameter("q") ?: it.schemeSpecificPart } ?: return
            navigateTo(carContext, q)
        }

        fun askForPermission(carContext: CarContext) {
            CarToast.makeText(
                carContext,
                "On your phone, open Najeem Drive and allow notifications",
                CarToast.LENGTH_LONG,
            ).show()
        }
    }
}
