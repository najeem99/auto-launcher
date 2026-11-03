package com.najib.carwelcome.car

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/** Colours and fonts shared by the drawn car screens (they match the HTML mockups). */
class Look private constructor(private val context: Context) {

    val night1 = Color.parseColor("#0D1526")
    val night2 = Color.parseColor("#0A101D")
    val ink = Color.parseColor("#EEF2FA")
    val ink2 = Color.parseColor("#A9B6D0")
    val ink3 = Color.parseColor("#6E7C99")
    val sand = Color.parseColor("#E8C27A")
    val teal = Color.parseColor("#5BD1C1")
    val good = Color.parseColor("#63D69A")
    val bad = Color.parseColor("#FF8A7A")
    val red = Color.parseColor("#FF5A4E")
    val line = Color.parseColor("#24314D")
    val tileFill = Color.argb(224, 19, 29, 49)
    val iconBox = Color.argb(15, 255, 255, 255)
    val claude = Color.parseColor("#D97757")

    val sora300 = font("Sora.ttf", 300, Typeface.create("sans-serif-light", Typeface.NORMAL))
    val sora500 = font("Sora.ttf", 500, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    val manrope500 = font("Manrope.ttf", 500, Typeface.create("sans-serif", Typeface.NORMAL))
    val manrope600 = font("Manrope.ttf", 600, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    val manrope700 = font("Manrope.ttf", 700, manrope600)
    val amiri: Typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/Amiri-Regular.ttf") }
        .getOrDefault(Typeface.create(Typeface.SERIF, Typeface.NORMAL))

    private fun font(file: String, weight: Int, fallback: Typeface): Typeface = runCatching {
        Typeface.Builder(context.assets, "fonts/$file")
            .setFontVariationSettings("'wght' $weight")
            .setWeight(weight)
            .build()
    }.getOrNull() ?: fallback

    companion object {
        @Volatile private var instance: Look? = null

        fun get(context: Context): Look =
            instance ?: synchronized(this) { instance ?: Look(context.applicationContext).also { instance = it } }
    }
}
