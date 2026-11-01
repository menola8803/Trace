package se.cellkarta

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Lista över alla cell-ID:n som appen har sparat, med sök, filter och export. */
class CellListActivity : ComponentActivity() {

    private enum class Filter(val label: String) {
        ALL("Alla"), FLAGGED("Avvikande"), MOVING("Rör sig"), STRONG("Stark"), SHORT("Kortvarig"),
        NEW("Nya"), UNKNOWN("Ej i referens")
    }

    private val db by lazy { CellDb.get(this) }
    private val sv: Locale = Locale.forLanguageTag("sv-SE")
    private val timeFmt = DateTimeFormatter.ofPattern("d MMM HH:mm", sv).withZone(ZoneId.systemDefault())

    private var all: List<CellEntry> = emptyList()
    private var shown: List<CellEntry> = emptyList()
    private var filter = Filter.ALL
    private var query = ""

    private lateinit var summary: TextView
    private lateinit var btnFilter: Button
    private lateinit var empty: TextView
    private val adapter = CellAdapter()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(::export) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cells)

        summary = findViewById(R.id.summary)
        btnFilter = findViewById(R.id.btnFilter)
        empty = findViewById(R.id.empty)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, pos, _ -> showDetails(shown[pos]) }

        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            applyFilter()
        }
        btnFilter.setOnClickListener {
            filter = Filter.entries[(filter.ordinal + 1) % Filter.entries.size]
            applyFilter()
        }
        findViewById<Button>(R.id.btnExportCells).setOnClickListener {
            exportLauncher.launch("trace-celler.csv")
        }

        val root = findViewById<View>(R.id.cellsRoot)
        val top = root.paddingTop
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = top + bars.top, bottom = bottom + bars.bottom, left = bars.left, right = bars.right)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { db.allSeen() }
            applyFilter()
        }
    }

    private fun applyFilter() {
        shown = all.filter { e ->
            val match = when (filter) {
                Filter.ALL -> true
                Filter.FLAGGED -> e.anomalous
                Filter.STRONG -> e.strong
                Filter.SHORT -> e.shortLived
                Filter.NEW -> e.isNew
                Filter.MOVING -> e.moving
                Filter.UNKNOWN -> !e.known
            }
            match && (query.isEmpty() || e.key.contains(query, ignoreCase = true))
        }
        btnFilter.text = "Visa: ${filter.label}"
        summary.text = String.format(
            sv, "%,d sparade celler · %,d avvikande · visar %,d",
            all.size, all.count { it.anomalous }, shown.size
        )
        empty.text = if (all.isEmpty())
            "Inga celler sparade än. Starta övervakningen på kartan, så sparas varje cell-ID som telefonen hör."
        else "Inga celler matchar sökningen."
        adapter.notifyDataSetChanged()
    }

    private fun fmt(ts: Long) = timeFmt.format(Instant.ofEpochMilli(ts))

    private fun showDetails(e: CellEntry) {
        val parts = e.key.split(':')
        val text = buildString {
            appendLine("Status: ${if (e.reasons.isEmpty()) e.statusLabel else e.reasons.joinToString(", ")}")
            if (parts.size == 5) {
                appendLine("Nät: ${parts[0]}")
                appendLine("Land och operatör: ${parts[1]}-${parts[2].padStart(2, '0')}")
                appendLine("Område (LAC/TAC): ${parts[3]}")
                appendLine("Cell-ID: ${parts[4]}")
            }
            appendLine("Först sedd: ${fmt(e.firstTs)}")
            appendLine("Senast sedd: ${fmt(e.lastTs)}")
            appendLine("Mätningar: ${e.count}")
            appendLine("Starkaste signal: ${e.bestDbm?.let { "$it dBm" } ?: "okänd"}")
            appendLine(String.format(Locale.ROOT, "Uppskattad position: %.5f, %.5f", e.lat, e.lon))
            if (!e.known) appendLine("\nCellen finns inte i den importerade OpenCellID-datan.")
        }

        AlertDialog.Builder(this)
            .setTitle(e.key)
            .setMessage(text.trim())
            .setPositiveButton("Visa på kartan") { _, _ ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_LAT, e.lat)
                        .putExtra(MainActivity.EXTRA_LON, e.lon)
                )
            }
            .setNeutralButton("Kopiera ID") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Cell-ID", e.key))
                Toast.makeText(this, "Cell-ID kopierat", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Stäng", null)
            .show()
    }

    private fun export(uri: Uri) {
        val rows = shown
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                        w.write("cell,radio,status,forst_sedd_utc,senast_sedd_utc,matningar,starkast_dbm,lat,lon\n")
                        for (e in rows) {
                            w.write(
                                "${e.key},${e.radio},${e.statusLabel},${Instant.ofEpochMilli(e.firstTs)}," +
                                    "${Instant.ofEpochMilli(e.lastTs)},${e.count},${e.bestDbm ?: ""}," +
                                    String.format(Locale.ROOT, "%.6f,%.6f\n", e.lat, e.lon)
                            )
                        }
                    }
                }
                Toast.makeText(this@CellListActivity, "Listan är sparad (${rows.size} celler).", Toast.LENGTH_LONG).show()
            } catch (ex: Exception) {
                Toast.makeText(this@CellListActivity, "Exporten misslyckades: ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class CellAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_cell, parent, false)
            val e = shown[position]
            v.findViewById<TextView>(R.id.cellKey).text = e.key
            v.findViewById<TextView>(R.id.cellMeta).text =
                "${e.count} mätningar · starkast ${e.bestDbm ?: "?"} dBm · senast ${fmt(e.lastTs)}"

            val (fg, bg) = when {
                e.moving -> Color.rgb(121, 31, 31) to Color.rgb(252, 235, 235)
                e.strong -> Color.rgb(114, 36, 62) to Color.rgb(251, 234, 240)
                e.shortLived -> Color.rgb(8, 80, 65) to Color.rgb(225, 245, 238)
                e.isNew -> Color.rgb(99, 56, 6) to Color.rgb(250, 238, 218)
                e.downgraded -> Color.rgb(60, 52, 137) to Color.rgb(238, 237, 254)
                e.known -> Color.rgb(68, 68, 65) to Color.rgb(241, 239, 232)
                else -> Color.rgb(12, 68, 124) to Color.rgb(230, 241, 251)
            }
            v.findViewById<TextView>(R.id.cellStatus).apply {
                text = e.statusLabel
                setTextColor(fg)
                background = GradientDrawable().apply {
                    setColor(bg)
                    cornerRadius = 6f * resources.displayMetrics.density
                }
            }
            return v
        }
    }
}
