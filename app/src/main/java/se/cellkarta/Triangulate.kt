package se.cellkarta

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangulering: räknar ut var en mast står från dina mätningar av dess sektorer.
 *
 *  - Timing Advance ger avståndet från varje mätpunkt till antennen (cirka 78 m per steg).
 *    Antennen sitter högt, så TA mäter snedavståndet. Signalen studsar ofta, och då blir
 *    vägen längre: TA visar därför oftare för långt än för kort, och det tas hänsyn till.
 *  - Signalstyrkan avtar med avståndet. Lutningen är gemensam för masten men varje sektor
 *    får en egen nivå, eftersom sektorerna pekar åt olika håll.
 *  - Enstaka mätningar som inte stämmer alls (en studs, ett GPS-hopp) får begränsad vikt.
 *  - Appen provar ett rutnät av tänkbara mastplatser, väljer den som bäst förklarar alla
 *    mätningar och förfinar runt den. Alla platser som nästan lika bra förklarar mätningarna
 *    bildar osäkerhetscirkeln. Mätningar från samma ställe räknas inte som oberoende.
 *  - Ligger mätningarna nästan på en rak linje (en väg) kan masten stå på vilken sida som helst.
 *    Då visas båda platserna.
 *
 * För 4G och 5G räknas hela masten (alla sektorer). För 3G och 2G går masten inte att utläsa
 * ur cell-ID, så där räknas den enskilda cellens antenn.
 *
 * Mätningarna slås ihop per 15 m och sektor, så att många mätningar från samma plats inte väger
 * tyngre än några få från en ny plats.
 */
object Triangulate {

    enum class Quality { INSUFFICIENT, ROUGH, GOOD, SECURE }

    data class Sector(val key: String, val sector: Long?, val bearingDeg: Double)
    data class Ring(val lat: Double, val lon: Double, val radiusM: Double)

    data class Result(
        val siteKey: String,
        val radio: String,
        val operator: String,
        val site: Long,
        val lat: Double,
        val lon: Double,
        val radiusM: Double,
        val quality: Quality,
        val places: Int,
        val points: Int,
        val taPoints: Int,
        val sectors: List<Sector>,
        /** Väderstreck (0 = norr … 7 = nordväst) där det saknas mätningar, eller null. */
        val adviceOctant: Int?,
        val rings: List<Ring>,
        val ocidDistM: Double?,
        val ts: Long,
        /** Andra möjliga platsen när mätningarna ligger nästan på en linje, annars null. */
        val altLat: Double? = null,
        val altLon: Double? = null,
    ) {
        val ambiguous get() = altLat != null && altLon != null
    }

    private const val BIN_M = 15.0
    private const val PLACE_M = 30.0
    private const val MAX_BINS = 400

    /** Antennens ungefärliga höjd över marken. */
    private const val ANTENNA_H = 25.0
    /** TA kortare än avståndet: ovanligt (avrundning, ett steg). */
    private const val SIG_TA_SHORT = 45.0
    /** TA längre än avståndet: vanligt, signalen tar en omväg. */
    private const val SIG_TA_LONG = 90.0
    private const val SIG_DB = 7.0
    /** Gränser för hur mycket en enskild mätning kan väga (i standardavvikelser). */
    private const val ROBUST_TA = 2.5
    private const val ROBUST_DB = 2.0

    private class Bin(
        val x: Double, val y: Double, val key: String, val dbm: Double?, val ta: Double?,
        val sigShort: Double, val sigLong: Double,
    )

    /** Mastens nummer i cellnyckeln. 3G och 2G: cellens eget nummer. */
    private fun siteOf(cellKey: String): Long? {
        val p = cellKey.split(':')
        if (p.size != 5) return null
        val cid = p[4].toLongOrNull() ?: return null
        return when (p[0]) {
            "LTE", "NR" -> Operators.mastId(cellKey)?.second
            "UMTS", "GSM" -> cid
            else -> null
        }
    }

    /** Nyckel för en mast: nät, land, operatör och mastnummer, till exempel "LTE:240:7:331321". */
    fun siteKeyOf(cellKey: String): String? {
        val p = cellKey.split(':')
        val site = siteOf(cellKey) ?: return null
        return "${p[0]}:${p[1]}:${p[2]}:$site"
    }

    /** Beräknar masten som cellen hör till. Null om det saknas mätningar. */
    fun forCell(db: CellDb, cellKey: String): Result? = siteKeyOf(cellKey)?.let { forSite(db, it) }

