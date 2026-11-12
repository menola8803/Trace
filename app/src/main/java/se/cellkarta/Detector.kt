package se.cellkarta

import android.location.Location
import java.util.Locale

/**
 * Letar efter tre sorters avvikelser:
 *  1. Ny cell     cell-ID som inte finns i OpenCellID och som du aldrig sett förut
 *  2. Rör sig     samma cell-ID hörs på platser som ligger för långt ifrån varandra
 *  3. Nedgradering  telefonen går från 4G/5G ner till 3G/2G
 *
 * Inget av detta bevisar en IMSI-catcher. Det är ledtrådar som är värda att titta närmare på.
 */
class Detector(private val ctx: android.content.Context, private val db: CellDb, private val notifier: Notifier) {

    companion object {
        /** En vanlig mast hörs sällan längre än så här. Höj värdet om du får falsklarm i glesbygd. */
        const val MOVE_KM = 50.0

        /** Tidsfönster för att jämföra var samma cell har hörts. */
        const val MOVE_WINDOW_MS = 24 * 3_600_000L

        private const val REFERENCE_RECHECK_MS = 5 * 60_000L

        /** Onormal styrka: så här stark signal hörs normalt bara alldeles intill en mast. */
        const val STRONG_LTE_NR_DBM = -55
        const val STRONG_OTHER_DBM = -51

        /** Onormal styrka: så här mycket starkare än cellens vanliga nivå. */
        const val STRONG_JUMP_DB = 20
        private const val STRONG_MIN_HISTORY = 10

        /** Kortvarig: cellen fanns högst så här länge… */
        const val SHORT_MAX_DURATION_MS = 15 * 60_000L
        /** …och har sedan inte hörts på minst så här länge, fast du var kvar inom SHORT_STAY_KM. */
        const val SHORT_GONE_MS = 30 * 60_000L
        const val SHORT_STAY_KM = 0.3
        private const val SHORT_CHECK_MS = 5 * 60_000L

        private val RANK = mapOf("GSM" to 1, "UMTS" to 2, "LTE" to 3, "NR" to 4)
    }

    private var lastServingRadio: String? = null
    private var hasReference = false
    private var referenceCheckedAt = 0L
    private var shortCheckedAt = 0L

