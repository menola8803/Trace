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

/**
 * En inspelning: från start till stopp. [kind] är LIVE (startad för hand), SCHEDULED (schemalagd)
 * eller EARLIER (från innan inspelningar sparades var för sig, en per dag).
 */
data class Recording(
    val kind: String,
    val tri: Boolean,
    val from: Long,
    val to: Long,
    val measurements: Int,
    val cells: Int,
    val minDbm: Int?,
    val maxDbm: Int?,
) {
    val day: String get() = java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
}

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
    SQLiteOpenHelper(ctx, "cellkarta.db", null, 6) {

    companion object {
        const val FLAG_NEW = "NEW"
        const val KIND_LIVE = "LIVE"
        const val KIND_SCHEDULED = "SCHEDULED"
        const val KIND_EARLIER = "EARLIER"
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
                "acc REAL, dbm INTEGER, registered INTEGER NOT NULL, ta_m REAL)"
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
        createFound(db)
        createSessions(db)
    }

    private fun createSessions(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                "tri INTEGER NOT NULL DEFAULT 0, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL)"
        )
    }

    /** Master som trianguleringen har hittat och placerat ut. */
    private fun createFound(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS found_masts(site_key TEXT PRIMARY KEY, radio TEXT NOT NULL, operator TEXT NOT NULL, " +
                "site INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, radius REAL NOT NULL, quality INTEGER NOT NULL, " +
                "places INTEGER NOT NULL, points INTEGER NOT NULL, ta_points INTEGER NOT NULL, advice INTEGER, ocid_dist REAL, " +
                "alt_lat REAL, alt_lon REAL, sectors TEXT, rings TEXT, ts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_found_lat_lon ON found_masts(lat, lon)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Behåller alla sparade mätningar vid uppdatering.
        if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_ts ON obs(ts)")
        if (oldVersion < 3) createMasts(db)
        if (oldVersion < 4) db.execSQL("ALTER TABLE obs ADD COLUMN ta_m REAL")
        if (oldVersion < 5) createFound(db)
        if (oldVersion < 6) createSessions(db)
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

    /** Alla kända celler för en operatör och ett nät, till exempel "LTE:240:7:". Använder nyckelns index. */
    fun knownWithPrefix(prefix: String): List<KnownCell> {
        val upper = prefix.dropLast(1) + ';' // ';' kommer direkt efter ':' i teckentabellen
        return readableDatabase.rawQuery(
            "SELECT cell_key, lat, lon FROM known_cells WHERE cell_key >= ? AND cell_key < ?", arrayOf(prefix, upper)
        ).use { c -> buildList { while (c.moveToNext()) add(KnownCell(c.getString(0), c.getDouble(1), c.getDouble(2))) } }
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
                if (c.taM != null) put("ta_m", c.taM) else putNull("ta_m")
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
            "SELECT ts, lat, lon, dbm FROM obs WHERE cell_key=? AND ts>=? AND (acc IS NULL OR acc <= ${CellMonitorService.MAX_ACCURACY_M}) ORDER BY ts DESC LIMIT $limit",
            arrayOf(key, since.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ObsRow(c.getLong(0), c.getDouble(1), c.getDouble(2), if (c.isNull(3)) null else c.getInt(3)))
                }
            }.reversed()
        }

    /**
     * Längsta avståndet i km från platsen till en tidigare mätning av cellen, bland mätningar som
     * ligger minst ungefär [km] bort. Null om alla ligger närmare. Databasen sorterar bort de nära
     * mätningarna, så kontrollen kostar nästan inget även för celler med tusentals mätningar.
     */
    fun farthestWithin(key: String, since: Long, lat: Double, lon: Double, km: Double): Double? {
        // En ruta som ryms inuti cirkeln: allt utanför rutan kan ligga längre bort än km.
        val r = km / Math.sqrt(2.0)
        val dLat = r / 111.0
        val dLon = r / (111.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        val sql = "SELECT lat, lon FROM obs WHERE cell_key=? AND ts>=? AND (acc IS NULL OR acc <= ${CellMonitorService.MAX_ACCURACY_M}) AND " +
            "(lat < ${num(lat - dLat)} OR lat > ${num(lat + dLat)} OR lon < ${num(lon - dLon)} OR lon > ${num(lon + dLon)}) LIMIT 2000"
        return readableDatabase.rawQuery(sql, arrayOf(key, since.toString())).use { c ->
            var far: Double? = null
            while (c.moveToNext()) {
                val d = Geo.km(lat, lon, c.getDouble(0), c.getDouble(1))
                if (far == null || d > far) far = d
            }
            far
        }
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

    /** Tar bort alla händelser och avvikelser. Mätningar och celler finns kvar. */
    fun clearFlags() {
        writableDatabase.delete("flags", null, null)
    }

    // ---- Inspelningar ----

    fun startSession(kind: String, tri: Boolean, ts: Long): Long =
        writableDatabase.insert("sessions", null, ContentValues().apply {
            put("kind", kind); put("tri", if (tri) 1 else 0); put("start_ts", ts); put("end_ts", ts)
        })

    /** Flyttar fram slutet. Anropas efter varje sparad avläsning, så det stämmer även om appen stängs. */
    fun touchSession(id: Long, ts: Long, tri: Boolean) {
        writableDatabase.execSQL(
            "UPDATE sessions SET end_ts = MAX(end_ts, ?), tri = MAX(tri, ?) WHERE id = ?",
            arrayOf<Any>(ts, if (tri) 1 else 0, id)
        )
    }

    /**
     * Alla inspelningar, senaste först. Mätningar från innan inspelningarna sparades var för sig
     * visas som en tidigare inspelning per dag. Inspelningar utan mätningar som varade kortare
     * än en minut visas inte.
     */
    fun recordings(): List<Recording> {
        val db = readableDatabase
        data class S(val kind: String, val tri: Boolean, val from: Long, val to: Long)
        val sessions = db.rawQuery("SELECT kind, tri, start_ts, end_ts FROM sessions ORDER BY start_ts DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(S(c.getString(0), c.getInt(1) == 1, c.getLong(2), c.getLong(3))) }
        }
        val out = ArrayList<Recording>()
        for ((kind, tri, from, to) in sessions) {
            db.rawQuery(
                "SELECT COUNT(*), COUNT(DISTINCT cell_key), MIN(dbm), MAX(dbm), MAX(ts) FROM obs WHERE ts >= ? AND ts <= ?",
                arrayOf(from.toString(), (to + 1000L).toString())
            ).use { c ->
                c.moveToFirst()
                val n = c.getInt(0)
                if (n == 0 && to - from < 60_000L) return@use
                out.add(Recording(kind, tri, from, maxOf(to, if (c.isNull(4)) to else c.getLong(4)), n, c.getInt(1),
                    if (c.isNull(2)) null else c.getInt(2), if (c.isNull(3)) null else c.getInt(3)))
            }
        }
        // Tidigare mätningar, per dag
        val firstSession = sessions.minOfOrNull { it.from } ?: Long.MAX_VALUE
        db.rawQuery(
            "SELECT date(ts / 1000, 'unixepoch', 'localtime') AS d, COUNT(*), COUNT(DISTINCT cell_key), MIN(ts), MAX(ts), " +
                "MIN(dbm), MAX(dbm) FROM obs WHERE ts < ? GROUP BY d",
            arrayOf(firstSession.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Recording(KIND_EARLIER, false, c.getLong(3), c.getLong(4), c.getInt(1), c.getInt(2),
                    if (c.isNull(5)) null else c.getInt(5), if (c.isNull(6)) null else c.getInt(6)))
            }
        }
        return out.sortedByDescending { it.from }
    }

    // ---- Hittade master ----

    fun saveFound(r: Triangulate.Result) {
        writableDatabase.insertWithOnConflict("found_masts", null, ContentValues().apply {
            put("site_key", r.siteKey); put("radio", r.radio); put("operator", r.operator); put("site", r.site)
            put("lat", r.lat); put("lon", r.lon); put("radius", r.radiusM); put("quality", r.quality.ordinal)
            put("places", r.places); put("points", r.points); put("ta_points", r.taPoints)
            if (r.adviceOctant != null) put("advice", r.adviceOctant) else putNull("advice")
            if (r.ocidDistM != null) put("ocid_dist", r.ocidDistM) else putNull("ocid_dist")
            if (r.altLat != null && r.altLon != null) { put("alt_lat", r.altLat); put("alt_lon", r.altLon) }
            else { putNull("alt_lat"); putNull("alt_lon") }
            put("sectors", r.sectors.joinToString(";") { "${it.key}|${it.sector ?: ""}|${num(it.bearingDeg)}" })
            put("rings", r.rings.joinToString(";") { "${num(it.lat)}|${num(it.lon)}|${num(it.radiusM)}" })
            put("ts", r.ts)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Hittade master i området, även en bit utanför så att cirklarna syns vid kanten. */
    fun foundInBox(s: Double, w: Double, n: Double, e: Double): List<Triangulate.Result> {
        val mLat = (n - s) * 0.25; val mLon = (e - w) * 0.25
        return readFound("WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY ts DESC LIMIT 300",
            arrayOf((s - mLat).toString(), (n + mLat).toString(), (w - mLon).toString(), (e + mLon).toString()))
    }

    /** Alla hittade master, säkrast först. */
    fun allFound(): List<Triangulate.Result> = readFound("ORDER BY quality DESC, radius ASC", emptyArray())

    fun found(siteKey: String): Triangulate.Result? = readFound("WHERE site_key=?", arrayOf(siteKey)).firstOrNull()

    fun foundCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "found_masts")

    fun deleteFound(siteKey: String) {
        writableDatabase.delete("found_masts", "site_key=?", arrayOf(siteKey))
    }

    fun clearFound() {
        writableDatabase.delete("found_masts", null, null)
    }

    /** Masterna vars celler har nya mätningar sedan tidpunkten. */
    fun cellKeysSince(since: Long): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT cell_key FROM obs WHERE ts >= ?", arrayOf(since.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun readFound(where: String, args: Array<String>): List<Triangulate.Result> =
        readableDatabase.rawQuery(
            "SELECT site_key, radio, operator, site, lat, lon, radius, quality, places, points, ta_points, advice, ocid_dist, " +
                "alt_lat, alt_lon, sectors, rings, ts FROM found_masts $where", args
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val sectors = c.getString(15).orEmpty().split(';').filter { it.isNotEmpty() }.mapNotNull { s ->
                        val p = s.split('|'); if (p.size != 3) null
                        else Triangulate.Sector(p[0], p[1].toLongOrNull(), p[2].toDoubleOrNull() ?: return@mapNotNull null)
                    }
                    val rings = c.getString(16).orEmpty().split(';').filter { it.isNotEmpty() }.mapNotNull { s ->
                        val p = s.split('|').map { it.toDoubleOrNull() }
                        if (p.size != 3 || p.any { it == null }) null else Triangulate.Ring(p[0]!!, p[1]!!, p[2]!!)
                    }
                    add(Triangulate.Result(
                        c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getDouble(4), c.getDouble(5),
                        c.getDouble(6), Triangulate.Quality.entries.getOrElse(c.getInt(7)) { Triangulate.Quality.ROUGH },
                        c.getInt(8), c.getInt(9), c.getInt(10), sectors,
                        if (c.isNull(11)) null else c.getInt(11), rings,
                        if (c.isNull(12)) null else c.getDouble(12), c.getLong(17),
                        if (c.isNull(13)) null else c.getDouble(13), if (c.isNull(14)) null else c.getDouble(14),
                    ))
                }
            }
        }

    /** Tar bort allt du har spelat in: mätningar, celler, händelser och markerade master. */
    fun clearRecordings() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("obs", "seen", "flags", "masts", "found_masts", "sessions").forEach { db.delete(it, null, null) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** En mätning för trianguleringen: plats, noggrannhet, signal och avstånd. */
    data class TriObs(val key: String, val lat: Double, val lon: Double, val acc: Double, val dbm: Int?, val taM: Double?, val ts: Long)

    /** Mätningar av alla celler som börjar med prefixet (operatör och nät), till exempel "LTE:240:7:". */
    fun triObservations(prefix: String, maxAccM: Double, limit: Int = 50_000): List<TriObs> {
        val upper = prefix.dropLast(1) + ';'
        return readableDatabase.rawQuery(
            "SELECT cell_key, lat, lon, acc, dbm, ta_m, ts FROM obs WHERE cell_key >= ? AND cell_key < ? " +
                "AND (acc IS NULL OR acc <= $maxAccM) ORDER BY ts DESC LIMIT $limit",
            arrayOf(prefix, upper)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(TriObs(c.getString(0), c.getDouble(1), c.getDouble(2), if (c.isNull(3)) 0.0 else c.getDouble(3),
                        if (c.isNull(4)) null else c.getInt(4), if (c.isNull(5)) null else c.getDouble(5), c.getLong(6)))
                }
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
        val sql = "SELECT lat, lon FROM obs WHERE ts >= $after AND cell_key != ? AND (acc IS NULL OR acc <= ${CellMonitorService.MAX_ACCURACY_M}) " +
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
        out.write("time_utc,cell,radio,lat,lon,accuracy_m,dbm,connected,ta_m\n")
        readableDatabase.rawQuery(
            "SELECT ts, cell_key, radio, lat, lon, acc, dbm, registered, ta_m FROM obs ORDER BY ts", null
        ).use { c ->
            while (c.moveToNext()) {
                val acc = if (c.isNull(5)) "" else String.format(Locale.ROOT, "%.0f", c.getDouble(5))
                val dbm = if (c.isNull(6)) "" else c.getInt(6).toString()
                val ta = if (c.isNull(8)) "" else String.format(Locale.ROOT, "%.0f", c.getDouble(8))
                out.write(
                    "${Instant.ofEpochMilli(c.getLong(0))},${c.getString(1)},${c.getString(2)}," +
                        "${c.getDouble(3)},${c.getDouble(4)},$acc,$dbm,${c.getInt(7)},$ta\n"
                )
            }
        }
    }
}
