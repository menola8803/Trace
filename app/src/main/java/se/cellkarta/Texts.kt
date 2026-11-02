package se.cellkarta

import android.content.Context

/**
 * Formulerar förklaringar till avvikelser på valt språk. Avvikelserna sparas som rena värden
 * (till exempel "dbm=-52;jump=24;median=-76"), så att texten kan visas på svenska eller engelska.
 */
object Texts {

    /** Gör om "LTE:240:7:34306:87378227" till "LTE 240-07 · TAC 34306 · CI 87378227". */
    fun cellLabel(key: String): String {
        val p = key.split(':')
        if (p.size != 5) return key
        val area = if (p[0] == "LTE" || p[0] == "NR") "TAC" else "LAC"
        val cell = if (p[0] == "NR") "NCI" else "CI"
        return "${p[0]} ${p[1]}-${p[2].padStart(2, '0')} · $area ${p[3]} · $cell ${p[4]}"
    }

    fun reasonName(ctx: Context, type: String): String = ctx.getString(
        when (type) {
            CellDb.FLAG_MOVING -> R.string.reason_moving
            CellDb.FLAG_STRONG -> R.string.reason_strong
            CellDb.FLAG_SHORT -> R.string.reason_short
            CellDb.FLAG_DOWNGRADE -> R.string.reason_downgrade
            else -> R.string.reason_new
        }
    )

    fun explanation(ctx: Context, type: String): String = ctx.getString(
        when (type) {
            CellDb.FLAG_MOVING -> R.string.expl_moving
            CellDb.FLAG_STRONG -> R.string.expl_strong
            CellDb.FLAG_SHORT -> R.string.expl_short
            CellDb.FLAG_DOWNGRADE -> R.string.expl_downgrade
            else -> R.string.expl_new
        }
    )

    fun eventTitle(ctx: Context, type: String): String = ctx.getString(
        when (type) {
            CellDb.FLAG_MOVING -> R.string.event_moving
            CellDb.FLAG_STRONG -> R.string.event_strong
            CellDb.FLAG_SHORT -> R.string.event_short
            CellDb.FLAG_DOWNGRADE -> R.string.event_downgrade
            else -> R.string.event_new
        }
    )

    fun alertTitle(ctx: Context, type: String, detail: String): String = when (type) {
        CellDb.FLAG_MOVING -> ctx.getString(R.string.alert_moving)
        CellDb.FLAG_STRONG -> ctx.getString(R.string.alert_strong)
        CellDb.FLAG_SHORT -> ctx.getString(R.string.alert_short)
        CellDb.FLAG_DOWNGRADE -> ctx.tr(R.string.alert_downgrade, parse(detail)["to"] ?: "")
        else -> ctx.getString(R.string.alert_new)
    }

    fun parse(detail: String?): Map<String, String> =
        detail.orEmpty().split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()

    /** Signalstyrkan i en avvikelse, om den finns. */
    fun dbm(detail: String?): Int? = parse(detail)["dbm"]?.toIntOrNull()

    /** Kort beskrivning av värdena, t.ex. "24 dB starkare än vanligt (-76 dBm)". */
    fun detailText(ctx: Context, type: String, detail: String?): String {
        val d = parse(detail)
        if (d.isEmpty()) return detail.orEmpty() // äldre avvikelser sparades som färdig svensk text
        fun i(k: String) = d[k]?.toDoubleOrNull()?.toInt()
        return when (type) {
            CellDb.FLAG_STRONG -> {
                val jump = i("jump")
                val median = i("median")
                if (jump != null && median != null) ctx.tr(R.string.det_jump, jump, median)
                else ctx.getString(R.string.det_very_strong)
            }
            CellDb.FLAG_MOVING -> i("far")?.let { ctx.tr(R.string.det_far, it) }
                ?: i("ref")?.let { ctx.tr(R.string.det_from_ref, it) } ?: ""
            CellDb.FLAG_SHORT -> i("min")?.let { ctx.tr(R.string.det_minutes, it) } ?: ""
            CellDb.FLAG_DOWNGRADE -> ctx.tr(R.string.det_from_to, d["from"] ?: "?", d["to"] ?: "?")
            else -> i("dbm")?.let { ctx.tr(R.string.det_dbm, it) } ?: ""
        }
    }

    fun row(label: String, value: String) = "<b>$label:</b> $value"
}