    /**
     * [acc] är positionens osäkerhet i meter, större om positionen är gammal. [precise] är false när
     * positionen är för grov eller för gammal: då sparas mätningen ändå, men kontrollerna som bygger
     * på exakt plats (cell som flyttar sig) hoppas över, och trianguleringen använder den inte.
     */
    fun process(all: List<CellObs>, loc: Location, acc: Float = loc.accuracy, precise: Boolean = true) {
        // Samma cell kan förekomma två gånger i samma avläsning. Den anslutna, annars den starkaste, behålls.
        val cells = all.groupBy { it.key }.values.map { same ->
            same.maxWith(compareBy<CellObs>({ it.registered }, { it.dbm ?: -200 }))
        }
        val now = System.currentTimeMillis()
        if (now - referenceCheckedAt > REFERENCE_RECHECK_MS) {
            hasReference = db.knownCount() > 0
            referenceCheckedAt = now
        }

        val lat = loc.latitude
        val lon = loc.longitude
        var changed = false

        // Varje mätning sparas också i dagens fil i Download/Trace.
        val time = java.time.Instant.ofEpochMilli(now).toString()
        TraceFiles.appendMeasurements(ctx, now, cells.map { c ->
            String.format(
                Locale.ROOT, "%s,%s,%s,%.7f,%.7f,%.0f,%s,%d,%s\n",
                time, c.key, c.radio, lat, lon, acc, c.dbm?.toString() ?: "", if (c.registered) 1 else 0,
                c.taM?.let { String.format(Locale.ROOT, "%.0f", it) } ?: ""
            )
        })

        for (c in cells) {
            val firstTime = !db.isSeen(c.key)
            db.recordObservation(c, lat, lon, acc, now)
            val ref = db.knownPos(c.key)

            // 1. Ny cell. Utan referensdata vore alla celler "nya", så då varnar vi inte.
            if (firstTime && hasReference && ref == null && !db.hasFlag(c.key, CellDb.FLAG_NEW)) {
                flag(c.key, CellDb.FLAG_NEW, now, lat, lon, "dbm=${c.dbm ?: ""}")
                changed = true
            }

            // Onormal styrka: mycket stark signal, eller ett stort hopp mot cellens vanliga nivå.
            val dbm = c.dbm
            if (dbm != null && !db.hasFlag(c.key, CellDb.FLAG_STRONG)) {
                val limit = if (c.radio == "LTE" || c.radio == "NR") STRONG_LTE_NR_DBM else STRONG_OTHER_DBM
                val median = db.medianDbm(c.key, 200)
                val jump = median != null && median.second >= STRONG_MIN_HISTORY && dbm - median.first >= STRONG_JUMP_DB
                if (dbm >= limit || jump) {
                    val detail = if (jump) "dbm=$dbm;jump=${dbm - median!!.first};median=${median.first}" else "dbm=$dbm"
                    flag(c.key, CellDb.FLAG_STRONG, now, lat, lon, detail)
                    changed = true
                }
            }

            // 2. Cell som verkar flytta sig.
            if (precise && !db.hasFlag(c.key, CellDb.FLAG_MOVING)) {
                val farthest = db.farthestWithin(c.key, now - MOVE_WINDOW_MS, lat, lon, MOVE_KM) ?: 0.0
                val fromRef = ref?.let { Geo.km(it.first, it.second, lat, lon) } ?: 0.0

                if (farthest > MOVE_KM || fromRef > MOVE_KM) {
                    val detail = (if (farthest > MOVE_KM) "far=%.0f" else "ref=%.0f")
                        .let { String.format(Locale.ROOT, it, if (farthest > MOVE_KM) farthest else fromRef) } +
                        ";dbm=${c.dbm ?: ""}"
                    flag(c.key, CellDb.FLAG_MOVING, now, lat, lon, detail)
                    changed = true
                }
            }
        }

        // Kortvarig cell: kontrolleras var femte minut.
        if (precise && now - shortCheckedAt > SHORT_CHECK_MS) {
            shortCheckedAt = now
            if (checkShortLived(now)) changed = true
        }

        // 3. Nedgradering från 4G/5G.
        val serving = cells.filter { it.registered }.maxByOrNull { RANK[it.radio] ?: 0 }
        if (serving != null) {
            val prev = lastServingRadio
            val prevRank = RANK[prev] ?: 0
            val nowRank = RANK[serving.radio] ?: 0
            if (prev != null && prevRank >= 3 && nowRank < 3) {
                flag(serving.key, CellDb.FLAG_DOWNGRADE, now, lat, lon, "from=$prev;to=${serving.radio};dbm=${serving.dbm ?: ""}")
                changed = true
            }
            lastServingRadio = serving.radio
        }

        LiveStatus.scanned(serving?.describe(), serving?.key, now)
        if (changed) LiveStatus.bump()
    }

    /** Sparar avvikelsen, skriver den till dagens händelsefil och skickar en notis. */
    private fun flag(key: String, type: String, ts: Long, lat: Double, lon: Double, detail: String) {
        db.addFlag(key, type, ts, lat, lon, detail)
        TraceFiles.appendEvent(
            ctx, ts,
            String.format(Locale.ROOT, "%s,%s,%s,%.7f,%.7f,%s\n", java.time.Instant.ofEpochMilli(ts), type, key, lat, lon, detail)
        )
        val body = listOf(Texts.cellLabel(key), Texts.detailText(ctx, type, detail), Texts.explanation(ctx, type))
            .filter { it.isNotBlank() }.joinToString(". ")
        notifier.alert(Texts.alertTitle(ctx, type, detail), body)
    }

    private fun checkShortLived(now: Long): Boolean {
        var found = false
        val candidates = db.shortLivedCandidates(now - 6 * 3_600_000L, now - SHORT_GONE_MS, SHORT_MAX_DURATION_MS)
        for (cell in candidates) {
            val obs = db.observations(cell.key, 0L, 200)
            if (obs.size < 2) continue
            // Du ska ha varit på ungefär samma plats medan cellen fanns…
            val spread = obs.maxOf { Geo.km(it.lat, it.lon, cell.lat, cell.lon) }
            if (spread > SHORT_STAY_KM) continue
            // …och kvar där efter att den försvann. Annars var det du som rörde dig.
            if (!db.stayedNear(cell.key, cell.lastTs + 5 * 60_000L, cell.lat, cell.lon, SHORT_STAY_KM)) continue

            val minutes = ((cell.lastTs - cell.firstTs) / 60_000L).coerceAtLeast(1)
            flag(cell.key, CellDb.FLAG_SHORT, now, cell.lat, cell.lon, "min=$minutes;dbm=${cell.bestDbm ?: ""}")
            found = true
        }
        return found
    }
}
