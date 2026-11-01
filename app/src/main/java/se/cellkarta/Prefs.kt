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
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String? = sp(ctx).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(ctx: Context, key: String?) = sp(ctx).edit {
        if (key.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, key.trim())
    }

    fun autoUpdate(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_UPDATE, true)

    fun setAutoUpdate(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean(KEY_AUTO_UPDATE, on) }

    fun lastUpdateCheck(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(ctx: Context, ts: Long) = sp(ctx).edit { putLong(KEY_LAST_UPDATE_CHECK, ts) }
}
