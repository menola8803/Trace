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
class Detector(private val db: CellDb, private val notifier: Notifier) {

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

    fun process(cells: List<CellObs>, loc: Location) {
        val now = System.currentTimeMillis()
        if (now - referenceCheckedAt > REFERENCE_RECHECK_MS) {
            hasReference = db.knownCount() > 0
            referenceCheckedAt = now
        }

        val lat = loc.latitude
        val lon = loc.longitude
        var changed = false

        for (c in cells) {
            val firstTime = !db.isSeen(c.key)
            db.recordObservation(c, lat, lon, loc.accuracy, now)
            val ref = db.knownPos(c.key)

            // 1. Ny cell. Utan referensdata vore alla celler "nya", så då varnar vi inte.
            if (firstTime && hasReference && ref == null && !db.hasFlag(c.key, CellDb.FLAG_NEW)) {
                db.addFlag(c.key, CellDb.FLAG_NEW, now, lat, lon, "Första gången sedd, ${c.dbm ?: "?"} dBm")
                notifier.alert("Ny okänd cell", "${c.label()} finns inte i referensdatan.")
                changed = true
            }

            // Onormal styrka: mycket stark signal, eller ett stort hopp mot cellens vanliga nivå.
            val dbm = c.dbm
            if (dbm != null && !db.hasFlag(c.key, CellDb.FLAG_STRONG)) {
                val limit = if (c.radio == "LTE" || c.radio == "NR") STRONG_LTE_NR_DBM else STRONG_OTHER_DBM
                val median = db.medianDbm(c.key, 200)
                val jump = median != null && median.second >= STRONG_MIN_HISTORY && dbm - median.first >= STRONG_JUMP_DB
                if (dbm >= limit || jump) {
                    val detail = if (jump)
                        "$dbm dBm, ${dbm - median!!.first} dB starkare än vanligt (${median.first} dBm)"
                    else "$dbm dBm, ovanligt stark signal"
                    db.addFlag(c.key, CellDb.FLAG_STRONG, now, lat, lon, detail)
                    notifier.alert("Onormal signalstyrka", "${c.label()}. $detail.")
                    changed = true
                }
            }

            // 2. Cell som verkar flytta sig.
            if (!db.hasFlag(c.key, CellDb.FLAG_MOVING)) {
                val farthest = db.observations(c.key, now - MOVE_WINDOW_MS, 2000)
                    .maxOfOrNull { Geo.km(it.lat, it.lon, lat, lon) } ?: 0.0
                val fromRef = ref?.let { Geo.km(it.first, it.second, lat, lon) } ?: 0.0

                if (farthest > MOVE_KM || fromRef > MOVE_KM) {
                    val detail = if (farthest > MOVE_KM)
                        String.format(Locale.ROOT, "Hörd %.0f km isär inom 24 timmar", farthest)
                    else
                        String.format(Locale.ROOT, "Hörd %.0f km från sin registrerade position", fromRef)
                    db.addFlag(c.key, CellDb.FLAG_MOVING, now, lat, lon, detail)
                    notifier.alert("Cell som verkar flytta sig", "${c.label()}. $detail.")
                    changed = true
                }
            }
        }

        // Kortvarig cell: kontrolleras var femte minut.
        if (now - shortCheckedAt > SHORT_CHECK_MS) {
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
                val detail = "$prev → ${serving.radio}"
                db.addFlag(serving.key, CellDb.FLAG_DOWNGRADE, now, lat, lon, detail)
                notifier.alert("Nedgradering till ${serving.radio}", "Telefonen gick från $detail. ${serving.label()}.")
                changed = true
            }
            lastServingRadio = serving.radio
        }

        LiveStatus.scanned(serving?.describe(), serving?.key, now)
        if (changed) LiveStatus.bump()
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
            val detail = "Fanns i cirka $minutes min och försvann medan du var kvar"
            db.addFlag(cell.key, CellDb.FLAG_SHORT, now, cell.lat, cell.lon, detail)
            notifier.alert("Kortvarig cell", "${cell.key}. $detail.")
            found = true
        }
        return found
    }
}
