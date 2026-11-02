package se.cellkarta

import android.content.Context
import android.graphics.Color

/** Nättyp 2G, 3G, 4G eller 5G, ur cellens radiotyp. */
enum class Net(val digit: String, val color: Int) {
    G2("2", Color.rgb(232, 119, 109)),
    G3("3", Color.rgb(232, 176, 74)),
    G4("4", Color.rgb(95, 196, 180)),
    G5("5", Color.rgb(127, 119, 221));

    companion object {
        fun of(key: String): Net = when (key.substringBefore(':')) {
            "GSM" -> G2
            "UMTS" -> G3
            "NR" -> G5
            else -> G4
        }

        fun label(ctx: Context, n: Net): String = n.digit + "G"
    }
}
