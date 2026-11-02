package se.cellkarta

import android.content.Context
import java.util.Locale
import androidx.core.content.edit

/**
 * Inställningar som sparas privat i appen. Säkerhetskopiering är avstängd i manifestet,
 * så OpenCellID-nyckeln lämnar aldrig telefonen.
 */
object Prefs {

    private const val FILE = "cellkarta"
    private const val KEY_API = "opencellid_key"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_STORAGE_INFO = "storage_info_shown"
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

    /** Svenska om telefonen är på svenska, annars engelska, tills användaren väljer själv. */
    fun language(ctx: Context): String =
        sp(ctx).getString(KEY_LANGUAGE, null)
            ?: if (Locale.getDefault().language == "sv") Lang.SV else Lang.EN

    fun setLanguage(ctx: Context, tag: String) = sp(ctx).edit { putString(KEY_LANGUAGE, tag) }

    fun storageInfoShown(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_STORAGE_INFO, false)

    fun setStorageInfoShown(ctx: Context) = sp(ctx).edit { putBoolean(KEY_STORAGE_INFO, true) }
}