    fun forSite(db: CellDb, siteKey: String): Result? {
        val p = siteKey.split(':')
        if (p.size != 4) return null
        val prefix = "${p[0]}:${p[1]}:${p[2]}:"
        val site = p[3].toLongOrNull() ?: return null
        val mcc = p[1].toIntOrNull() ?: return null
        val mnc = p[2].toIntOrNull() ?: return null
        val obs = db.triObservations(prefix, CellMonitorService.MAX_ACCURACY_M.toDouble()).filter { siteOf(it.key) == site }
        if (obs.isEmpty()) return null
        val known = when (p[0]) {
            "LTE", "NR" -> MastLookup.siteCells(db, p[0], mcc, mnc, site)
            else -> obs.map { it.key }.distinct().mapNotNull { k -> db.knownPos(k)?.let { KnownCell(k, it.first, it.second) } }
        }
        return solve(siteKey, p[0], Operators.name(mcc, mnc), site, obs, known)
    }

    /**
     * Beräknar flera master på en gång. Mätningarna läses en gång per operatör och nät,
     * i stället för en gång per mast.
     */
    fun forSites(db: CellDb, siteKeys: Collection<String>): List<Result> =
        siteKeys.groupBy { it.substringBeforeLast(':') }.flatMap { (prefix, keys) ->
            val p = prefix.split(':')
            val mcc = p.getOrNull(1)?.toIntOrNull() ?: return@flatMap emptyList()
            val mnc = p.getOrNull(2)?.toIntOrNull() ?: return@flatMap emptyList()
            val bySite = db.triObservations("$prefix:", CellMonitorService.MAX_ACCURACY_M.toDouble()).groupBy { siteOf(it.key) }
            keys.mapNotNull { key ->
                val site = key.substringAfterLast(':').toLongOrNull() ?: return@mapNotNull null
                val obs = bySite[site].orEmpty()
                if (obs.isEmpty()) return@mapNotNull null
                val known = when (p[0]) {
                    "LTE", "NR" -> MastLookup.siteCells(db, p[0], mcc, mnc, site)
                    else -> obs.map { it.key }.distinct().mapNotNull { k -> db.knownPos(k)?.let { KnownCell(k, it.first, it.second) } }
                }
                runCatching { solve(key, p[0], Operators.name(mcc, mnc), site, obs, known) }.getOrNull()
            }
        }

    /** Begränsar hur mycket en mätning som inte stämmer alls kan påverka (Cauchy). */
    private fun robust(e2: Double, c: Double): Double = c * c * ln(1.0 + e2 / (c * c))

