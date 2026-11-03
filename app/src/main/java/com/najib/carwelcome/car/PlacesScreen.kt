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

        /** When we last started Maps on the phone, so a bounced car request isn't sent twice. */
        @Volatile private var phoneStartedAt = 0L

        /**
         * Starts Google Maps navigation to [query] ("Home", "Work", "mosque", …).
         *
         * Android blocks apps from opening screens on the phone from the background,
         * so the earlier versions silently did nothing. With "Display over other apps"
         * allowed for Najeem Drive, Maps is started on the phone, and Android Auto shows
         * its turn-by-turn on the car screen. Otherwise Android Auto is asked to hand
         * the destination to Google Maps directly.
         */
        fun navigateTo(carContext: CarContext, query: String): Boolean {
            if (Settings.canDrawOverlays(carContext) && openOnPhone(carContext, query)) {
                // Also bring Maps to the car screen.
                runCatching { carContext.startCarApp(navigateIntent(query).setPackage(MAPS_PACKAGE)) }
                return true
            }
            val sent = runCatching { carContext.startCarApp(navigateIntent(query).setPackage(MAPS_PACKAGE)) }.isSuccess ||
                runCatching { carContext.startCarApp(navigateIntent(query)) }.isSuccess
            if (sent) {
                CarToast.makeText(carContext, "Starting Google Maps…", CarToast.LENGTH_SHORT).show()
                return true
            }
            askForPermission(carContext)
            return false
        }

        /**
         * Android Auto handed a navigation request back to this app (it is a navigation
         * app too). Start Maps on the phone instead.
         */
        fun onNavigateBounced(carContext: CarContext, intent: Intent) {
            if (System.currentTimeMillis() - phoneStartedAt < 5_000L) return
            val q = intent.data?.let { it.getQueryParameter("q") ?: it.schemeSpecificPart } ?: return
            if (!Settings.canDrawOverlays(carContext) || !openOnPhone(carContext, q)) askForPermission(carContext)
        }

        private fun navigateIntent(query: String) =
            Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=" + Uri.encode(query)))

        private fun openOnPhone(carContext: CarContext, query: String): Boolean {
            val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query)))
                .setPackage(MAPS_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (maps.resolveActivity(carContext.packageManager) == null) {
                CarToast.makeText(carContext, "Install Google Maps to use directions", CarToast.LENGTH_LONG).show()
                return false
            }
            return runCatching { carContext.startActivity(maps) }.isSuccess.also {
                if (it) {
                    phoneStartedAt = System.currentTimeMillis()
                    CarToast.makeText(carContext, "Starting Google Maps…", CarToast.LENGTH_SHORT).show()
                }
            }
        }

        fun askForPermission(carContext: CarContext) {
            CarToast.makeText(
                carContext,
                "On your phone, open Najeem Drive and tap \"Allow opening Maps\"",
                CarToast.LENGTH_LONG,
            ).show()
        }
    }
}
