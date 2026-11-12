package com.najib.carwelcome.drive

import android.content.Context

/** Trip log settings. */
class TripPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("trips", Context.MODE_PRIVATE)

    /** Record trips while connected to Android Auto. Turn off to save battery. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** The car's official fuel economy, km per litre. */
    var ratedKmpl: Float
        get() = sp.getFloat("rated_kmpl", 22f)
        set(v) = sp.edit().putFloat("rated_kmpl", v).apply()

    /** Fallback petrol price per litre (AED), only used if no monthly price is known. See [FuelPrices]. */
    var fuelPrice: Float
        get() = sp.getFloat("fuel_price", 4.04f)
        set(v) = sp.edit().putFloat("fuel_price", v).apply()
}

/**
 * Rough petrol estimate. Official figures are measured on a gentle test cycle, so
 * real driving uses more: open-road driving gets about 85% of the rated figure,
 * stop-and-go crawling about 55%, and a small petrol engine idling burns roughly
 * 0.6 litres an hour.
 */
object Fuel {
    private const val MOVING_FACTOR = 0.85
    private const val SLOW_FACTOR = 0.55
    private const val IDLE_LPH = 0.6

    fun litres(t: Trip, ratedKmpl: Float): Double {
        if (ratedKmpl <= 0f) return 0.0
        val rated = ratedKmpl.toDouble()
        // Distance not split into moving/slow (e.g. tunnels) counts as moving.
        val other = (t.distanceM - t.movingDistM - t.slowDistM).coerceAtLeast(0.0)
        val movingKm = (t.movingDistM + other) / 1000.0
        val slowKm = t.slowDistM / 1000.0
        val idleH = t.idleMs / 3_600_000.0
        return movingKm / (rated * MOVING_FACTOR) + slowKm / (rated * SLOW_FACTOR) + idleH * IDLE_LPH
    }

    /** Rough cost in AED, priced with the month the trip was in. */
    fun cost(context: Context, t: Trip, ratedKmpl: Float): Double =
        litres(t, ratedKmpl) * FuelPrices.price(context, t.startAt)

    /** Real-world km per litre for these trips, or 0 if too little data. */
    fun kmpl(distanceM: Double, litres: Double): Double =
        if (litres > 0.05) distanceM / 1000.0 / litres else 0.0
}
