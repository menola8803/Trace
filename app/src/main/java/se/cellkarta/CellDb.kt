package se.cellkarta

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Writer
import java.time.Instant
import java.util.Locale

data class ObsRow(val ts: Long, val lat: Double, val lon: Double, val dbm: Int?)

data class SeenRow(
    val key: String,
    val radio: String,
    val firstTs: Long,
    val lastTs: Long,
    val count: Int,
    val bestDbm: Int?,
    val lat: Double,
    val lon: Double,
)

data class FlagRow(
    val key: String,
    val type: String,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val detail: String?,
)

data class CellEntry(
    val key: String,
    val radio: String,
    val firstTs: Long,
    val lastTs: Long,
    val count: Int,
    val bestDbm: Int?,
    val lat: Double,
    val lon: Double,
    val known: Boolean,
    val flags: Set<String>,
) {
    val moving get() = CellDb.FLAG_MOVING in flags
    val isNew get() = CellDb.FLAG_NEW in flags
    val downgraded get() = CellDb.FLAG_DOWNGRADE in flags
    val strong get() = CellDb.FLAG_STRONG in flags
    val shortLived get() = CellDb.FLAG_SHORT in flags

    /** Onormal antenn som visas med utropstecken på kartan. */
    val anomalous get() = moving || strong || shortLived || isNew

    /** Avvikelsetyperna, viktigast först. */
    val reasonTypes: List<String>
        get() = buildList {
            if (moving) add(CellDb.FLAG_MOVING)
            if (strong) add(CellDb.FLAG_STRONG)
            if (shortLived) add(CellDb.FLAG_SHORT)
            if (isNew) add(CellDb.FLAG_NEW)
            if (downgraded) add(CellDb.FLAG_DOWNGRADE)
        }

    /** Kort status för listan, på valt språk. */
    fun statusLabel(ctx: android.content.Context): String = ctx.getString(
        when {
            moving -> R.string.reason_moving
            strong -> R.string.status_strong
            shortLived -> R.string.reason_short
            isNew -> R.string.status_new
            downgraded -> R.string.reason_downgrade
            known -> R.string.status_known
            else -> R.string.status_unknown
        }
    )

    /** Alla orsaker i klartext, på valt språk. */
    fun reasons(ctx: android.content.Context): List<String> = reasonTypes.map { Texts.reasonName(ctx, it) }
}

/** Sammanfattning av en dag i loggen. */
data class DaySummary(
    val day: String,          // ÅÅÅÅ-MM-DD, lokal tid
    val measurements: Int,
    val cells: Int,
    val firstTs: Long,
    val lastTs: Long,
    val minDbm: Int?,
    val maxDbm: Int?,
)

data class Cluster(val lat: Double, val lon: Double, val count: Int)

data class UserMast(val id: Long, val lat: Double, val lon: Double, val ts: Long)

/** En mätning för uppspelningen. */
data class ReplayRow(val ts: Long, val lat: Double, val lon: Double, val key: String, val dbm: Int?, val registered: Boolean)

data class KnownCell(val key: String, val lat: Double, val lon: Double)

/**
 * Lokal databas.
 *  known_cells  referensdata från OpenCellID (kända master i Sverige)
 *  obs          varje enskild mätning: cell, tid, GPS-position, signal
 *  seen         sammanfattning per cell med uppskattad position
 *  flags        avvikelser: ny cell, cell som flyttar sig, nedgradering
 */
class CellDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "cellkarta.db", null, 3) {

