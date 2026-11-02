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

    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(Prefs.language(base))
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    fun locale(ctx: Context): Locale = ctx.resources.configuration.locales[0]
}

/** Hämtar en text och fyller i värden med appens valda språk (t.ex. tusentalsavgränsare). */
fun Context.tr(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) getString(id) else String.format(Lang.locale(this), getString(id), *args)

/** Alla skärmar ärver härifrån så att det valda språket gäller överallt. */
open class BaseActivity : ComponentActivity() {
    private var languageAtCreate: String? = null

    override fun attachBaseContext(newBase: Context) {
        languageAtCreate = Prefs.language(newBase)
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        // Språket byttes på en annan skärm: bygg om den här.
        if (languageAtCreate != null && languageAtCreate != Prefs.language(this)) recreate()
    }
}
