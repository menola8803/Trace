package se.cellkarta

/**
 * Operatörsnamn för svenska nätkoder (MCC 240), enligt PTS plan för MNC.
 * https://www.pts.se (Nummer och adressering → Tekniska planer → MNC)
 */
object Operators {
    private val SWEDEN = mapOf(
        1 to "Telia",
        2 to "Tre",
        3 to "Teracom",
        4 to "3GIS",
        5 to "Svenska UMTS-nät",
        6 to "Telenor",
        7 to "Tele2",
        8 to "Telenor",
        9 to "Com4",
        10 to "Tele2",
        12 to "Lycamobile",
        14 to "Tele2",
        21 to "Trafikverket",
        24 to "Net4Mobility (Tele2/Telenor)",
        41 to "Telenor",
        49 to "Telia",
    )

    fun name(mcc: Int, mnc: Int): String =
        (if (mcc == 240) SWEDEN[mnc] else null) ?: String.format(java.util.Locale.ROOT, "%d-%02d", mcc, mnc)

    /** Operatör ur en cellnyckel som "LTE:240:7:34306:87378227". */
    fun fromKey(key: String): String? {
        val p = key.split(':')
        if (p.size != 5) return null
        val mcc = p[1].toIntOrNull() ?: return null
        val mnc = p[2].toIntOrNull() ?: return null
        return name(mcc, mnc)
    }

    /**
     * Mast-ID ur en cellnyckel. För 4G är cell-ID:t mastens nummer (eNB) gånger 256 plus sektorn,
     * för 5G används de översta bitarna, för 3G nätstyrningens nummer (RNC).
     */
    fun mastId(key: String): Pair<String, Long>? {
        val p = key.split(':')
        if (p.size != 5) return null
        val cid = p[4].toLongOrNull() ?: return null
        val site = when (p[0]) {
            "LTE" -> cid / 256
            "NR" -> cid / 4096
            "UMTS" -> cid / 65536
            else -> return null
        }
        return "${p[0]}:${p[1]}:${p[2]}" to site
    }

    fun sector(key: String): Long? {
        val p = key.split(':')
        val cid = p.getOrNull(4)?.toLongOrNull() ?: return null
        return when (p[0]) {
            "LTE" -> cid % 256
            "NR" -> cid % 4096
            "UMTS" -> cid % 65536
            else -> null
        }
    }
}