    fun solve(
        siteKey: String, radio: String, operator: String, site: Long,
        obs: List<CellDb.TriObs>, known: List<KnownCell>,
    ): Result? {
        if (obs.isEmpty()) return null
        // Lokala koordinater i meter runt mätningarnas mitt
        val lat0 = obs.map { it.lat }.average()
        val lon0 = obs.map { it.lon }.average()
        val mx = 111_320.0 * cos(Math.toRadians(lat0))
        val my = 110_540.0
        fun toLatLon(x: Double, y: Double) = (lat0 + y / my) to (lon0 + x / mx)

        // Slå ihop per 15 m och sektor
        data class Acc(var sx: Double = 0.0, var sy: Double = 0.0, var n: Int = 0,
                       val dbm: MutableList<Int> = ArrayList(), val ta: MutableList<Double> = ArrayList(),
                       val acc: MutableList<Double> = ArrayList())
        val acc = HashMap<Triple<Long, Long, String>, Acc>()
        for (o in obs) {
            val x = (o.lon - lon0) * mx
            val y = (o.lat - lat0) * my
            val a = acc.getOrPut(Triple(Math.round(x / BIN_M), Math.round(y / BIN_M), o.key)) { Acc() }
            a.sx += x; a.sy += y; a.n++
            o.dbm?.let { a.dbm.add(it) }
            o.taM?.let { a.ta.add(it) }
            a.acc.add(o.acc)
        }
        var bins = acc.entries.map { (k, a) ->
            val ta = a.ta.sorted()
            val gps = a.acc.sorted()[a.acc.size / 2]
            Bin(a.sx / a.n, a.sy / a.n, k.third,
                if (a.dbm.isEmpty()) null else a.dbm.average(),
                if (ta.isEmpty()) null else ta[ta.size / 2],
                sqrt(SIG_TA_SHORT * SIG_TA_SHORT + gps * gps),
                sqrt(SIG_TA_LONG * SIG_TA_LONG + gps * gps))
        }
        if (bins.isEmpty()) return null
        if (bins.size > MAX_BINS) bins = bins.shuffled(java.util.Random(1)).take(MAX_BINS)

        val places = bins.map { Math.round(it.x / PLACE_M) to Math.round(it.y / PLACE_M) }.toSet().size
        val taBins = bins.count { it.ta != null }

        // Sökområde: runt mätningarna, minst 800 m, längre om avstånden säger det
        val maxTa = bins.mapNotNull { it.ta }.maxOrNull() ?: 0.0
        val r = min(5000.0, max(800.0, maxTa + 300.0))
        val cx = (bins.minOf { it.x } + bins.maxOf { it.x }) / 2
        val cy = (bins.minOf { it.y } + bins.maxOf { it.y }) / 2
        val step = max(20.0, 2 * r / 120)
        val n = (r / step).toInt()

        val keys = bins.map { it.key }.distinct()
        val keyIx = keys.withIndex().associate { it.value to it.index }
        val binKey = IntArray(bins.size) { keyIx[bins[it].key]!! }
        val sumL = DoubleArray(keys.size)
        val sumV = DoubleArray(keys.size)
        val cnt = IntArray(keys.size)
        val ls = DoubleArray(bins.size)
        val h2 = ANTENNA_H * ANTENNA_H

        fun cost(px: Double, py: Double): Double {
            var c = 0.0
            java.util.Arrays.fill(sumL, 0.0); java.util.Arrays.fill(sumV, 0.0); java.util.Arrays.fill(cnt, 0)
            for ((i, b) in bins.withIndex()) {
                val d = max(15.0, hypot(b.x - px, b.y - py))
                if (b.ta != null) {
                    val res = b.ta - sqrt(d * d + h2)
                    val e = if (res < 0) res / b.sigShort else res / b.sigLong
                    c += robust(e * e, ROBUST_TA)
                }
                if (b.dbm != null) {
                    val l = log10(d); ls[i] = l
                    val k = binKey[i]
                    sumL[k] += l; sumV[k] += b.dbm; cnt[k]++
                }
            }
            if (cnt.sum() >= 3) {
                // Gemensam lutning, egen nivå per sektor
                var num = 0.0; var den = 0.0
                for ((i, b) in bins.withIndex()) {
                    if (b.dbm == null) continue
                    val k = binKey[i]
                    val lm = sumL[k] / cnt[k]; val vm = sumV[k] / cnt[k]
                    num += (ls[i] - lm) * (b.dbm - vm); den += (ls[i] - lm) * (ls[i] - lm)
                }
                val slope = (if (den > 1e-9) num / den else -30.0).coerceIn(-45.0, -20.0)
                for ((i, b) in bins.withIndex()) {
                    if (b.dbm == null) continue
                    val k = binKey[i]
                    val a = sumV[k] / cnt[k] - slope * (sumL[k] / cnt[k])
                    val e = (b.dbm - a - slope * ls[i]) / SIG_DB
                    c += robust(e * e, ROBUST_DB)
                }
            }
            return c
        }

        // Grovt rutnät
        val side = 2 * n + 1
        val gx = DoubleArray(side * side)
        val gy = DoubleArray(side * side)
        val gc = DoubleArray(side * side)
        var bestC = Double.MAX_VALUE; var bx = cx; var by = cy
        var g = 0
        for (i in -n..n) for (j in -n..n) {
            val px = cx + i * step; val py = cy + j * step
            val c = cost(px, py)
            gx[g] = px; gy[g] = py; gc[g] = c; g++
            if (c < bestC) { bestC = c; bx = px; by = py }
        }
        // Förfina runt den bästa
        fun refine(x0: Double, y0: Double, reach: Int, f: Double): Triple<Double, Double, Double> {
            var b = Triple(cost(x0, y0), x0, y0)
            for (i in -reach..reach) for (j in -reach..reach) {
                val px = x0 + i * f; val py = y0 + j * f
                val c = cost(px, py)
                if (c < b.first) b = Triple(c, px, py)
            }
            return b
        }
        refine(bx, by, 10, step / 5).let { (c, x, y) -> if (c < bestC) { bestC = c; bx = x; by = y } }

        // Hur mycket nästan lika bra platser får avvika. Mätningar från samma ställe hänger ihop,
        // så tröskeln växer med antalet mätningar per ställe. Utan avstånd är signalen ensam
        // mycket osäkrare än den ser ut.
        val terms = bins.count { it.dbm != null } + taBins
        val dof = max(1, terms - 3)
        val scale = max(1.0, bestC / dof)
        val perPlace = terms.toDouble() / max(1, places)
        val threshold = 4.6 * scale * max(1.0, perPlace) * (if (taBins >= 3) 1.0 else 3.0)

        // Spegelplatsen: ligger mätningarna längs en linje kan masten stå på andra sidan
        val meanX = bins.sumOf { it.x } / bins.size
        val meanY = bins.sumOf { it.y } / bins.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (b in bins) { val dx = b.x - meanX; val dy = b.y - meanY; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        val nx = -sin(theta); val ny = cos(theta)
        val off = (bx - meanX) * nx + (by - meanY) * ny
        var alt: Pair<Double, Double>? = null
        if (abs(2 * off) > 4 * step) {
            val (mc, ax, ay) = refine(bx - 2 * off * nx, by - 2 * off * ny, 3, step)
                .let { (c, x, y) -> refine(x, y, 4, step / 4) }
            if (mc <= bestC + threshold && hypot(ax - bx, ay - by) > 4 * step) alt = ax to ay
        }

        // Osäkerhet: avståndet som täcker nio av tio nästan lika bra platser (på den här sidan)
        val near = ArrayList<Double>()
        for (k in 0 until g) {
            if (gc[k] > bestC + threshold) continue
            val db = hypot(gx[k] - bx, gy[k] - by)
            if (alt != null && hypot(gx[k] - alt.first, gy[k] - alt.second) < db) continue
            near.add(db)
        }
        near.sort()
        val radius = max(step, if (near.isEmpty()) step else near[min(near.size - 1, (near.size * 0.9).toInt())])

        // Hur väl mätningarna omger masten: åtta väderstreck
        val octs = BooleanArray(8)
        for (b in bins) {
            val brg = (Math.toDegrees(atan2(b.x - bx, b.y - by)) + 360) % 360
            octs[((brg + 22.5) / 45).toInt() % 8] = true
        }
        val covered = octs.count { it }

        val quality = when {
            places < 3 -> Quality.INSUFFICIENT
            alt != null || taBins < 3 -> Quality.ROUGH
            radius < 100 -> Quality.SECURE
            radius < 300 -> Quality.GOOD
            else -> Quality.ROUGH
        }

        // Tips: två möjliga sidor → mät på den troligaste sidan av linjen.
        // Annars mitten av den största luckan bland väderstrecken.
        val advice = if (alt != null) {
            val brg = (Math.toDegrees(atan2(bx - meanX, by - meanY)) + 360) % 360
            ((brg + 22.5) / 45).toInt() % 8
        } else if (covered >= 6) null else {
            var bestStart = -1; var bestLen = 0
            for (s in 0 until 8) {
                if (octs[s]) continue
                var len = 0
                while (len < 8 && !octs[(s + len) % 8]) len++
                if (len > bestLen) { bestLen = len; bestStart = s }
            }
            if (bestStart < 0) null else (bestStart + bestLen / 2) % 8
        }

        // Sektorernas riktning: åt det håll sektorn hörs starkast
        val sectors = keys.mapNotNull { k ->
            var wx = 0.0; var wy = 0.0; var w = 0.0
            for (b in bins) if (b.key == k) {
                val ww = Math.pow(10.0, ((b.dbm ?: -110.0) + 130.0) / 20.0)
                wx += (b.x - bx) * ww; wy += (b.y - by) * ww; w += ww
            }
            if (w <= 0 || hypot(wx, wy) < 1e-6) null
            else Sector(k, sectorOf(k), (Math.toDegrees(atan2(wx / w, wy / w)) + 360) % 360)
        }.sortedBy { it.sector ?: 0 }

        // Avståndsringar: upp till tolv, utspridda bland mätpunkterna med avstånd
        val ringBins = bins.filter { it.ta != null }
        val rings = (if (ringBins.size <= 12) ringBins else ringBins.indices.step(ringBins.size / 12).map { ringBins[it] }.take(12))
            .map { b -> val (la, lo) = toLatLon(b.x, b.y); Ring(la, lo, b.ta!!) }

        val (lat, lon) = toLatLon(bx, by)
        val altPos = alt?.let { toLatLon(it.first, it.second) }
        val ocid = if (known.isEmpty()) null
        else Geo.km(lat, lon, known.map { it.lat }.average(), known.map { it.lon }.average()) * 1000

        return Result(
            siteKey, radio, operator, site, lat, lon, radius, quality, places, obs.size, taBins,
            sectors, advice, rings, ocid, System.currentTimeMillis(), altPos?.first, altPos?.second,
        )
    }

    /** Sektorns nummer för 4G och 5G. 3G och 2G har inga sektornummer i cell-ID. */
    private fun sectorOf(key: String): Long? = when (key.substringBefore(':')) {
        "LTE", "NR" -> Operators.sector(key)
        else -> null
    }

    fun compass(ctx: android.content.Context, deg: Double): String =
        ctx.resources.getStringArray(R.array.compass)[((((deg % 360) + 360) % 360 + 22.5) / 45).toInt() % 8]

    fun round(m: Double): Int = if (m < 100) ((m / 5).roundToInt() * 5) else ((m / 10).roundToInt() * 10)
}
