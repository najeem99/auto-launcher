package com.najib.carwelcome.drive

import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Works out where the driving time goes, from the positions saved with each trip's
 * 5-second speed points. Nothing here touches Android, so it is plain arithmetic:
 *
 * - **Stop spots**: every time the car stood still (under 5 km/h) for 10 s or more in
 *   the middle of a drive is a stop. Stops within [SPOT_RADIUS_M] of each other, on any
 *   trip, are the same spot: usually a traffic signal, a junction or a jam.
 * - **Roads**: every 5 s of driving is counted against the ~100 m square it was in;
 *   squares get a street name later (see [PlaceNames]) and add up per road.
 * - **Places**: where trips start and end, grouped within [PLACE_RADIUS_M]. For each one
 *   it knows how long the drives there took ("time to reach home") and how long you
 *   stayed before driving off again.
 *
 * Wait at the very start and end of a trip is left out of stop spots: that is warming up
 * or parking, not traffic.
 */
object PlaceStats {
    const val STOP_KMH = 5f
    const val WAIT_KMH = 20f
    /** A stop must last this long to count (same rule as the trip's "stops"). */
    const val MIN_STOP_MS = 10_000L
    /** Longer than this, the car was parked with the phone still connected: a stay, not a stop. */
    const val PARKED_MS = 20 * 60_000L
    const val SPOT_RADIUS_M = 60.0
    const val PLACE_RADIUS_M = 250.0
    /** Points are saved every 5 s; a longer gap (tunnel, no fix) splits runs. */
    const val POINT_MS = 5_000L
    const val GAP_MS = 30_000L
    /** A stay longer than this isn't a stay (phone off, trip missed). */
    const val MAX_STAY_MS = 4 * 24 * 3_600_000L
    /** Road squares are this many degrees (~110 m north–south, ~100 m east–west in the UAE). */
    const val CELL_DEG = 0.001

    // ---- Results -------------------------------------------------------------------------

    class Stop(val tripId: Long, val startAt: Long, val ms: Long, val lat: Double, val lon: Double)

    /** A spot where the car keeps stopping. */
    class Spot(var lat: Double, var lon: Double) {
        val stops = mutableListOf<Stop>()
        val totalMs get() = stops.sumOf { it.ms }
        val count get() = stops.size
        val avgMs get() = if (stops.isEmpty()) 0L else totalMs / stops.size
        val longestMs get() = stops.maxOfOrNull { it.ms } ?: 0L
        val trips get() = stops.map { it.tripId }.distinct().size
        internal fun add(s: Stop) {
            stops += s
            lat += (s.lat - lat) / stops.size
            lon += (s.lon - lon) / stops.size
        }
    }

    /** Time spent in one ~100 m square of road. */
    class Cell(val key: String, val lat: Double, val lon: Double) {
        var ms = 0L
        var waitMs = 0L
        var standMs = 0L
        var distM = 0.0
    }

    /** A road: squares with the same street name added up. */
    class Road(val name: String) {
        var ms = 0L
        var waitMs = 0L
        var standMs = 0L
        var distM = 0.0
        var trips = 0
        val waitShare: Float get() = if (ms > 0) waitMs.toFloat() / ms else 0f
        val avgKmh: Float get() = if (ms > 30_000) (distM / (ms / 1000.0) * 3.6).toFloat() else 0f
    }

    /** One drive that ended at a place. */
    class Arrival(val trip: Trip, val from: Place?, val driveMs: Long, val waitMs: Long, val at: Long)

    /** Time spent at a place between two drives (or parked with the phone connected). */
    class Stay(val fromAt: Long, val toAt: Long) { val ms get() = toAt - fromAt }

    class Place(var lat: Double, var lon: Double) {
        private var n = 0
        val arrivals = mutableListOf<Arrival>()
        val stays = mutableListOf<Stay>()
        var departures = 0
        /** "Home" / "Office" when the stays make it obvious, else null. */
        var guess: String? = null
        val stayMs get() = stays.sumOf { it.ms }
        val visits get() = maxOf(arrivals.size, stays.size)
        val avgStayMs get() = if (stays.isEmpty()) 0L else stayMs / stays.size
        val avgDriveMs get() = if (arrivals.isEmpty()) 0L else arrivals.sumOf { it.driveMs } / arrivals.size
        val avgWaitMs get() = if (arrivals.isEmpty()) 0L else arrivals.sumOf { it.waitMs } / arrivals.size
        val fastest get() = arrivals.minByOrNull { it.driveMs }
        val slowest get() = arrivals.maxByOrNull { it.driveMs }
        internal fun pull(la: Double, lo: Double) {
            n++
            lat += (la - lat) / n
            lon += (lo - lon) / n
        }
    }

