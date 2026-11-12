package se.cellkarta

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

/** Allt som har loggats, dag för dag. Tryck på en dag för att se dess händelser. */
class LogActivity : BaseActivity() {

    private val db by lazy { CellDb.get(this) }
    private val zone = ZoneId.systemDefault()
    private val dayFmt by lazy { DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Lang.locale(this)) }
    private val clock by lazy { DateTimeFormatter.ofPattern("HH:mm", Lang.locale(this)).withZone(zone) }
    private val clockSec by lazy { DateTimeFormatter.ofPattern("HH:mm:ss", Lang.locale(this)).withZone(zone) }
    private val d by lazy { resources.displayMetrics.density }
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        list = findViewById(R.id.logList)

        Ui.header(this, getString(R.string.log_title), getString(R.string.log_sub))
        val root = findViewById<View>(R.id.logRoot)
        val top = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = top + bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
    }

    /** Filter: 0 alla, 1 live, 2 schemalagda, 3 med triangulering. */
    private var filter = 0
    private var all: List<Pair<Recording, Int>> = emptyList()

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) {
                db.recordings().map { r -> r to db.flagsBetween(r.from, r.to + 1000L).size }
            }
            render()
        }
    }

    private fun render() {
        list.removeAllViews()
        if (all.isEmpty()) {
            list.addView(text(getString(R.string.log_empty), R.color.muted, 14f).apply {
                gravity = Gravity.CENTER
                setPadding(0, (48 * d).toInt(), 0, 0)
            })
            return
        }
        // Tre sorters inspelningar: välj vilka som visas
        list.addView(android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() }
            addView(Ui.Segmented(this@LogActivity).apply {
                set(listOf(
                    0 to getString(R.string.log_filter_all),
                    1 to getString(R.string.log_filter_live),
                    2 to getString(R.string.log_filter_scheduled),
                    3 to getString(R.string.rec_tri),
                ), filter)
                onChange = { v -> filter = v; render() }
            })
        })
        val shown = all.filter { (r, _) ->
            when (filter) {
                1 -> r.kind == CellDb.KIND_LIVE
                2 -> r.kind == CellDb.KIND_SCHEDULED
                3 -> r.tri
                else -> true
            }
        }
        if (shown.isEmpty()) {
            list.addView(text(getString(R.string.log_filter_none), R.color.muted, 14f).apply {
                gravity = Gravity.CENTER
                setPadding(0, (32 * d).toInt(), 0, 0)
            })
        }
        var lastDay: String? = null
        shown.forEach { (r, events) ->
            if (r.day != lastDay) {
                lastDay = r.day
                list.addView(text(dayFmt.format(LocalDate.parse(r.day)).replaceFirstChar { it.titlecase(Lang.locale(this)) },
                    R.color.gold, 13f, bold = true).apply { setPadding(0, (18 * d).toInt(), 0, 0) })
            }
            list.addView(recordingCard(r, events))
        }
    }

    private fun text(s: String, color: Int, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(ContextCompat.getColor(this@LogActivity, color))
        if (bold) typeface = ResourcesCompat.getFont(this@LogActivity, R.font.jakarta_bold)
        setLineSpacing(2f * d, 1f)
    }

    private fun recordingCard(r: Recording, eventCount: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@LogActivity, R.drawable.bg_card)
            val p = (14 * d).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
            isClickable = true
            isFocusable = true
            foreground = ContextCompat.getDrawable(this@LogActivity, android.R.drawable.list_selector_background)
        }

        card.addView(Recordings.pills(this, r))
        card.addView(text(
            tr(R.string.log_day_summary, r.measurements, r.cells,
                clock.format(Instant.ofEpochMilli(r.from)), clock.format(Instant.ofEpochMilli(r.to))),
            R.color.ink, 14f, bold = true
        ).apply { setPadding(0, (8 * d).toInt(), 0, 0) })
        if (r.minDbm != null && r.maxDbm != null) {
            card.addView(text(tr(R.string.log_day_signal, r.minDbm, r.maxDbm), R.color.muted, 13f))
        }
        card.addView(
            if (eventCount > 0) text("›  " + tr(R.string.log_day_events, eventCount), R.color.gold, 13f, bold = true)
            else text(getString(R.string.log_day_no_events), R.color.dim, 13f)
        )
        card.addView(text(tr(R.string.log_day_files, r.day), R.color.dim, 11f).apply {
            setPadding(0, (4 * d).toInt(), 0, 0)
        })
        card.addView(text(getString(R.string.replay_recording), R.color.mint, 13f, bold = true).apply {
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
            setOnClickListener { startActivity(MainActivity.replayIntent(this@LogActivity, r)) }
        })

        val events = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        card.addView(events)

        if (eventCount > 0) card.setOnClickListener {
            if (events.visibility == View.VISIBLE) {
                events.visibility = View.GONE
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val flags = withContext(Dispatchers.IO) { db.flagsBetween(r.from, r.to + 1000L) }
                events.removeAllViews()
                flags.forEach { events.addView(eventRow(it)) }
                events.visibility = View.VISIBLE
            }
        }
        return card
    }

    private fun eventRow(f: FlagRow): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = (8 * d).toInt()
        setPadding(0, p, 0, p)
        background = ContextCompat.getDrawable(this@LogActivity, android.R.drawable.list_selector_background)

        addView(ImageView(this@LogActivity).apply {
            setImageDrawable(MapMarks.icon(this@LogActivity, "?", MapMarks.colorFor(f.type), 30f))
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt())
        })
        addView(LinearLayout(this@LogActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (10 * d).toInt() }
            addView(text("${clockSec.format(Instant.ofEpochMilli(f.ts))} · ${Texts.reasonName(this@LogActivity, f.type)}", R.color.ink, 13f, bold = true))
            val detail = Texts.detailText(this@LogActivity, f.type, f.detail)
            addView(text(listOf(detail, Texts.cellLabel(f.key)).filter { it.isNotBlank() }.joinToString("\n"), R.color.muted, 12f))
            addView(text("›  " + getString(R.string.log_show_on_map), R.color.mint, 12f))
        })
        setOnClickListener {
            startActivity(
                Intent(this@LogActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_LAT, f.lat)
                    .putExtra(MainActivity.EXTRA_LON, f.lon)
            )
        }
    }
}
