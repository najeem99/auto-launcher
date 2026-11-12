package com.najib.carwelcome.drive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One visit to the petrol station. */
data class FillUp(
    var id: Long = 0,
    var at: Long = 0,
    var litres: Double = 0.0,
    /** What you paid, AED. */
    var aed: Double = 0.0,
    /** Odometer reading in km, if you typed it. */
    var odometerKm: Double? = null,
    /** Filled to the top. Two full fills with odometer readings give the real economy. */
    var full: Boolean = true,
    var grade: Grade = Grade.SPECIAL95,
) {
    val pricePerLitre: Double get() = if (litres > 0) aed / litres else 0.0
}

class FuelDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "fuel.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE fillups (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                litres REAL NOT NULL,
                aed REAL NOT NULL,
                odometer_km REAL,
                full INTEGER NOT NULL DEFAULT 1,
                grade TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX fillups_at ON fillups(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun save(f: FillUp): Long {
        val v = ContentValues().apply {
            put("at", f.at); put("litres", f.litres); put("aed", f.aed)
            if (f.odometerKm == null) putNull("odometer_km") else put("odometer_km", f.odometerKm)
            put("full", if (f.full) 1 else 0); put("grade", f.grade.key)
        }
        return if (f.id > 0) { writableDatabase.update("fillups", v, "id = ?", arrayOf(f.id.toString())); f.id }
        else writableDatabase.insert("fillups", null, v).also { f.id = it }
    }

    fun delete(id: Long) { writableDatabase.delete("fillups", "id = ?", arrayOf(id.toString())) }

    fun between(from: Long, to: Long): List<FillUp> = query("at >= ? AND at < ?", arrayOf(from.toString(), to.toString()))
    fun all(): List<FillUp> = query(null, null)

    /** Adds a fill-up from a backup unless one at the same moment is already here. */
    fun import(f: FillUp): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM fillups WHERE at = ?", arrayOf(f.at.toString())).use { if (it.moveToFirst()) return false }
        f.id = 0
        save(f)
        return true
    }

    private fun query(where: String?, args: Array<String>?): List<FillUp> {
        val out = mutableListOf<FillUp>()
        readableDatabase.query("fillups", null, where, args, null, null, "at").use { c -> while (c.moveToNext()) out += read(c) }
        return out
    }

    private fun read(c: Cursor) = FillUp(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        at = c.getLong(c.getColumnIndexOrThrow("at")),
        litres = c.getDouble(c.getColumnIndexOrThrow("litres")),
        aed = c.getDouble(c.getColumnIndexOrThrow("aed")),
        odometerKm = c.getColumnIndexOrThrow("odometer_km").let { if (c.isNull(it)) null else c.getDouble(it) },
        full = c.getInt(c.getColumnIndexOrThrow("full")) != 0,
        grade = Grade.of(c.getString(c.getColumnIndexOrThrow("grade"))),
    )

    companion object {
        @Volatile private var instance: FuelDb? = null
        fun get(context: Context): FuelDb =
            instance ?: synchronized(this) { instance ?: FuelDb(context).also { instance = it } }

        /**
         * Real km per litre from the odometer: between two full fills, the distance
         * driven is the odometer difference and the fuel used is everything put in
         * after the first one, up to and including the second.
         */
        fun odometerEconomy(fills: List<FillUp>): Double? {
            val sorted = fills.sortedBy { it.at }
            val fullWithOdo = sorted.withIndex().filter { it.value.full && it.value.odometerKm != null }
            if (fullWithOdo.size < 2) return null
            val first = fullWithOdo.first()
            val last = fullWithOdo.last()
            val km = last.value.odometerKm!! - first.value.odometerKm!!
            val litres = sorted.subList(first.index + 1, last.index + 1).sumOf { it.litres }
            return if (km > 20 && litres > 1) km / litres else null
        }
    }
}
