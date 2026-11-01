package se.cellkarta

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Hämtar och läser in OpenCellID:s celldata.
 * Kolumner: radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal
 */
object OpenCellIdImporter {

    /** Sveriges landskod i mobilnätet. */
    const val MCC_SWEDEN = "240"

    private const val BATCH = 20_000L
    private val SV: Locale = Locale.forLanguageTag("sv-SE")

    /** Läser in en fil som du själv har laddat ner (240.csv.gz eller uppackad 240.csv). */
    suspend fun importFile(ctx: Context, uri: Uri, progress: suspend (String) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val raw = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Kunde inte öppna filen")
            raw.use { importStream(ctx, it, progress) }
        }

    /** Laddar ner hela landets celler med din API-nyckel och läser in dem. */
    suspend fun downloadAndImport(
        ctx: Context, apiKey: String, mcc: String = MCC_SWEDEN, progress: suspend (String) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "$mcc.csv.gz")
        try {
            download(apiKey, mcc, file, progress)
            file.inputStream().use { importStream(ctx, it, progress) }
        } finally {
            file.delete()
        }
    }

    private suspend fun download(apiKey: String, mcc: String, target: File, progress: suspend (String) -> Unit) {
        val token = URLEncoder.encode(apiKey, "UTF-8")
        // OpenCellID har haft två adresser för nedladdningar. Den andra prövas om den första inte svarar med data.
        val urls = listOf(
            "https://opencellid.org/ocid/downloads?token=$token&type=mcc&file=$mcc.csv.gz",
            "https://opencellid.org/downloads.php?token=$token&type=mcc&file=$mcc.csv.gz",
        )
        var lastError: String? = null

        for (address in urls) {
            val conn = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Cellkarta/0.1 (Android)")
            }
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    lastError = "HTTP $code"
                    continue
                }
                val input = BufferedInputStream(conn.inputStream, 1 shl 16)
                input.mark(4)
                val isGzip = input.read() == 0x1f && input.read() == 0x8b
                input.reset()
                if (!isGzip) {
                    // OpenCellID svarar med en kort text vid fel, till exempel ogiltig nyckel.
                    lastError = input.bufferedReader().readText().take(300).trim()
                    continue
                }

                progress("Laddar ner celler för Sverige…")
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total - reported >= 2_000_000) {
                            reported = total
                            progress(String.format(SV, "Laddar ner… %.0f MB", total / 1_000_000.0))
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IOException(explain(lastError))
    }

    private fun explain(msg: String?): String {
        val m = msg.orEmpty()
        return when {
            m.contains("RATE", ignoreCase = true) || m.contains("limit", ignoreCase = true) ->
                "OpenCellID tillåter bara ett fåtal nedladdningar per dag. Försök igen i morgon."
            m.contains("token", ignoreCase = true) || m.contains("key", ignoreCase = true) ||
                m.contains("401") || m.contains("403") ->
                "OpenCellID godkände inte nyckeln. Kontrollera den under Mer → OpenCellID-nyckel."
            m.isBlank() -> "OpenCellID svarade inte. Kontrollera internetanslutningen."
            else -> "OpenCellID svarade: $m"
        }
    }

    private suspend fun importStream(ctx: Context, raw: InputStream, progress: suspend (String) -> Unit): Long {
        val db = CellDb.get(ctx).writableDatabase
        var count = 0L

        val buffered = BufferedInputStream(raw, 1 shl 16)
        buffered.mark(4)
        val isGzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val reader = (if (isGzip) GZIPInputStream(buffered, 1 shl 16) else buffered).bufferedReader()

        progress("Läser in referensdata…")
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO known_cells(cell_key, lat, lon, range_m) VALUES(?,?,?,?)"
        )
        db.beginTransaction()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val f = line.split(',')
                if (f.size < 9 || f[0] == "radio") continue
                val mcc = f[1].toIntOrNull() ?: continue
                val mnc = f[2].toIntOrNull() ?: continue
                val area = f[3].toLongOrNull() ?: continue
                val cell = f[4].toLongOrNull() ?: continue
                val lon = f[6].toDoubleOrNull() ?: continue
                val lat = f[7].toDoubleOrNull() ?: continue

                stmt.clearBindings()
                stmt.bindString(1, "${f[0]}:$mcc:$mnc:$area:$cell")
                stmt.bindDouble(2, lat)
                stmt.bindDouble(3, lon)
                stmt.bindLong(4, f[8].toLongOrNull() ?: 0L)
                stmt.executeInsert()
                count++

                if (count % BATCH == 0L) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    progress(String.format(SV, "Läser in referensdata… %,d celler", count))
                    db.beginTransaction()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            stmt.close()
        }
        return count
    }
}