    class Report(
        val trips: Int,
        val tripsWithPlaces: Int,
        val trackedMs: Long,
        val standMs: Long,
        val spots: List<Spot>,
        val cells: List<Cell>,
        val places: List<Place>,
        /** Stops on each trip, for the trip page. */
        val stopsByTrip: Map<Long, List<Stop>>,
    ) {
        /** Spot a stop belongs to. */
        fun spotOf(s: Stop): Spot? = spots.firstOrNull { sp -> sp.stops.any { it === s } }

        /** Adds the road squares up per road, using [nameOf] (null = not known yet). */
        fun roads(nameOf: (Cell) -> String?): Pair<List<Road>, Long> {
            val out = HashMap<String, Road>()
            var unknownMs = 0L
            val tripsPerRoad = HashMap<String, MutableSet<Long>>()
            for (c in cells) {
                val name = nameOf(c)
                if (name.isNullOrBlank()) { unknownMs += c.ms; continue }
                val r = out.getOrPut(name) { Road(name) }
                r.ms += c.ms; r.waitMs += c.waitMs; r.standMs += c.standMs; r.distM += c.distM
                tripsPerRoad.getOrPut(name) { HashSet() }.addAll(cellTrips[c.key].orEmpty())
            }
            out.values.forEach { it.trips = tripsPerRoad[it.name]?.size ?: 0 }
            return out.values.sortedByDescending { it.ms } to unknownMs
        }

        internal var cellTrips: Map<String, Set<Long>> = emptyMap()
    }

    // ---- Working it out ----------------------------------------------------------------------

    fun cellKey(lat: Double, lon: Double): String =
        "c:" + kotlin.math.floor(lat / CELL_DEG).toLong() + ":" + kotlin.math.floor(lon / CELL_DEG).toLong()

