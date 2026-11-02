package se.cellkarta

/**
 * Räknar ut vilken operatör och vilken mast en markering troligen hör till.
 *  1. Egna mätningar: celler du har hört nära platsen, grupperade per mast-ID. Den mast som hörts
 *     starkast vinner. Telefonen hör bara sin egen operatörs celler, så det här visar bara om masten
 *     är din operatörs.
 *  2. OpenCellID: kända celler nära platsen, räknade per operatör. Visar även andra operatörer,
 *     eftersom flera operatörer ofta delar samma tak.
 */
data class MastGuess(
    val operator: String?,
    val siteRadio: String?,
    val siteId: Long?,
    val sectors: List<CellEntry>,
    val bestDbm: Int?,
    val knownOperators: List<Pair<String, Int>>,
) {
    companion object {
        const val RADIUS_KM = 0.3

        fun of(db: CellDb, lat: Double, lon: Double): MastGuess {
            val own = db.seenNear(lat, lon, RADIUS_KM)
            val known = db.knownNear(lat, lon, RADIUS_KM)
            val knownOps = known.mapNotNull { Operators.fromKey(it.key) }
                .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

            val bestSite = own.mapNotNull { c -> Operators.mastId(c.key)?.let { it to c } }
                .groupBy({ it.first }, { it.second })
                .maxByOrNull { (_, cells) -> cells.maxOf { it.bestDbm ?: -200 } }

            if (bestSite != null) {
                val (site, cells) = bestSite
                return MastGuess(
                    operator = Operators.fromKey(cells.first().key),
                    siteRadio = site.first.substringBefore(':'),
                    siteId = site.second,
                    sectors = cells.sortedBy { Operators.sector(it.key) ?: 0 },
                    bestDbm = cells.mapNotNull { it.bestDbm }.maxOrNull(),
                    knownOperators = knownOps,
                )
            }
            return MastGuess(
                operator = knownOps.firstOrNull()?.first,
                siteRadio = null, siteId = null, sectors = emptyList(), bestDbm = null,
                knownOperators = knownOps,
            )
        }
    }
}
