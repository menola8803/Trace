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

        private val RANK = mapOf("GSM" to 1, "UMTS" to 2, "LTE" to 3, "NR" to 4)
    }

    private var lastServingRadio: String? = null
    private var hasReference = false
    private var referenceCheckedAt = 0L

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

        LiveStatus.scanned(serving?.describe(), now)
        if (changed) LiveStatus.bump()
    }
}