    /**
     * [trips] in any order; [points] holds each trip's points that have a position,
     * oldest first. Trips without positions are skipped.
     */
    fun analyse(trips: List<Trip>, points: Map<Long, List<SpeedPoint>>): Report {
        val sorted = trips.sortedBy { it.startAt }
        val spots = mutableListOf<Spot>()
        val cells = LinkedHashMap<String, Cell>()
        val cellTrips = HashMap<String, MutableSet<Long>>()
        val places = mutableListOf<Place>()
        val stopsByTrip = HashMap<Long, List<Stop>>()
        var tracked = 0L
        var stand = 0L
        var withPlaces = 0

        fun placeAt(lat: Double, lon: Double): Place {
            val near = places.minByOrNull { distM(it.lat, it.lon, lat, lon) }
            val p = if (near != null && distM(near.lat, near.lon, lat, lon) <= PLACE_RADIUS_M) near else Place(lat, lon).also { places += it }
            p.pull(lat, lon)
            return p
        }

        var prevTrip: Trip? = null
        var prevEnd: Place? = null
        for (t in sorted) {
            val pts = points[t.id].orEmpty().filter { it.lat != null && it.lon != null }
            if (pts.size < 2) { prevTrip = null; prevEnd = null; continue }
            withPlaces++

            // Time per point, and road squares.
            for (i in pts.indices) {
                val p = pts[i]
                val next = pts.getOrNull(i + 1)
                val gap = next?.let { it.at - p.at } ?: POINT_MS
                val dt = if (gap in 1..GAP_MS) gap else POINT_MS
                tracked += dt
                if (p.kmh < STOP_KMH) stand += dt
                val la = p.lat!!
                val lo = p.lon!!
                val key = cellKey(la, lo)
                val c = cells.getOrPut(key) { Cell(key, la, lo) }
                c.ms += dt
                if (p.kmh < WAIT_KMH) c.waitMs += dt
                if (p.kmh < STOP_KMH) c.standMs += dt
                if (next != null && gap <= GAP_MS) c.distM += distM(la, lo, next.lat!!, next.lon!!)
                cellTrips.getOrPut(key) { HashSet() } += t.id
            }

            // Standing runs.
            val stops = mutableListOf<Stop>()
            val longStays = mutableListOf<Triple<Long, Long, SpeedPoint>>()
            var i = 0
            while (i < pts.size) {
                if (pts[i].kmh >= STOP_KMH) { i++; continue }
                var j = i
                while (j + 1 < pts.size && pts[j + 1].kmh < STOP_KMH && pts[j + 1].at - pts[j].at <= GAP_MS) j++
                val ms = pts[j].at - pts[i].at + POINT_MS
                val mid = pts[(i + j) / 2]
                val atEdge = i == 0 || j == pts.size - 1
                when {
                    ms >= PARKED_MS && !atEdge -> longStays += Triple(pts[i].at, pts[j].at + POINT_MS, mid)
                    !atEdge && ms >= MIN_STOP_MS -> stops += Stop(t.id, pts[i].at, ms, mid.lat!!, mid.lon!!)
                }
                i = j + 1
            }
            stopsByTrip[t.id] = stops
            for (s in stops) {
                val near = spots.minByOrNull { distM(it.lat, it.lon, s.lat, s.lon) }
                val spot = if (near != null && distM(near.lat, near.lon, s.lat, s.lon) <= SPOT_RADIUS_M) near else Spot(s.lat, s.lon).also { spots += it }
                spot.add(s)
            }

            // Where the trip started and ended.
            val first = pts.first()
            val last = pts.last()
            val start = placeAt(first.lat!!, first.lon!!)
            val end = placeAt(last.lat!!, last.lon!!)
            start.departures++
            // The stay between the last trip and this one, if they meet at the same place.
            val pt = prevTrip
            if (pt != null && prevEnd != null && prevEnd === start) {
                val from = pt.endAt ?: pt.updatedAt
                if (t.startAt - from in 60_000..MAX_STAY_MS) start.stays += Stay(from, t.startAt)
            }
            for ((a, b, p) in longStays) placeAt(p.lat!!, p.lon!!).stays += Stay(a, b)

            // The drive itself: from first moving to last moving, so warming up and parking don't count.
            val firstMove = pts.indexOfFirst { it.kmh >= STOP_KMH }
            val lastMove = pts.indexOfLast { it.kmh >= STOP_KMH }
            if (end !== start && firstMove >= 0 && lastMove > firstMove) {
                var wait = 0L
                for (k in firstMove until lastMove) {
                    val gap = pts[k + 1].at - pts[k].at
                    if (pts[k].kmh < WAIT_KMH) wait += if (gap in 1..GAP_MS) gap else POINT_MS
                }
                end.arrivals += Arrival(t, start, pts[lastMove].at - pts[firstMove].at + POINT_MS, wait, t.endAt ?: t.updatedAt)
            }
            prevTrip = t
            prevEnd = end
        }

        guessHomeAndOffice(places)
        return Report(
            trips = trips.size,
            tripsWithPlaces = withPlaces,
            trackedMs = tracked,
            standMs = stand,
            spots = spots.sortedByDescending { it.totalMs },
            cells = cells.values.toList(),
            places = places.filter { it.arrivals.isNotEmpty() || it.stays.isNotEmpty() }
                .sortedByDescending { it.stayMs + it.arrivals.sumOf { a -> a.driveMs } },
            stopsByTrip = stopsByTrip,
        ).also { it.cellTrips = cellTrips }
    }

    /**
     * Home is where the nights are spent (1–5 AM); the office is where most weekday
     * daytime (Mon–Fri, 9 AM–3 PM) is spent, if it adds up to a few hours.
     */
    private fun guessHomeAndOffice(places: List<Place>) {
        fun overlap(p: Place, test: (Calendar) -> Boolean): Long {
            var ms = 0L
            val c = Calendar.getInstance()
            for (s in p.stays) {
                // Count in 15-minute steps; stays are at most a few days, so this stays cheap.
                var at = s.fromAt
                while (at < s.toAt) {
                    c.timeInMillis = at
                    if (test(c)) ms += minOf(15 * 60_000L, s.toAt - at)
                    at += 15 * 60_000L
                }
            }
            return ms
        }
        val night = places.associateWith { p -> overlap(p) { it.get(Calendar.HOUR_OF_DAY) in 1..4 } }
        val home = night.filter { it.value >= 60 * 60_000L }.maxByOrNull { it.value }?.key
        home?.guess = "Home"
        val day = places.filter { it !== home }.associateWith { p ->
            overlap(p) { c ->
                val dow = c.get(Calendar.DAY_OF_WEEK)
                dow in Calendar.MONDAY..Calendar.FRIDAY && c.get(Calendar.HOUR_OF_DAY) in 9..14
            }
        }
        day.filter { it.value >= 4 * 60 * 60_000L }.maxByOrNull { it.value }?.key?.guess = "Office"
    }

    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