    companion object {
        const val FLAG_NEW = "NEW"
        const val FLAG_MOVING = "MOVING"
        const val FLAG_DOWNGRADE = "DOWNGRADE"
        const val FLAG_STRONG = "STRONG"
        const val FLAG_SHORT = "SHORT"

        /** Avvikelser som gäller själva antennen (inte nedgradering, som är en händelse). */
        val ANTENNA_FLAGS = listOf(FLAG_NEW, FLAG_MOVING, FLAG_STRONG, FLAG_SHORT)

        @Volatile private var instance: CellDb? = null

        fun get(ctx: Context): CellDb = instance ?: synchronized(this) {
            instance ?: CellDb(ctx.applicationContext).also { instance = it }
        }

        private fun num(v: Double) = String.format(Locale.ROOT, "%.6f", v)
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE known_cells(cell_key TEXT PRIMARY KEY, lat REAL NOT NULL, " +
                "lon REAL NOT NULL, range_m INTEGER)"
        )
        db.execSQL("CREATE INDEX idx_known_lat_lon ON known_cells(lat, lon)")
        db.execSQL(
            "CREATE TABLE obs(id INTEGER PRIMARY KEY AUTOINCREMENT, cell_key TEXT NOT NULL, " +
                "radio TEXT NOT NULL, ts INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, " +
                "acc REAL, dbm INTEGER, registered INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_obs_key_ts ON obs(cell_key, ts)")
        db.execSQL("CREATE INDEX idx_obs_ts ON obs(ts)")
        db.execSQL(
            "CREATE TABLE seen(cell_key TEXT PRIMARY KEY, radio TEXT NOT NULL, " +
                "first_ts INTEGER NOT NULL, last_ts INTEGER NOT NULL, count INTEGER NOT NULL, " +
                "best_dbm INTEGER, wsum REAL NOT NULL, wlat REAL NOT NULL, wlon REAL NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE flags(id INTEGER PRIMARY KEY AUTOINCREMENT, cell_key TEXT NOT NULL, " +
                "type TEXT NOT NULL, ts INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, detail TEXT)"
        )
        db.execSQL("CREATE INDEX idx_flags_type ON flags(type, cell_key)")
        createMasts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Behåller alla sparade mätningar vid uppdatering.
        if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_ts ON obs(ts)")
        if (oldVersion < 3) createMasts(db)
    }

