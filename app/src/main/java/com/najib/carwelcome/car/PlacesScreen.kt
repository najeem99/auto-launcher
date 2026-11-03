package com.najib.carwelcome.car

import android.content.Intent
import android.net.Uri
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
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"

        /**
         * Starts Google Maps navigation. A generic "navigate" request from the car is
         * routed to the active navigation app, which is this dashboard, so it came
         * straight back here and nothing happened. Asking Google Maps directly avoids
         * that; Android Auto then switches the car screen to Maps' turn-by-turn view.
         */
        fun navigateTo(carContext: CarContext, query: String): Boolean {
            val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query)))
                .setPackage(MAPS_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching {
                if (maps.resolveActivity(carContext.packageManager) == null) error("Maps not installed")
                carContext.startActivity(maps)
            }.isSuccess
            if (started) {
                CarToast.makeText(carContext, "Starting Google Maps…", CarToast.LENGTH_SHORT).show()
                return true
            }
            // Google Maps missing: fall back to the car's own navigation handoff.
            return try {
                carContext.startCarApp(
                    Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
                )
                true
            } catch (_: Exception) {
                CarToast.makeText(carContext, "Install Google Maps to use directions", CarToast.LENGTH_LONG).show()
                false
            }
        }
    }
}
