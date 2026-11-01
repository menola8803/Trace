package se.cellkarta

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {

    data class TrackPoint(val lat: Double, val lon: Double, val ts: Long, val samples: Int)

    /** Avstånd i kilometer mellan två punkter (haversine). */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }

    /**
     * Vikt för en mätning. Starkare signal betyder att telefonen troligen var närmare
     * antennen, så den mätpunkten får mer vikt i uppskattningen av antennens position.
     */
    fun weight(dbm: Int?): Double =
        if (dbm == null) 1.0 else 10.0.pow((dbm + 130).coerceIn(0, 90) / 10.0)

    /**
     * Delar upp mätningarna i tidsfönster och räknar ut en viktad mittpunkt per fönster.
     * Resultatet blir det spår som ritas som en linje på kartan.
     */
    fun track(rows: List<ObsRow>, bucketMs: Long = 15 * 60_000L): List<TrackPoint> =
        rows.groupBy { it.ts / bucketMs }
            .toSortedMap()
            .map { (_, group) ->
                var ws = 0.0
                var la = 0.0
                var lo = 0.0
                for (r in group) {
                    val w = weight(r.dbm)
                    ws += w
                    la += r.lat * w
                    lo += r.lon * w
                }
                TrackPoint(la / ws, lo / ws, group.first().ts, group.size)
            }
}
