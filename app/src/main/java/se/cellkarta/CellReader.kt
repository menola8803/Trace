package se.cellkarta

import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import java.util.Locale

/**
 * En cell som telefonen hör just nu.
 * Nyckeln har samma format som OpenCellID (radio:mcc:mnc:område:cell),
 * så att den kan jämföras direkt mot referensdatan.
 */
data class CellObs(
    val radio: String,
    val mcc: Int,
    val mnc: Int,
    val area: Int,
    val cid: Long,
    val dbm: Int?,
    val registered: Boolean,
) {
    val key: String get() = "$radio:$mcc:$mnc:$area:$cid"

    fun label(): String =
        String.format(Locale.ROOT, "%s %d-%02d · område %d · cell %d", radio, mcc, mnc, area, cid)

    fun describe(): String = label() + (dbm?.let { " · $it dBm" } ?: "")
}

object CellReader {

    fun parse(list: List<CellInfo>): List<CellObs> = list.mapNotNull(::parseOne)

    private fun parseOne(info: CellInfo): CellObs? = when (info) {
        is CellInfoLte -> {
            val id = info.cellIdentity
            build("LTE", id.mccString, id.mncString, id.tac, id.ci.toLong(),
                info.cellSignalStrength.dbm, info.isRegistered)
        }
        is CellInfoNr -> {
            val id = info.cellIdentity as CellIdentityNr
            val nci = id.nci
            if (nci == CellInfo.UNAVAILABLE_LONG) null
            else build("NR", id.mccString, id.mncString, id.tac, nci,
                (info.cellSignalStrength as CellSignalStrengthNr).dbm, info.isRegistered)
        }
        is CellInfoWcdma -> {
            val id = info.cellIdentity
            build("UMTS", id.mccString, id.mncString, id.lac, id.cid.toLong(),
                info.cellSignalStrength.dbm, info.isRegistered)
        }
        is CellInfoGsm -> {
            val id = info.cellIdentity
            build("GSM", id.mccString, id.mncString, id.lac, id.cid.toLong(),
                info.cellSignalStrength.dbm, info.isRegistered)
        }
        else -> null
    }

    /** Grannceller saknar ofta fullständigt ID. De hoppas över eftersom de inte går att jämföra. */
    private fun build(
        radio: String, mccS: String?, mncS: String?, area: Int, cid: Long,
        dbm: Int, registered: Boolean,
    ): CellObs? {
        val mcc = mccS?.toIntOrNull() ?: return null
        val mnc = mncS?.toIntOrNull() ?: return null
        if (area == CellInfo.UNAVAILABLE || area < 0) return null
        if (cid < 0 || cid == CellInfo.UNAVAILABLE.toLong()) return null
        val signal = if (dbm == CellInfo.UNAVAILABLE || dbm >= 0) null else dbm
        return CellObs(radio, mcc, mnc, area, cid, signal, registered)
    }
}
