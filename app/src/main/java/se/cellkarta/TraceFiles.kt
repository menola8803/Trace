package se.cellkarta

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sparar loggar i Download/Trace, en fil per dag:
 *  trace-ÅÅÅÅ-MM-DD.csv            alla mätningar
 *  trace-ÅÅÅÅ-MM-DD-handelser.csv  alla avvikelser och nedgraderingar
 *
 * Filerna skrivs via MediaStore, så ingen extra behörighet behövs. Android tar inte bort
 * mappen när appen avinstalleras, därför finns deleteAll() som anropas från inställningarna.
 */
object TraceFiles {

    private const val TAG = "Trace"
    private val RELATIVE = Environment.DIRECTORY_DOWNLOADS + "/Trace/"
    private const val INFO_FILE = "LAS-MIG-README.txt"

    private const val MEASUREMENT_HEADER = "time_utc,cell,radio,lat,lon,accuracy_m,dbm,connected\n"
    private const val EVENT_HEADER = "time_utc,type,cell,lat,lon,details\n"

    private val uris = HashMap<String, Uri>()

    fun day(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun measurementFile(day: String) = "trace-$day.csv"
    fun eventFile(day: String) = "trace-$day-handelser.csv"

    /** Skapar mappen genom att lägga en förklarande textfil i den. */
    fun ensureFolder(ctx: Context) {
        runCatching {
            if (find(ctx, INFO_FILE) == null) {
                val uri = create(ctx, INFO_FILE, "text/plain") ?: return
                ctx.contentResolver.openOutputStream(uri, "w")?.use {
                    it.write(ctx.getString(R.string.storage_info_file).toByteArray())
                }
            }
        }.onFailure { Log.w(TAG, "Kunde inte skapa Download/Trace", it) }
    }

    fun appendMeasurements(ctx: Context, ts: Long, lines: List<String>) =
        append(ctx, measurementFile(day(ts)), MEASUREMENT_HEADER, lines)

    fun appendEvent(ctx: Context, ts: Long, line: String) =
        append(ctx, eventFile(day(ts)), EVENT_HEADER, listOf(line))

    @Synchronized
    private fun append(ctx: Context, name: String, header: String, lines: List<String>) {
        if (lines.isEmpty()) return
        runCatching {
            var uri = uris[name] ?: find(ctx, name)
            var text = lines.joinToString("")
            if (uri == null) {
                uri = create(ctx, name, "text/csv") ?: return
                text = header + text
            }
            uris[name] = uri
            ctx.contentResolver.openOutputStream(uri, "wa")?.use { it.write(text.toByteArray()) }
        }.onFailure {
            uris.remove(name)
            Log.w(TAG, "Kunde inte skriva $name", it)
        }
    }

    private fun create(ctx: Context, name: String, mime: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
        }
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun find(ctx: Context, name: String): Uri? =
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(name, RELATIVE),
            null
        )?.use { c ->
            if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            else null
        }

    /** Tar bort alla filer som Trace har sparat i Download/Trace. Returnerar antalet. */
    @Synchronized
    fun deleteAll(ctx: Context): Int {
        uris.clear()
        val ids = ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(RELATIVE),
            null
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()

        var removed = 0
        for (id in ids) {
            val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            removed += ctx.contentResolver.delete(uri, null, null)
        }
        // Den tomma mappen tas bort om Android tillåter det.
        runCatching {
            @Suppress("DEPRECATION")
            java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Trace").delete()
        }
        return removed
    }

    fun dayFiles(day: LocalDate) = listOf(measurementFile(day.toString()), eventFile(day.toString()))
}
