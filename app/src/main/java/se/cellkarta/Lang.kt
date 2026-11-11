package se.cellkarta

import android.content.Context
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import java.util.Locale

/** Språkval: svenska eller engelska, oberoende av telefonens språk. */
object Lang {
    const val SV = "sv"
    const val EN = "en"

    /** Valt språk och valt tema (mörkt eller beige) gäller oberoende av telefonens inställningar. */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(Prefs.language(base))
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        val night = if (Prefs.theme(base) == Prefs.THEME_DARK) android.content.res.Configuration.UI_MODE_NIGHT_YES
        else android.content.res.Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    fun locale(ctx: Context): Locale = ctx.resources.configuration.locales[0]
}

/** Hämtar en text och fyller i värden med appens valda språk (t.ex. tusentalsavgränsare). */
fun Context.tr(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) getString(id) else String.format(Lang.locale(this), getString(id), *args)

/** Alla skärmar ärver härifrån så att det valda språket gäller överallt. */
open class BaseActivity : ComponentActivity() {
    private var lookAtCreate: String? = null

    private fun look(ctx: Context) = Prefs.language(ctx) + "/" + Prefs.theme(ctx)

    override fun attachBaseContext(newBase: Context) {
        lookAtCreate = look(newBase)
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        // Språk eller tema byttes på en annan skärm: bygg om den här.
        if (lookAtCreate != null && lookAtCreate != look(this)) recreate()
    }
}
