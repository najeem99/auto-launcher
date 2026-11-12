package com.najib.carwelcome.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonReader
import android.util.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * Keeps a copy of every trip in a folder you pick on the phone (for example
 * Documents). Files there stay when the app is uninstalled, so after reinstalling
 * you pick the same folder and the trips come back.
 *
 * The copy is one JSON file, rewritten after every trip. Restoring merges: trips
 * already in the app are skipped, so it is safe to restore more than once.
 */
object TripBackup {
    const val FILE_NAME = "NajeemDrive-trips-backup.json"
    private const val MIME = "application/json"
    private val io = Executors.newSingleThreadExecutor()

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences("trip_backup", Context.MODE_PRIVATE)

    /** The chosen folder, if the app still has permission to write there. */
    fun folder(context: Context): Uri? {
        val s = sp(context).getString("tree", null) ?: return null
        val uri = Uri.parse(s)
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (ok) uri else null
    }

    fun lastBackupAt(context: Context): Long = sp(context).getLong("at", 0L)
    fun lastCount(context: Context): Int = sp(context).getInt("count", 0)

    /** A readable name for the folder, like "Documents". */
    fun folderName(context: Context): String {
        val uri = folder(context) ?: return ""
        return runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "phone storage" } }
            .getOrDefault("the chosen folder")
    }

    /** Remembers [tree] (from the folder picker) and keeps permission to it across restarts. */
    fun setFolder(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        sp(context).edit().putString("tree", tree.toString()).apply()
    }

    /** Writes the backup in the background. [done] gets the number of trips saved, or null on failure. */
    fun backupSoon(context: Context, done: ((Int?) -> Unit)? = null) {
        val app = context.applicationContext
        if (folder(app) == null) { done?.invoke(null); return }
        io.execute {
            val n = runCatching { write(app) }.onFailure { android.util.Log.w("TripBackup", "backup failed", it) }.getOrNull()
            done?.invoke(n)
        }
    }

    /** Backs up if the last copy is older than [maxAgeMs]. */
    fun backupIfStale(context: Context, maxAgeMs: Long) {
        if (folder(context) != null && System.currentTimeMillis() - lastBackupAt(context) > maxAgeMs) backupSoon(context)
    }

    /** Reads the backup in the chosen folder into the app. [done] gets how many trips were added, or null. */
    fun restoreSoon(context: Context, done: (Int?) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val n = runCatching { restore(app) }.onFailure { android.util.Log.w("TripBackup", "restore failed", it) }.getOrNull()
            done(n)
        }
    }

    // ---- Files in the chosen folder ---------------------------------------------------

    private fun findFile(context: Context, tree: Uri): Uri? {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == FILE_NAME) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }

    /** True if the chosen folder already holds a backup file. */
    fun hasBackupFile(context: Context): Boolean {
        val tree = folder(context) ?: return false
        return runCatching { findFile(context, tree) != null }.getOrDefault(false)
    }

    private fun write(context: Context): Int {
        val tree = folder(context) ?: error("No backup folder")
        val db = TripDb.get(context)
        val trips = db.allFinished()
        val prefs = TripPrefs(context)

        // Build the whole file in memory first, so the file on disk is only touched once.
        val bytes = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(bytes, Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("app").value("najeem-drive")
            w.name("version").value(1)
            w.name("exportedAt").value(System.currentTimeMillis())
            w.name("settings").beginObject()
                .name("ratedKmpl").value(prefs.ratedKmpl.toDouble())
                .name("fuelPrice").value(prefs.fuelPrice.toDouble())
                .name("fuelGrade").value(FuelPrices.grade(context).key)
                .name("fuelPriceEdits").value(FuelPrices.editsJson(context))
                .endObject()
            w.name("fillUps").beginArray()
            FuelDb.get(context).all().forEach { f ->
                w.beginObject()
                w.name("at").value(f.at)
                w.name("litres").value(f.litres)
                w.name("aed").value(f.aed)
                f.odometerKm?.let { w.name("odometerKm").value(it) }
                w.name("full").value(f.full)
                w.name("grade").value(f.grade.key)
                w.endObject()
            }
            w.endArray()
            w.name("trips").beginArray()
            trips.forEach { t ->
                w.beginObject()
                w.name("startAt").value(t.startAt)
                w.name("endAt").value(t.endAt ?: t.updatedAt)
                w.name("distanceM").value(t.distanceM)
                w.name("movingMs").value(t.movingMs)
                w.name("slowMs").value(t.slowMs)
                w.name("idleMs").value(t.idleMs)
                w.name("noSignalMs").value(t.noSignalMs)
                w.name("movingDistM").value(t.movingDistM)
                w.name("slowDistM").value(t.slowDistM)
                w.name("topKmh").value(t.topKmh.toDouble())
                w.name("stops").value(t.stops.toLong())
                w.name("carSpeed").value(t.carSpeed)
                w.name("points").beginArray()
                db.points(t.id).forEach { p -> w.beginArray().value(p.at).value(p.kmh.toDouble()).endArray() }
                w.endArray()
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }

        val file = findFile(context, tree)
            ?: DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                MIME, FILE_NAME
            ) ?: error("Couldn't create the backup file")
        context.contentResolver.openOutputStream(file, "wt")!!.use { it.write(bytes.toByteArray()) }
        sp(context).edit().putLong("at", System.currentTimeMillis()).putInt("count", trips.size).apply()
        return trips.size
    }

    private fun restore(context: Context): Int {
        val tree = folder(context) ?: error("No backup folder")
        val file = findFile(context, tree) ?: return 0
        val db = TripDb.get(context)
        val prefs = TripPrefs(context)
        var added = 0
        context.contentResolver.openInputStream(file)!!.use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "settings" -> {
                            r.beginObject()
                            while (r.hasNext()) when (r.nextName()) {
                                "ratedKmpl" -> r.nextDouble().toFloat().takeIf { it > 0 }?.let { prefs.ratedKmpl = it }
                                "fuelPrice" -> r.nextDouble().toFloat().takeIf { it > 0 }?.let { prefs.fuelPrice = it }
                                "fuelGrade" -> FuelPrices.setGrade(context, Grade.of(r.nextString()))
                                "fuelPriceEdits" -> FuelPrices.restoreEdits(context, r.nextString())
                                else -> r.skipValue()
                            }
                            r.endObject()
                        }
                        "fillUps" -> {
                            val fdb = FuelDb.get(context)
                            r.beginArray()
                            while (r.hasNext()) {
                                val f = FillUp()
                                r.beginObject()
                                while (r.hasNext()) when (r.nextName()) {
                                    "at" -> f.at = r.nextLong()
                                    "litres" -> f.litres = r.nextDouble()
                                    "aed" -> f.aed = r.nextDouble()
                                    "odometerKm" -> f.odometerKm = r.nextDouble()
                                    "full" -> f.full = r.nextBoolean()
                                    "grade" -> f.grade = Grade.of(r.nextString())
                                    else -> r.skipValue()
                                }
                                r.endObject()
                                if (f.at > 0 && f.litres > 0) fdb.import(f)
                            }
                            r.endArray()
                        }
                        "trips" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                val (t, pts) = readTrip(r)
                                if (t.startAt > 0 && db.importTrip(t, pts)) added++
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
        }
        return added
    }

    private fun readTrip(r: JsonReader): Pair<Trip, List<SpeedPoint>> {
        val t = Trip()
        val pts = mutableListOf<SpeedPoint>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "startAt" -> t.startAt = r.nextLong()
                "endAt" -> { val e = r.nextLong(); t.endAt = e; t.updatedAt = e }
                "distanceM" -> t.distanceM = r.nextDouble()
                "movingMs" -> t.movingMs = r.nextLong()
                "slowMs" -> t.slowMs = r.nextLong()
                "idleMs" -> t.idleMs = r.nextLong()
                "noSignalMs" -> t.noSignalMs = r.nextLong()
                "movingDistM" -> t.movingDistM = r.nextDouble()
                "slowDistM" -> t.slowDistM = r.nextDouble()
                "topKmh" -> t.topKmh = r.nextDouble().toFloat()
                "stops" -> t.stops = r.nextInt()
                "carSpeed" -> t.carSpeed = r.nextBoolean()
                "points" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginArray()
                        val at = r.nextLong()
                        val kmh = r.nextDouble().toFloat()
                        while (r.hasNext()) r.skipValue()
                        r.endArray()
                        pts += SpeedPoint(at, kmh)
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (t.endAt == null) { t.endAt = t.startAt; t.updatedAt = t.startAt }
        return t to pts
    }
}
