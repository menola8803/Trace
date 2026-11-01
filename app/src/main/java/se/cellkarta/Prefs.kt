package se.cellkarta

import android.content.Context
import androidx.core.content.edit

/**
 * Inställningar som sparas privat i appen. Säkerhetskopiering är avstängd i manifestet,
 * så OpenCellID-nyckeln lämnar aldrig telefonen.
 */
object Prefs {

    private const val FILE = "cellkarta"
    private const val KEY_API = "opencellid_key"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String? = sp(ctx).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(ctx: Context, key: String?) = sp(ctx).edit {
        if (key.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, key.trim())
    }
}
