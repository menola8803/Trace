package se.cellkarta

import android.content.Context
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Mastinformation för en cell: operatör, mast och sektor i klartext, och en uppskattad
 * mastposition från OpenCellID, räknad som medelvärdet av alla kända sektorer på samma mast.
 */
data class MastInfo(
    val key: String,
    val radio: String,
    val mcc: Int,
    val mnc: Int,
    val area: String,
    val site: Long?,
    val sector: Long?,
    val operator: String,
    val mastLat: Double?,
    val mastLon: Double?,
    val knownSectors: List<Long>,
) {
    /** Länk till CellMapper, centrerad på masten eller på platsen där cellen hördes. */
    fun cellMapperUrl(fallbackLat: Double, fallbackLon: Double): String {
        val type = when (radio) { "NR" -> "NR"; "UMTS" -> "UMTS"; "GSM" -> "GSM"; else -> "LTE" }
        return String.format(
            Locale.ROOT,
            "https://www.cellmapper.net/map?MCC=%d&MNC=%d&type=%s&latitude=%.6f&longitude=%.6f&zoom=16&showTowers=true",
            mcc, mnc, type, mastLat ?: fallbackLat, mastLon ?: fallbackLon
        )
    }

    /** Rader till förklaringsrutan och listan. */
    fun rows(ctx: Context, heardLat: Double, heardLon: Double): List<String> = buildList {
        add(Texts.row(ctx.getString(R.string.row_operator_name), "$operator ($mcc-${"%02d".format(mnc)})"))
        add(Texts.row(ctx.getString(R.string.row_network), Net.label(ctx, Net.of(key)) + " · " + radio))
        add(Texts.row(ctx.getString(if (radio == "LTE" || radio == "NR") R.string.row_tac else R.string.row_lac), area))
        if (site != null) {
            add(Texts.row(ctx.getString(R.string.row_mast_id), "$site"))
            add(Texts.row(ctx.getString(R.string.row_sector), "$sector"))
        }
        if (mastLat != null && mastLon != null) {
            val m = (Geo.km(heardLat, heardLon, mastLat, mastLon) * 1000).roundToInt()
            add(Texts.row(ctx.getString(R.string.row_mast_pos),
                ctx.tr(R.string.mast_pos_value, m, direction(ctx, heardLat, heardLon, mastLat, mastLon), knownSectors.size)))
            if (knownSectors.isNotEmpty()) {
                add(Texts.row(ctx.getString(R.string.row_sectors_known), knownSectors.sorted().joinToString(", ")))
            }
        } else {
            add(Texts.row(ctx.getString(R.string.row_mast_pos), ctx.getString(R.string.mast_pos_unknown)))
        }
    }

    companion object {
        fun of(db: CellDb, key: String): MastInfo? {
            val p = key.split(':')
            if (p.size != 5) return null
            val mcc = p[1].toIntOrNull() ?: return null
            val mnc = p[2].toIntOrNull() ?: return null
            val siteId = Operators.mastId(key)?.second
            val siblings = if (siteId != null) MastLookup.siteCells(db, p[0], mcc, mnc, siteId) else emptyList()
            return MastInfo(
                key = key, radio = p[0], mcc = mcc, mnc = mnc, area = p[3],
                site = siteId, sector = Operators.sector(key),
                operator = Operators.name(mcc, mnc),
                mastLat = siblings.takeIf { it.isNotEmpty() }?.map { it.lat }?.average(),
                mastLon = siblings.takeIf { it.isNotEmpty() }?.map { it.lon }?.average(),
                knownSectors = siblings.mapNotNull { Operators.sector(it.key) }.distinct(),
            )
        }

        private fun direction(ctx: Context, lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
            val y = sin(Math.toRadians(lon2 - lon1)) * cos(Math.toRadians(lat2))
            val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lon2 - lon1))
            val deg = (Math.toDegrees(atan2(y, x)) + 360) % 360
            val names = ctx.resources.getStringArray(R.array.compass)
            return names[((deg + 22.5) / 45).toInt() % 8]
        }
    }
}

/**
 * Kända celler från OpenCellID grupperade per mast. Byggs en gång per operatör och nät,
 * och byggs om när referensdatan ändras.
 */
object MastLookup {
    private val cache = HashMap<String, Map<Long, List<KnownCell>>>()
    private var builtForCount = -1L

    @Synchronized
    fun siteCells(db: CellDb, radio: String, mcc: Int, mnc: Int, site: Long): List<KnownCell> {
        val count = db.knownCount()
        if (count != builtForCount) {
            cache.clear()
            builtForCount = count
        }
        val prefix = "$radio:$mcc:$mnc:"
        val bySite = cache.getOrPut(prefix) {
            db.knownWithPrefix(prefix).groupBy { Operators.mastId(it.key)?.second ?: -1L }
        }
        return bySite[site].orEmpty()
    }
}
