package com.najib.carwelcome.drive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One drive, from Android Auto connecting to disconnecting. Times are wall-clock ms. */
data class Trip(
    var id: Long = 0,
    var startAt: Long = 0,
    /** Null while the trip is still going. */
    var endAt: Long? = null,
    /** Last time the running trip was saved; used to close a trip after a crash. */
    var updatedAt: Long = 0,
    var distanceM: Double = 0.0,
    /** Driving at 20 km/h or more. */
    var movingMs: Long = 0,
    /** Crawling, 5 to 20 km/h: slow traffic. */
    var slowMs: Long = 0,
    /** Standing still (under 5 km/h): lights, jams, parked with the phone connected. */
    var idleMs: Long = 0,
    /** No speed reading at all (tunnel, GPS off). */
    var noSignalMs: Long = 0,
    var movingDistM: Double = 0.0,
    var slowDistM: Double = 0.0,
    var topKmh: Float = 0f,
    var stops: Int = 0,
    /** True if most of the speed came from the car itself rather than GPS. */
    var carSpeed: Boolean = false,
) {
    val durationMs: Long get() = ((endAt ?: updatedAt) - startAt).coerceAtLeast(0)
    /** Time spent waiting: standing plus crawling. */
    val waitMs: Long get() = idleMs + slowMs
    val trackedMs: Long get() = movingMs + slowMs + idleMs
    /** Share of the trip spent waiting, 0..1. */
    val waitShare: Float get() = if (trackedMs > 0) waitMs.toFloat() / trackedMs else 0f
    /** Average while the wheels were turning. */
    val avgMovingKmh: Float get() {
        val ms = movingMs + slowMs
        return if (ms > 30_000) (distanceM / (ms / 1000.0) * 3.6).toFloat() else 0f
    }
    /** Average over the whole trip, stops included. */
    val avgKmh: Float get() {
        val ms = trackedMs + noSignalMs
        return if (ms > 30_000) (distanceM / (ms / 1000.0) * 3.6).toFloat() else 0f
    }
}

/**
 * Speed (km/h) averaged over each 5 s of a trip, for the graph, and where the car was
 * at that moment ([lat]/[lon], null for trips recorded before positions were kept or
 * without a GPS fix). Positions never leave the phone except in the user's own backup.
 */
data class SpeedPoint(val at: Long, val kmh: Float, val lat: Double? = null, val lon: Double? = null)

class TripDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "trips.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_at INTEGER NOT NULL,
                end_at INTEGER,
                updated_at INTEGER NOT NULL,
                distance_m REAL NOT NULL DEFAULT 0,
                moving_ms INTEGER NOT NULL DEFAULT 0,
                slow_ms INTEGER NOT NULL DEFAULT 0,
                idle_ms INTEGER NOT NULL DEFAULT 0,
                no_signal_ms INTEGER NOT NULL DEFAULT 0,
                moving_dist_m REAL NOT NULL DEFAULT 0,
                slow_dist_m REAL NOT NULL DEFAULT 0,
                top_kmh REAL NOT NULL DEFAULT 0,
                stops INTEGER NOT NULL DEFAULT 0,
                car_speed INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX trips_start ON trips(start_at)")
        db.execSQL(
            """CREATE TABLE speed_points (
                trip_id INTEGER NOT NULL,
                at INTEGER NOT NULL,
                kmh REAL NOT NULL,
                lat REAL,
                lon REAL
            )"""
        )
        db.execSQL("CREATE INDEX points_trip ON speed_points(trip_id)")
        createNames(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 2: where the car was, for the places and roads page.
            db.execSQL("ALTER TABLE speed_points ADD COLUMN lat REAL")
            db.execSQL("ALTER TABLE speed_points ADD COLUMN lon REAL")
            createNames(db)
        }
    }

    /** Street names looked up once per spot and kept, so the phone doesn't ask again. */
    private fun createNames(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS place_names (key TEXT PRIMARY KEY, road TEXT, area TEXT)")
    }

    fun cachedName(key: String): Pair<String?, String?>? =
        readableDatabase.rawQuery("SELECT road, area FROM place_names WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) null else c.getString(1)) else null
        }

    fun allCachedNames(): Map<String, Pair<String?, String?>> {
        val out = HashMap<String, Pair<String?, String?>>()
        readableDatabase.rawQuery("SELECT key, road, area FROM place_names", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = (if (c.isNull(1)) null else c.getString(1)) to (if (c.isNull(2)) null else c.getString(2))
        }
        return out
    }

    fun saveName(key: String, road: String?, area: String?) {
        writableDatabase.insertWithOnConflict("place_names", null, ContentValues().apply {
            put("key", key); put("road", road); put("area", area)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Every point with a position from trips that started at or after [fromMs], by trip then time. */
    fun geoPoints(fromMs: Long): Map<Long, List<SpeedPoint>> {
        val out = LinkedHashMap<Long, MutableList<SpeedPoint>>()
        readableDatabase.rawQuery(
            """SELECT p.trip_id, p.at, p.kmh, p.lat, p.lon FROM speed_points p JOIN trips t ON t.id = p.trip_id
               WHERE t.start_at >= ? AND p.lat IS NOT NULL ORDER BY p.trip_id, p.at""",
            arrayOf(fromMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getLong(0)) { mutableListOf() } += SpeedPoint(c.getLong(1), c.getFloat(2), c.getDouble(3), c.getDouble(4))
            }
        }
        return out
    }

    fun insert(t: Trip): Long {
        t.id = writableDatabase.insert("trips", null, values(t))
        return t.id
    }

    fun update(t: Trip) {
        if (t.id == 0L) insert(t) else writableDatabase.update("trips", values(t), "id = ?", arrayOf(t.id.toString()))
    }

    fun addPoint(tripId: Long, p: SpeedPoint) {
        writableDatabase.insert("speed_points", null, pointValues(tripId, p))
    }

    private fun pointValues(tripId: Long, p: SpeedPoint) = ContentValues().apply {
        put("trip_id", tripId); put("at", p.at); put("kmh", p.kmh)
        if (p.lat != null && p.lon != null) { put("lat", p.lat); put("lon", p.lon) }
    }

    fun deletePointsAfter(tripId: Long, at: Long) {
        writableDatabase.delete("speed_points", "trip_id = ? AND at > ?", arrayOf(tripId.toString(), at.toString()))
    }

    /** Trips that started in [fromMs, toMs), newest first. */
    fun tripsBetween(fromMs: Long, toMs: Long): List<Trip> =
        query("start_at >= ? AND start_at < ?", arrayOf(fromMs.toString(), toMs.toString()), "start_at DESC", null)

    /** Every finished trip, oldest first (for the backup file). */
    fun allFinished(): List<Trip> = query("end_at IS NOT NULL", emptyArray(), "start_at ASC", null)

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM trips", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Adds a trip from a backup with its speed points, unless a trip with the same start is already here. */
    fun importTrip(t: Trip, points: List<SpeedPoint>): Boolean {
        val db = writableDatabase
        val exists = db.rawQuery("SELECT 1 FROM trips WHERE start_at = ? LIMIT 1", arrayOf(t.startAt.toString())).use { it.moveToFirst() }
        if (exists) return false
        db.beginTransaction()
        try {
            t.id = 0
            val id = db.insert("trips", null, values(t))
            points.forEach { p ->
                db.insert("speed_points", null, pointValues(id, p))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("speed_points", "trip_id = ?", arrayOf(id.toString()))
            db.delete("trips", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deleteAll() {
        val db = writableDatabase
        db.delete("speed_points", null, null)
        db.delete("trips", null, null)
    }

    /** The trip left open (end_at is empty), if any. */
    fun openTrip(): Trip? = query("end_at IS NULL", emptyArray(), "start_at DESC", "1").firstOrNull()

    fun lastFinished(): Trip? = query("end_at IS NOT NULL", emptyArray(), "end_at DESC", "1").firstOrNull()

    /** Trips that started at or after [fromMs], newest first. */
    fun tripsSince(fromMs: Long, limit: Int? = null): List<Trip> =
        query("start_at >= ?", arrayOf(fromMs.toString()), "start_at DESC", limit?.toString())

    fun trip(id: Long): Trip? = query("id = ?", arrayOf(id.toString()), null, "1").firstOrNull()

    fun points(tripId: Long): List<SpeedPoint> {
        val out = mutableListOf<SpeedPoint>()
        readableDatabase.query("speed_points", arrayOf("at", "kmh", "lat", "lon"), "trip_id = ?", arrayOf(tripId.toString()), null, null, "at").use { c ->
            while (c.moveToNext()) out += SpeedPoint(c.getLong(0), c.getFloat(1),
                if (c.isNull(2)) null else c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3))
        }
        return out
    }

    private fun query(where: String, args: Array<String>, order: String?, limit: String?): List<Trip> {
        val out = mutableListOf<Trip>()
        readableDatabase.query("trips", null, where, args, null, null, order, limit).use { c ->
            while (c.moveToNext()) out += read(c)
        }
        return out
    }

    private fun read(c: Cursor) = Trip(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        startAt = c.getLong(c.getColumnIndexOrThrow("start_at")),
        endAt = c.getColumnIndexOrThrow("end_at").let { if (c.isNull(it)) null else c.getLong(it) },
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m")),
        movingMs = c.getLong(c.getColumnIndexOrThrow("moving_ms")),
        slowMs = c.getLong(c.getColumnIndexOrThrow("slow_ms")),
        idleMs = c.getLong(c.getColumnIndexOrThrow("idle_ms")),
        noSignalMs = c.getLong(c.getColumnIndexOrThrow("no_signal_ms")),
        movingDistM = c.getDouble(c.getColumnIndexOrThrow("moving_dist_m")),
        slowDistM = c.getDouble(c.getColumnIndexOrThrow("slow_dist_m")),
        topKmh = c.getFloat(c.getColumnIndexOrThrow("top_kmh")),
        stops = c.getInt(c.getColumnIndexOrThrow("stops")),
        carSpeed = c.getInt(c.getColumnIndexOrThrow("car_speed")) != 0,
    )

    private fun values(t: Trip) = ContentValues().apply {
        put("start_at", t.startAt)
        if (t.endAt == null) putNull("end_at") else put("end_at", t.endAt)
        put("updated_at", t.updatedAt)
        put("distance_m", t.distanceM)
        put("moving_ms", t.movingMs)
        put("slow_ms", t.slowMs)
        put("idle_ms", t.idleMs)
        put("no_signal_ms", t.noSignalMs)
        put("moving_dist_m", t.movingDistM)
        put("slow_dist_m", t.slowDistM)
        put("top_kmh", t.topKmh)
        put("stops", t.stops)
        put("car_speed", if (t.carSpeed) 1 else 0)
    }

    companion object {
        @Volatile private var instance: TripDb? = null
        fun get(context: Context): TripDb =
            instance ?: synchronized(this) { instance ?: TripDb(context).also { instance = it } }
    }
}
