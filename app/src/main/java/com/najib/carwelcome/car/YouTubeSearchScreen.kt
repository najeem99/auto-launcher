package com.najib.carwelcome.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/** YouTube search with Android Auto's keyboard (Android Auto only offers it while parked). */
class YouTubeSearchScreen(
    carContext: CarContext,
    private val onQuery: (String) -> Unit,
) : Screen(carContext) {

    private val suggestions = listOf("Quran recitation", "Islamic lectures", "News", "Cartoons for kids")

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        suggestions.forEach { s ->
            list.addItem(Row.Builder().setTitle(s).setOnClickListener { submit(s) }.build())
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchTerm: String) {
                if (searchTerm.isNotBlank()) submit(searchTerm.trim())
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search YouTube")
            .setShowKeyboardByDefault(true)
            .setItemList(list.build())
            .build()
    }

    private fun submit(q: String) {
        screenManager.pop()
        onQuery(q)
    }
}
