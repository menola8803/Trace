package se.cellkarta

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Triangulering: räknar ut var en mast står från dina mätningar av dess sektorer.
 *
 *  - Timing Advance ger avståndet från varje mätpunkt till antennen (cirka 78 m per steg).
 *  - Signalstyrkan avtar med avståndet. Lutningen är gemensam för masten men varje sektor
 *    får en egen nivå, eftersom sektorerna pekar åt olika håll.
 *  - Appen provar ett rutnät av tänkbara mastplatser, väljer den som bäst förklarar alla
 *    mätningar och förfinar runt den. Alla platser som nästan lika bra förklarar mätningarna
 *    bildar osäkerhetscirkeln.
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
    )

    private const val BIN_M = 15.0
    private const val PLACE_M = 30.0
    private const val SIG_TA = 80.0
    private const val SIG_DB = 7.0
    private const val MAX_BINS = 400

    private class Bin(val x: Double, val y: Double, val key: String, val dbm: Double?, val ta: Double?)

    /** Nyckel för en mast: nät, land, operatör och mastnummer, till exempel "LTE:240:7:331321". */
    fun siteKeyOf(cellKey: String): String? {
        val p = cellKey.split(':')
        val site = Operators.mastId(cellKey)?.second ?: return null
        return "${p[0]}:${p[1]}:${p[2]}:$site"
    }

    /** Beräknar masten som cellen hör till. Null om det saknas mätningar. */
    fun forCell(db: CellDb, cellKey: String): Result? = siteKeyOf(cellKey)?.let { forSite(db, it) }

    fun forSite(db: CellDb, siteKey: String): Result? {
        val p = siteKey.split(':')
        if (p.size != 4) return null
        val prefix = "${p[0]}:${p[1]}:${p[2]}:"
        val site = p[3].toLong()
        val obs = db.triObservations(prefix, CellMonitorService.MAX_ACCURACY_M.toDouble()).filter { Operators.mastId(it.key)?.second == site }
        if (obs.isEmpty()) return null
        val known = MastLookup.siteCells(db, p[0], p[1].toInt(), p[2].toInt(), site)
        return solve(siteKey, p[0], Operators.name(p[1].toInt(), p[2].toInt()), site, obs, known)
    }

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
                       val dbm: MutableList<Int> = ArrayList(), val ta: MutableList<Double> = ArrayList())
        val acc = HashMap<Triple<Long, Long, String>, Acc>()
        for (o in obs) {
            val x = (o.lon - lon0) * mx
            val y = (o.lat - lat0) * my
            val a = acc.getOrPut(Triple(Math.round(x / BIN_M), Math.round(y / BIN_M), o.key)) { Acc() }
            a.sx += x; a.sy += y; a.n++
            o.dbm?.let { a.dbm.add(it) }
            o.taM?.let { a.ta.add(it) }
        }
        var bins = acc.entries.map { (k, a) ->
            val ta = a.ta.sorted()
            Bin(a.sx / a.n, a.sy / a.n, k.third,
                if (a.dbm.isEmpty()) null else a.dbm.average(),
                if (ta.isEmpty()) null else ta[ta.size / 2])
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
        val sumL = DoubleArray(keys.size)
        val sumV = DoubleArray(keys.size)
        val cnt = IntArray(keys.size)
        val ls = DoubleArray(bins.size)

        fun cost(px: Double, py: Double): Double {
            var c = 0.0
            java.util.Arrays.fill(sumL, 0.0); java.util.Arrays.fill(sumV, 0.0); java.util.Arrays.fill(cnt, 0)
            for ((i, b) in bins.withIndex()) {
                val d = max(15.0, hypot(b.x - px, b.y - py))
                if (b.ta != null) { val e = (d - b.ta) / SIG_TA; c += e * e }
                if (b.dbm != null) {
                    val l = log10(d); ls[i] = l
                    val k = keyIx[b.key]!!
                    sumL[k] += l; sumV[k] += b.dbm; cnt[k]++
                }
            }
            val withDb = cnt.sum()
            if (withDb >= 3) {
                // Gemensam lutning, egen nivå per sektor
                var num = 0.0; var den = 0.0
                for ((i, b) in bins.withIndex()) {
                    if (b.dbm == null) continue
                    val k = keyIx[b.key]!!
                    val lm = sumL[k] / cnt[k]; val vm = sumV[k] / cnt[k]
                    num += (ls[i] - lm) * (b.dbm - vm); den += (ls[i] - lm) * (ls[i] - lm)
                }
                val slope = (if (den > 1e-9) num / den else -30.0).coerceIn(-45.0, -20.0)
                for ((i, b) in bins.withIndex()) {
                    if (b.dbm == null) continue
                    val k = keyIx[b.key]!!
                    val a = sumV[k] / cnt[k] - slope * (sumL[k] / cnt[k])
                    val e = (b.dbm - a - slope * ls[i]) / SIG_DB
                    c += e * e
                }
            }
            return c
        }

        // Grovt rutnät
        val grid = ArrayList<Triple<Double, Double, Double>>((2 * n + 1) * (2 * n + 1))
        var best = Triple(Double.MAX_VALUE, cx, cy)
        for (i in -n..n) for (j in -n..n) {
            val px = cx + i * step; val py = cy + j * step
            val c = cost(px, py)
            grid.add(Triple(c, px, py))
            if (c < best.first) best = Triple(c, px, py)
        }
        // Förfina runt den bästa
        val f = step / 5
        val (_, bx, by) = best
        for (i in -10..10) for (j in -10..10) {
            val px = bx + i * f; val py = by + j * f
            val c = cost(px, py)
            if (c < best.first) best = Triple(c, px, py)
        }

        // Osäkerhet: alla platser som förklarar mätningarna nästan lika bra
        val terms = bins.count { it.dbm != null } + taBins
        val dof = max(1, terms - 3)
        val scale = max(1.0, best.first / dof)
        val inside = grid.count { it.first <= best.first + 4.6 * scale }
        var radius = max(step, sqrt(inside * step * step / Math.PI))

        // Hur väl mätningarna omger masten: åtta väderstreck
        val octs = BooleanArray(8)
        for (b in bins) {
            val brg = (Math.toDegrees(atan2(b.x - best.second, b.y - best.third)) + 360) % 360
            octs[((brg + 22.5) / 45).toInt() % 8] = true
        }
        val covered = octs.count { it }
        // Utan avstånd och från bara ena sidan underskattas osäkerheten
        if (taBins < 3 && covered < 4) radius *= 2.5

        val quality = when {
            places < 3 -> Quality.INSUFFICIENT
            taBins < 3 && covered < 4 -> Quality.ROUGH
            radius < 100 -> Quality.SECURE
            radius < 300 -> Quality.GOOD
            else -> Quality.ROUGH
        }

        // Tips: mitten av den största luckan bland väderstrecken
        val advice = if (covered >= 6) null else {
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
                wx += (b.x - best.second) * ww; wy += (b.y - best.third) * ww; w += ww
            }
            if (w <= 0 || hypot(wx, wy) < 1e-6) null
            else Sector(k, Operators.sector(k), (Math.toDegrees(atan2(wx / w, wy / w)) + 360) % 360)
        }.sortedBy { it.sector ?: 0 }

        // Avståndsringar: upp till tolv, utspridda bland mätpunkterna med avstånd
        val ringBins = bins.filter { it.ta != null }
        val rings = (if (ringBins.size <= 12) ringBins else ringBins.indices.step(ringBins.size / 12).map { ringBins[it] }.take(12))
            .map { b -> val (la, lo) = toLatLon(b.x, b.y); Ring(la, lo, b.ta!!) }

        val (lat, lon) = toLatLon(best.second, best.third)
        val ocid = if (known.isEmpty()) null
        else Geo.km(lat, lon, known.map { it.lat }.average(), known.map { it.lon }.average()) * 1000

        return Result(
            siteKey, radio, operator, site, lat, lon, radius, quality, places, obs.size, taBins,
            sectors, advice, rings, ocid, System.currentTimeMillis(),
        )
    }

    fun compass(ctx: android.content.Context, deg: Double): String =
        ctx.resources.getStringArray(R.array.compass)[((((deg % 360) + 360) % 360 + 22.5) / 45).toInt() % 8]

    fun round(m: Double): Int = if (m < 100) ((m / 5).roundToInt() * 5) else ((m / 10).roundToInt() * 10)
}

/** Beräknade master medan appen är igång, per mast ("LTE:240:7:331321"). */
object TriStore {
    val results = LinkedHashMap<String, Triangulate.Result>()
}

