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
        try {
            carContext.startCarApp(
                Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
            )
        } catch (_: Exception) {
            CarToast.makeText(carContext, "No maps app found", CarToast.LENGTH_LONG).show()
        }
    }
}
