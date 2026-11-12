package se.cellkarta

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Alla master som trianguleringen har hittat, säkrast först. Tryck för att visa en på kartan. */
class FoundMastsActivity : BaseActivity() {

    private val db by lazy { CellDb.get(this) }
    private val d by lazy { resources.displayMetrics.density }
    private val timeFmt by lazy { DateTimeFormatter.ofPattern("d MMM HH:mm", Lang.locale(this)).withZone(ZoneId.systemDefault()) }
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_masts)
        list = findViewById(R.id.mastsList)
        Ui.header(this, getString(R.string.masts_title), getString(R.string.masts_sub))
        val root = findViewById<View>(R.id.mastsRoot)
        val top = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = top + bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val masts = withContext(Dispatchers.IO) { runCatching { db.allFound() }.getOrDefault(emptyList()) }
            render(masts)
        }
    }

    private fun text(s: String, color: Int, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(ContextCompat.getColor(this@FoundMastsActivity, color))
        if (bold) typeface = ResourcesCompat.getFont(this@FoundMastsActivity, R.font.jakarta_bold)
        setLineSpacing(2f * d, 1f)
    }

    private fun render(masts: List<Triangulate.Result>) {
        list.removeAllViews()
        if (masts.isEmpty()) {
            list.addView(text(getString(R.string.masts_empty), R.color.muted, 14f).apply {
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (48 * d).toInt(), (8 * d).toInt(), 0)
            })
            return
        }
        val q = masts.groupingBy { it.quality }.eachCount()
        list.addView(text(
            tr(R.string.masts_count, masts.size, q[Triangulate.Quality.SECURE] ?: 0, q[Triangulate.Quality.GOOD] ?: 0,
                q[Triangulate.Quality.ROUGH] ?: 0),
            R.color.ink, 14f, bold = true
        ).apply { setPadding(0, (14 * d).toInt(), 0, 0) })
        list.addView(Button(this, null, 0, R.style.Trace_Button).apply {
            text = getString(R.string.masts_export)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
            setOnClickListener { export(masts) }
        })
        masts.forEach { list.addView(card(it)) }
    }

    private fun card(r: Triangulate.Result): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@FoundMastsActivity, R.drawable.bg_card)
        val p = (14 * d).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
        isClickable = true
        isFocusable = true
        foreground = ContextCompat.getDrawable(this@FoundMastsActivity, android.R.drawable.list_selector_background)

        addView(LinearLayout(this@FoundMastsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@FoundMastsActivity).apply {
                setImageDrawable(MapMarks.mastIcon(this@FoundMastsActivity, 36f, MapMarks.COLOR_TRI))
                layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt())
            })
            addView(LinearLayout(this@FoundMastsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * d).toInt() }
                addView(text("${r.operator} · ${Net.label(this@FoundMastsActivity, Net.of(r.siteKey))}", R.color.ink, 15f, bold = true))
                addView(text(getString(R.string.row_mast_id) + " " + r.site, R.color.muted, 12.5f))
            })
            addView(pill(qualityLabel(r.quality), qualityColor(r.quality)))
        })

        val extras = listOfNotNull(
            if (r.taPoints > 0) getString(R.string.masts_ta) else null,
            if (r.ambiguous) getString(R.string.masts_two_sides) else null,
        )
        addView(text(
            tr(R.string.masts_line, Triangulate.round(r.radiusM), r.points, r.places) +
                (if (extras.isEmpty()) "" else " · " + extras.joinToString(" · ")),
            R.color.muted, 13f
        ).apply { setPadding(0, (8 * d).toInt(), 0, 0) })
        addView(text(tr(R.string.masts_updated, timeFmt.format(Instant.ofEpochMilli(r.ts))), R.color.dim, 12f))
        addView(text(getString(R.string.masts_show), R.color.mint, 13f, bold = true).apply {
            setPadding(0, (8 * d).toInt(), 0, 0)
        })
        setOnClickListener {
            startActivity(
                Intent(this@FoundMastsActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_LAT, r.lat)
                    .putExtra(MainActivity.EXTRA_LON, r.lon)
                    .putExtra(MainActivity.EXTRA_ZOOM, 17.0)
                    .putExtra(MainActivity.EXTRA_OPEN, "tri:${r.siteKey}")
            )
        }
    }

    private fun qualityLabel(q: Triangulate.Quality) = getString(when (q) {
        Triangulate.Quality.INSUFFICIENT -> R.string.q_insufficient
        Triangulate.Quality.ROUGH -> R.string.q_rough
        Triangulate.Quality.GOOD -> R.string.q_good
        Triangulate.Quality.SECURE -> R.string.q_secure
    })

    private fun qualityColor(q: Triangulate.Quality) = when (q) {
        Triangulate.Quality.SECURE -> R.color.mint
        Triangulate.Quality.GOOD -> R.color.gold
        else -> R.color.dim
    }

    private fun pill(label: String, colorRes: Int) = TextView(this).apply {
        val color = ContextCompat.getColor(this@FoundMastsActivity, colorRes)
        text = label
        textSize = 11.5f
        setTextColor(color)
        typeface = ResourcesCompat.getFont(this@FoundMastsActivity, R.font.jakarta_bold)
        setPadding((9 * d).toInt(), (3 * d).toInt(), (9 * d).toInt(), (3 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 50 * d
            setStroke((1 * d).toInt().coerceAtLeast(1), color)
        }
    }

    /** Alla hittade master som en CSV-fil i Download/Trace. */
    private fun export(masts: List<Triangulate.Result>) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                val sb = StringBuilder("site_key,operator,radio,site,lat,lon,uncertainty_m,quality,measurements,places,ta_points,alt_lat,alt_lon,sectors,calculated_utc\n")
                for (r in masts) {
                    sb.append(String.format(Locale.ROOT, "%s,%s,%s,%d,%.6f,%.6f,%.0f,%s,%d,%d,%d,%s,%s,%s,%s\n",
                        r.siteKey, r.operator.replace(',', ' '), r.radio, r.site, r.lat, r.lon, r.radiusM,
                        r.quality.name.lowercase(Locale.ROOT), r.points, r.places, r.taPoints,
                        r.altLat?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "",
                        r.altLon?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "",
                        r.sectors.joinToString(" ") { s -> "${s.sector ?: "-"}@${s.bearingDeg.toInt()}" },
                        Instant.ofEpochMilli(r.ts).toString()))
                }
                TraceFiles.writeFile(this@FoundMastsActivity, "trace-masts-${LocalDate.now()}.csv", sb.toString())
            }
            Toast.makeText(
                this@FoundMastsActivity,
                if (name != null) tr(R.string.masts_exported, name) else getString(R.string.masts_export_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