    /** Master som användaren själv har markerat på kartan. */
    private fun createMasts(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS masts(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, ts INTEGER NOT NULL)"
        )
    }

    fun addMast(lat: Double, lon: Double, ts: Long): Long =
        writableDatabase.insert("masts", null, ContentValues().apply {
            put("lat", lat)
            put("lon", lon)
            put("ts", ts)
        })

    fun deleteMast(id: Long) {
        writableDatabase.delete("masts", "id=?", arrayOf(id.toString()))
    }

    fun masts(): List<UserMast> =
        readableDatabase.rawQuery("SELECT id, lat, lon, ts FROM masts ORDER BY ts", null).use { c ->
            buildList { while (c.moveToNext()) add(UserMast(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getLong(3))) }
        }

    /** Egna celler vars uppskattade position ligger inom radien. */
    fun seenNear(lat: Double, lon: Double, km: Double): List<CellEntry> {
        val dLat = km / 111.0
        val dLon = km / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return seenInBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon, 500)
            .filter { Geo.km(lat, lon, it.lat, it.lon) <= km }
    }

    /** Kända celler från OpenCellID inom radien. */
    fun knownNear(lat: Double, lon: Double, km: Double): List<KnownCell> {
        val dLat = km / 111.0
        val dLon = km / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return knownInBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon, 500)
            .filter { Geo.km(lat, lon, it.lat, it.lon) <= km }
    }

    // ---- Referensdata ----

    fun knownCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "known_cells")

    fun knownPos(key: String): Pair<Double, Double>? =
        readableDatabase.rawQuery("SELECT lat, lon FROM known_cells WHERE cell_key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getDouble(0) to c.getDouble(1) else null
        }

    fun knownInBox(s: Double, w: Double, n: Double, e: Double, limit: Int): List<KnownCell> {
        val sql = "SELECT cell_key, lat, lon FROM known_cells " +
            "WHERE lat BETWEEN ${num(s)} AND ${num(n)} AND lon BETWEEN ${num(w)} AND ${num(e)} LIMIT $limit"
        return readableDatabase.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(KnownCell(c.getString(0), c.getDouble(1), c.getDouble(2))) }
        }
    }

    /** Slår ihop kända master till kluster i ett rutnät, för utzoomad vy över hela Sverige. */
    fun clustersInBox(s: Double, w: Double, n: Double, e: Double, grid: Double): List<Cluster> {
        val g = num(grid)
        val sql = "SELECT AVG(lat), AVG(lon), COUNT(*) FROM known_cells " +
            "WHERE lat BETWEEN ${num(s)} AND ${num(n)} AND lon BETWEEN ${num(w)} AND ${num(e)} " +
            "GROUP BY CAST(lat / $g AS INTEGER), CAST(lon / $g AS INTEGER)"
        return readableDatabase.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(Cluster(c.getDouble(0), c.getDouble(1), c.getInt(2))) }
        }
    }

    // ---- Egna mätningar ----

    fun isSeen(key: String): Boolean =
        DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM seen WHERE cell_key=?", arrayOf(key)) > 0

    fun recordObservation(c: CellObs, lat: Double, lon: Double, acc: Float, ts: Long) {
        val db = writableDatabase
        val w = Geo.weight(c.dbm)
        db.beginTransaction()
        try {
            db.insert("obs", null, ContentValues().apply {
                put("cell_key", c.key)
                put("radio", c.radio)
                put("ts", ts)
                put("lat", lat)
                put("lon", lon)
                put("acc", acc)
                if (c.dbm != null) put("dbm", c.dbm) else putNull("dbm")
                put("registered", if (c.registered) 1 else 0)
            })

            db.rawQuery(
                "SELECT count, best_dbm, wsum, wlat, wlon FROM seen WHERE cell_key=?", arrayOf(c.key)
            ).use { cur ->
                if (cur.moveToFirst()) {
                    val oldBest = if (cur.isNull(1)) null else cur.getInt(1)
                    val best = listOfNotNull(oldBest, c.dbm).maxOrNull()
                    db.update("seen", ContentValues().apply {
                        put("last_ts", ts)
                        put("count", cur.getInt(0) + 1)
                        if (best != null) put("best_dbm", best) else putNull("best_dbm")
                        put("wsum", cur.getDouble(2) + w)
                        put("wlat", cur.getDouble(3) + lat * w)
                        put("wlon", cur.getDouble(4) + lon * w)
                    }, "cell_key=?", arrayOf(c.key))
                } else {
                    db.insert("seen", null, ContentValues().apply {
                        put("cell_key", c.key)
                        put("radio", c.radio)
                        put("first_ts", ts)
                        put("last_ts", ts)
                        put("count", 1)
                        if (c.dbm != null) put("best_dbm", c.dbm) else putNull("best_dbm")
                        put("wsum", w)
                        put("wlat", lat * w)
                        put("wlon", lon * w)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun observations(key: String, since: Long, limit: Int): List<ObsRow> =
        readableDatabase.rawQuery(
            "SELECT ts, lat, lon, dbm FROM obs WHERE cell_key=? AND ts>=? ORDER BY ts DESC LIMIT $limit",
            arrayOf(key, since.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ObsRow(c.getLong(0), c.getDouble(1), c.getDouble(2), if (c.isNull(3)) null else c.getInt(3)))
                }
            }.reversed()
        }

    // ---- Avvikelser ----

    fun hasFlag(key: String, type: String): Boolean =
        DatabaseUtils.longForQuery(
            readableDatabase, "SELECT COUNT(*) FROM flags WHERE cell_key=? AND type=?", arrayOf(key, type)
        ) > 0

    fun addFlag(key: String, type: String, ts: Long, lat: Double, lon: Double, detail: String) {
        writableDatabase.insert("flags", null, ContentValues().apply {
            put("cell_key", key)
            put("type", type)
            put("ts", ts)
            put("lat", lat)
            put("lon", lon)
            put("detail", detail)
        })
    }

    fun flaggedSeen(type: String): List<SeenRow> =
        readableDatabase.rawQuery(
            "SELECT cell_key, radio, first_ts, last_ts, count, best_dbm, wlat / wsum, wlon / wsum " +
                "FROM seen WHERE cell_key IN (SELECT cell_key FROM flags WHERE type=?)",
            arrayOf(type)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(SeenRow(
                        c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4),
                        if (c.isNull(5)) null else c.getInt(5), c.getDouble(6), c.getDouble(7)
                    ))
                }
            }
        }

    fun flags(type: String, limit: Int): List<FlagRow> =
        readableDatabase.rawQuery(
            "SELECT cell_key, type, ts, lat, lon, detail FROM flags WHERE type=? ORDER BY ts DESC LIMIT $limit",
            arrayOf(type)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FlagRow(c.getString(0), c.getString(1), c.getLong(2), c.getDouble(3), c.getDouble(4), c.getString(5)))
                }
            }
        }

    /** Alla avvikelser och nedgraderingar, med platsen där de upptäcktes. */
    fun allFlags(limit: Int): List<FlagRow> =
        readableDatabase.rawQuery(
            "SELECT cell_key, type, ts, lat, lon, detail FROM flags ORDER BY ts DESC LIMIT $limit", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FlagRow(c.getString(0), c.getString(1), c.getLong(2), c.getDouble(3), c.getDouble(4), c.getString(5)))
                }
            }
        }

    fun flagsBetween(from: Long, to: Long): List<FlagRow> =
        readableDatabase.rawQuery(
            "SELECT cell_key, type, ts, lat, lon, detail FROM flags WHERE ts >= $from AND ts < $to ORDER BY ts", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FlagRow(c.getString(0), c.getString(1), c.getLong(2), c.getDouble(3), c.getDouble(4), c.getString(5)))
                }
            }
        }

    fun flagsFor(key: String): List<FlagRow> =
        readableDatabase.rawQuery(
            "SELECT cell_key, type, ts, lat, lon, detail FROM flags WHERE cell_key=? ORDER BY ts", arrayOf(key)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FlagRow(c.getString(0), c.getString(1), c.getLong(2), c.getDouble(3), c.getDouble(4), c.getString(5)))
                }
            }
        }

    /** En rad per dag (lokal tid) med antal mätningar, celler, tider och signalspann. */
    fun days(): List<DaySummary> =
        readableDatabase.rawQuery(
            "SELECT date(ts / 1000, 'unixepoch', 'localtime') AS d, COUNT(*), COUNT(DISTINCT cell_key), " +
                "MIN(ts), MAX(ts), MIN(dbm), MAX(dbm) FROM obs GROUP BY d ORDER BY d DESC",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(DaySummary(
                        c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getLong(4),
                        if (c.isNull(5)) null else c.getInt(5), if (c.isNull(6)) null else c.getInt(6),
                    ))
                }
            }
        }

    /** Alla mätningar mellan två tidpunkter, i tidsordning, för uppspelningen. */
    fun replayRows(from: Long, to: Long, limit: Int = 60_000): List<ReplayRow> =
        readableDatabase.rawQuery(
            "SELECT ts, lat, lon, cell_key, dbm, registered FROM obs WHERE ts >= $from AND ts < $to ORDER BY ts LIMIT $limit",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ReplayRow(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getString(3),
                        if (c.isNull(4)) null else c.getInt(4), c.getInt(5) == 1))
                }
            }
        }

    fun flagCounts(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT type, COUNT(*) FROM flags GROUP BY type", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    /** Celler du själv har hört, med uppskattad position inom kartutsnittet. */
    fun seenInBox(s: Double, w: Double, n: Double, e: Double, limit: Int): List<CellEntry> =
        allSeenWhere(
            "WHERE s.wlat / s.wsum BETWEEN ${num(s)} AND ${num(n)} " +
                "AND s.wlon / s.wsum BETWEEN ${num(w)} AND ${num(e)}",
            limit
        )

    /** Celler med någon av antennavvikelserna (ny, rör sig, stark, kortvarig). */
    fun anomalies(): List<CellEntry> =
        allSeenWhere(
            "WHERE s.cell_key IN (SELECT cell_key FROM flags WHERE type IN " +
                ANTENNA_FLAGS.joinToString(",", "(", ")") { "'$it'" } + ")",
            null
        )

    /**
     * Celler som telefonen anslöt till, som bara fanns under kort tid och som
     * sedan inte har hörts på minst en halvtimme.
     */
    fun shortLivedCandidates(lastFrom: Long, lastTo: Long, maxDurationMs: Long): List<CellEntry> =
        allSeenWhere(
            "WHERE s.last_ts BETWEEN $lastFrom AND $lastTo " +
                "AND s.last_ts - s.first_ts <= $maxDurationMs AND s.count >= 2 " +
                "AND EXISTS(SELECT 1 FROM obs o WHERE o.cell_key = s.cell_key AND o.registered = 1) " +
                "AND NOT EXISTS(SELECT 1 FROM flags f WHERE f.cell_key = s.cell_key AND f.type = '$FLAG_SHORT')",
            50
        )

    /** Fanns telefonen kvar nära platsen efter tidpunkten? Avgörs av mätningar av andra celler. */
    fun stayedNear(exceptKey: String, after: Long, lat: Double, lon: Double, km: Double): Boolean {
        val dLat = km / 111.0
        val dLon = km / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        val sql = "SELECT lat, lon FROM obs WHERE ts >= $after AND cell_key != ? " +
            "AND lat BETWEEN ${num(lat - dLat)} AND ${num(lat + dLat)} " +
            "AND lon BETWEEN ${num(lon - dLon)} AND ${num(lon + dLon)} LIMIT 200"
        return readableDatabase.rawQuery(sql, arrayOf(exceptKey)).use { c ->
            var found = false
            while (!found && c.moveToNext()) found = Geo.km(lat, lon, c.getDouble(0), c.getDouble(1)) <= km
            found
        }
    }

    /** Median av cellens tidigare signalstyrka och antalet mätningar den bygger på. */
    fun medianDbm(key: String, limit: Int): Pair<Int, Int>? {
        val values = readableDatabase.rawQuery(
            "SELECT dbm FROM obs WHERE cell_key=? AND dbm IS NOT NULL ORDER BY ts DESC LIMIT $limit",
            arrayOf(key)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }.sorted()
        if (values.isEmpty()) return null
        return values[values.size / 2] to values.size
    }

    fun seenCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "seen")

    // ---- Lista över sparade celler ----

    /** Alla celler appen har hört, med status mot referensdata och avvikelser. */
    fun allSeen(): List<CellEntry> = allSeenWhere("", null)

    private fun allSeenWhere(where: String, limit: Int?): List<CellEntry> =
        readableDatabase.rawQuery(
            "SELECT s.cell_key, s.radio, s.first_ts, s.last_ts, s.count, s.best_dbm, " +
                "s.wlat / s.wsum, s.wlon / s.wsum, " +
                "EXISTS(SELECT 1 FROM known_cells k WHERE k.cell_key = s.cell_key), " +
                "(SELECT GROUP_CONCAT(DISTINCT f.type) FROM flags f WHERE f.cell_key = s.cell_key) " +
                "FROM seen s $where ORDER BY s.last_ts DESC" + (limit?.let { " LIMIT $it" } ?: ""),
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(CellEntry(
                        key = c.getString(0),
                        radio = c.getString(1),
                        firstTs = c.getLong(2),
                        lastTs = c.getLong(3),
                        count = c.getInt(4),
                        bestDbm = if (c.isNull(5)) null else c.getInt(5),
                        lat = c.getDouble(6),
                        lon = c.getDouble(7),
                        known = c.getInt(8) == 1,
                        flags = if (c.isNull(9)) emptySet() else c.getString(9).split(',').toSet(),
                    ))
                }
            }
        }

    // ---- Export ----

    fun exportObservations(out: Writer) {
        out.write("tid_utc,cell,radio,lat,lon,noggrannhet_m,dbm,ansluten\n")
        readableDatabase.rawQuery(
            "SELECT ts, cell_key, radio, lat, lon, acc, dbm, registered FROM obs ORDER BY ts", null
        ).use { c ->
            while (c.moveToNext()) {
                val acc = if (c.isNull(5)) "" else String.format(Locale.ROOT, "%.0f", c.getDouble(5))
                val dbm = if (c.isNull(6)) "" else c.getInt(6).toString()
                out.write(
                    "${Instant.ofEpochMilli(c.getLong(0))},${c.getString(1)},${c.getString(2)}," +
                        "${c.getDouble(3)},${c.getDouble(4)},$acc,$dbm,${c.getInt(7)}\n"
                )
            }
        }
    }
}
