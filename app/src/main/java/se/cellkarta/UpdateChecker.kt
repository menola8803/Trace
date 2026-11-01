package se.cellkarta

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Söker efter ny version bland releaserna i GitHub-repot.
 * Varje bygge på GitHub publiceras under taggen "senaste" med raderna "Version: …" och "Build: …".
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/menola8803/Trace"
    private const val API = "https://api.github.com/repos/menola8803/Trace/releases/tags/senaste"

    data class Release(
        val build: Long,
        val versionName: String,
        val apkUrl: String,
        val pageUrl: String,
        val publishedAt: String,
    )

    fun currentBuild(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "okänd"

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Cellkarta")
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> throw IOException("Ingen version är publicerad än.")
                else -> throw IOException("GitHub svarade med kod $code. Försök igen senare.")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val body = json.optString("body")
            val build = Regex("""Build:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLong()
                ?: throw IOException("Kunde inte läsa versionsnumret från GitHub.")
            val name = Regex("""Version:\s*([\w.\-]+)""").find(body)?.groupValues?.get(1) ?: "build $build"

            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            val page = json.optString("html_url", "$REPO_URL/releases")
            Release(build, name, apk ?: page, page, json.optString("published_at"))
        } finally {
            conn.disconnect()
        }
    }

    /** Frågar om användaren vill hämta den nya versionen. Nedladdningen sker i webbläsaren. */
    fun offer(ctx: Context, release: Release) {
        AlertDialog.Builder(ctx)
            .setTitle("Ny version finns")
            .setMessage(
                "Version ${release.versionName} finns att hämta. Du har ${currentVersionName(ctx)}.\n\n" +
                    "Filen laddas ner i webbläsaren. Öppna den när den är klar och tryck Installera. " +
                    "Dina sparade celler och inställningar finns kvar."
            )
            .setPositiveButton("Ladda ner") { _, _ -> open(ctx, release.apkUrl) }
            .setNegativeButton("Senare", null)
            .show()
    }

    fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Ingen webbläsare hittades. Gå till $url", Toast.LENGTH_LONG).show()
        }
    }
}
