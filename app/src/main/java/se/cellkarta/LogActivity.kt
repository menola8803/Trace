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

        findViewById<TextView>(R.id.btnBack).apply {
            text = "‹  ${getString(R.string.log_title)}"
            setOnClickListener { finish() }
        }
        val root = findViewById<View>(R.id.logRoot)
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
            val days = withContext(Dispatchers.IO) { db.days() }
            val counts = withContext(Dispatchers.IO) {
                days.associate { day -> day.day to db.flagsBetween(start(day.day), start(day.day) + 86_400_000L).size }
            }
            list.removeAllViews()
            if (days.isEmpty()) {
                list.addView(text(getString(R.string.log_empty), R.color.muted, 14f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, (48 * d).toInt(), 0, 0)
                })
            }
            days.forEach { list.addView(dayCard(it, counts[it.day] ?: 0)) }
        }
    }

    private fun start(day: String): Long = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun text(s: String, color: Int, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(ContextCompat.getColor(this@LogActivity, color))
        if (bold) typeface = ResourcesCompat.getFont(this@LogActivity, R.font.jakarta_bold)
        setLineSpacing(2f * d, 1f)
    }

    private fun dayCard(day: DaySummary, eventCount: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@LogActivity, R.drawable.bg_card)
            val p = (14 * d).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() }
            isClickable = true
            isFocusable = true
            foreground = ContextCompat.getDrawable(this@LogActivity, android.R.drawable.list_selector_background)
        }

        val date = LocalDate.parse(day.day)
        card.addView(text(dayFmt.format(date).replaceFirstChar { it.titlecase(Lang.locale(this)) }, R.color.ink, 16f, bold = true))
        card.addView(text(
            tr(R.string.log_day_summary, day.measurements, day.cells,
                clock.format(Instant.ofEpochMilli(day.firstTs)), clock.format(Instant.ofEpochMilli(day.lastTs))),
            R.color.muted, 13f
        ))
        if (day.minDbm != null && day.maxDbm != null) {
            card.addView(text(tr(R.string.log_day_signal, day.minDbm, day.maxDbm), R.color.muted, 13f))
        }
        card.addView(
            if (eventCount > 0) text("›  " + tr(R.string.log_day_events, eventCount), R.color.gold, 13f, bold = true)
            else text(getString(R.string.log_day_no_events), R.color.dim, 13f)
        )
        card.addView(text(tr(R.string.log_day_files, day.day), R.color.dim, 11f).apply {
            setPadding(0, (4 * d).toInt(), 0, 0)
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
                val flags = withContext(Dispatchers.IO) { db.flagsBetween(start(day.day), start(day.day) + 86_400_000L) }
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
