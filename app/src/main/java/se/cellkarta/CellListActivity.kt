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
class CellListActivity : BaseActivity() {

    private enum class Filter(val label: Int) {
        ALL(R.string.filter_all), FLAGGED(R.string.filter_abnormal), MOVING(R.string.filter_moving),
        STRONG(R.string.filter_strong), SHORT(R.string.filter_short), NEW(R.string.filter_new),
        UNKNOWN(R.string.filter_unknown), G2(R.string.filter_2g), G3(R.string.filter_3g),
        G4(R.string.filter_4g), G5(R.string.filter_5g)
    }

    private val db by lazy { CellDb.get(this) }
    private val timeFmt by lazy {
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Lang.locale(this)).withZone(ZoneId.systemDefault())
    }

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
            exportLauncher.launch("trace-cells.csv")
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
                Filter.G2 -> Net.of(e.key) == Net.G2
                Filter.G3 -> Net.of(e.key) == Net.G3
                Filter.G4 -> Net.of(e.key) == Net.G4
                Filter.G5 -> Net.of(e.key) == Net.G5
            }
            match && (query.isEmpty() || e.key.contains(query, ignoreCase = true))
        }
        btnFilter.text = tr(R.string.cells_show, getString(filter.label))
        summary.text = tr(
            R.string.cells_summary,
            all.size, all.count { it.anomalous }, shown.size
        )
        empty.text = if (all.isEmpty())
            getString(R.string.cells_empty)
        else getString(R.string.cells_no_match)
        adapter.notifyDataSetChanged()
    }

    private fun fmt(ts: Long) = timeFmt.format(Instant.ofEpochMilli(ts))

    private fun showDetails(e: CellEntry) {
        if (!Prefs.mastDetails(this)) return showDetails(e, null)
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { MastInfo.of(CellDb.get(this@CellListActivity), e.key) }
            showDetails(e, info)
        }
    }

    private fun showDetails(e: CellEntry, info: MastInfo?) {
        val parts = e.key.split(':')
        val text = buildString {
            val ctx = this@CellListActivity
            appendLine("${getString(R.string.row_status)}: " + (if (e.reasonTypes.isEmpty()) e.statusLabel(ctx) else e.reasons(ctx).joinToString(", ")))
            if (parts.size == 5) {
                appendLine("${getString(R.string.row_network)}: ${parts[0]}")
                appendLine("${getString(R.string.row_operator)}: ${parts[1]}-${parts[2].padStart(2, '0')}")
                appendLine("${getString(R.string.row_area)}: ${parts[3]}")
                appendLine("${getString(R.string.row_cell_id)}: ${parts[4]}")
            }
            appendLine("${getString(R.string.row_first)}: ${fmt(e.firstTs)}")
            appendLine("${getString(R.string.row_last)}: ${fmt(e.lastTs)}")
            appendLine("${getString(R.string.row_count)}: ${e.count}")
            appendLine("${getString(R.string.row_best)}: ${e.bestDbm?.let { "$it dBm" } ?: getString(R.string.unknown_value)}")
            appendLine(String.format(Locale.ROOT, "${getString(R.string.row_position)}: %.5f, %.5f", e.lat, e.lon))
            if (!e.known) appendLine("\n" + getString(R.string.cells_not_in_ref))
            if (info != null) {
                appendLine()
                info.rows(ctx, e.lat, e.lon).forEach { appendLine(it.replace(Regex("<[^>]+>"), "")) }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(Texts.cellLabel(e.key))
            .setMessage(text.trim())
            .setPositiveButton(R.string.log_show_on_map) { _, _ ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_LAT, e.lat)
                        .putExtra(MainActivity.EXTRA_LON, e.lon)
                )
            }
            .setNeutralButton(R.string.cells_copy_id) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Cell-ID", e.key))
                Toast.makeText(this, R.string.cells_copied, Toast.LENGTH_SHORT).show()
            }
            .apply {
                if (info != null) setNegativeButton("CellMapper") { _, _ ->
                    UpdateChecker.open(this@CellListActivity, info.cellMapperUrl(e.lat, e.lon))
                } else setNegativeButton(R.string.close, null)
            }
            .show()
    }

    private fun export(uri: Uri) {
        val rows = shown
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                        w.write("cell,radio,status,first_seen_utc,last_seen_utc,measurements,strongest_dbm,lat,lon\n")
                        for (e in rows) {
                            w.write(
                                "${e.key},${e.radio},${e.reasonTypes.joinToString("+").ifEmpty { if (e.known) "KNOWN" else "UNKNOWN" }},${Instant.ofEpochMilli(e.firstTs)}," +
                                    "${Instant.ofEpochMilli(e.lastTs)},${e.count},${e.bestDbm ?: ""}," +
                                    String.format(Locale.ROOT, "%.6f,%.6f\n", e.lat, e.lon)
                            )
                        }
                    }
                }
                Toast.makeText(this@CellListActivity, tr(R.string.cells_saved_n, rows.size), Toast.LENGTH_LONG).show()
            } catch (ex: Exception) {
                Toast.makeText(this@CellListActivity, tr(R.string.export_failed, ex.message), Toast.LENGTH_LONG).show()
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
            v.findViewById<TextView>(R.id.cellKey).text = Net.label(this@CellListActivity, Net.of(e.key)) + "  " + e.key
            v.findViewById<TextView>(R.id.cellMeta).text =
                tr(R.string.cells_meta, e.count, e.bestDbm?.toString() ?: "?", fmt(e.lastTs))

            val fg = when {
                e.moving -> Color.rgb(240, 149, 149)
                e.strong -> Color.rgb(237, 147, 177)
                e.shortLived -> Color.rgb(95, 196, 180)
                e.isNew -> Color.rgb(232, 176, 74)
                e.downgraded -> Color.rgb(175, 169, 236)
                e.known -> Color.rgb(183, 198, 195)
                else -> Color.rgb(243, 217, 160)
            }
            val bg = Color.argb(46, Color.red(fg), Color.green(fg), Color.blue(fg))
            v.findViewById<TextView>(R.id.cellStatus).apply {
                text = e.statusLabel(this@CellListActivity)
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
