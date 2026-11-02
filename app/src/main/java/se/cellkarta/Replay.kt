package se.cellkarta

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Spelar upp en dag på kartan, steg för steg: vägen du rörde dig, cellerna där du hörde dem
 * första gången och varje händelse i det ögonblick den inträffade.
 */
class Replay(
    private val act: BaseActivity,
    private val map: MapView,
    private val db: CellDb,
    private val bubble: MapMarks.Bubble,
    private val panel: View,
    private val eventMarker: (FlagRow) -> Marker,
    private val tapOutside: Overlay,
    private val onClose: () -> Unit,
) {
    /** Virtuella sekunder per verklig sekund. */
    private val speeds = intArrayOf(60, 300, 900, 3600)
    private var speedIx = 1

    private val zone = ZoneId.systemDefault()
    private val clock by lazy { DateTimeFormatter.ofPattern("HH:mm:ss", Lang.locale(act)).withZone(zone) }
    private val dayFmt by lazy { DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Lang.locale(act)) }
    private val density = act.resources.displayMetrics.density

    private val dateText = panel.findViewById<TextView>(R.id.replayDate)
    private val timeText = panel.findViewById<TextView>(R.id.replayTime)
    private val infoText = panel.findViewById<TextView>(R.id.replayInfo)
    private val seek = panel.findViewById<SeekBar>(R.id.replaySeek)
    private val btnPlay = panel.findViewById<Button>(R.id.btnReplayPlay)
    private val btnSpeed = panel.findViewById<Button>(R.id.btnReplaySpeed)

    private var rows: List<ReplayRow> = emptyList()
    private var path: List<ReplayRow> = emptyList()
    private var events: List<FlagRow> = emptyList()
    private var t0 = 0L
    private var t1 = 0L
    private var cur = 0L
    private var playing = false
    private var lastFrame = 0L

    // Det som hittills har ritats, så att uppspelningen bara lägger till det nya.
    private var rowIx = 0
    private var eventIx = 0
    private val seenCells = HashSet<String>()
    private var cellCount = 0
    private var lastEventLine = ""
    private val added = ArrayList<Overlay>()
    private var pathLine: Polyline? = null
    private var position: Marker? = null

    var active = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = SystemClock.uptimeMillis()
            cur += (now - lastFrame) * speeds[speedIx]
            lastFrame = now
            if (cur >= t1) {
                cur = t1
                playing = false
            }
            render()
            if (playing) handler.postDelayed(this, 40L) else updateButtons()
        }
    }

    init {
        panel.findViewById<View>(R.id.btnReplayClose).setOnClickListener { close() }
        btnPlay.setOnClickListener { if (playing) pause() else play() }
        btnSpeed.setOnClickListener {
            speedIx = (speedIx + 1) % speeds.size
            updateButtons()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || t1 <= t0) return
                val target = t0 + (t1 - t0) * p / 1000L
                if (target < cur) reset()
                cur = target
                render()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = pause()
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    fun open(day: String) {
        active = true
        pause()
        InfoWindow.closeAllInfoWindowsOn(map)
        bubble.openKey = null
        panel.visibility = View.VISIBLE
        dateText.text = dayFmt.format(LocalDate.parse(day)).replaceFirstChar { it.titlecase(Lang.locale(act)) }
        timeText.text = ""
        infoText.text = act.getString(R.string.preparing)

        act.lifecycleScope.launch {
            val from = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = from + 86_400_000L
            val loaded = withContext(Dispatchers.IO) { db.replayRows(from, to) to db.flagsBetween(from, to) }
            rows = loaded.first
            events = loaded.second
            if (rows.isEmpty()) {
                infoText.text = act.getString(R.string.replay_empty)
                return@launch
            }
            path = decimate(rows)
            t0 = rows.first().ts
            t1 = maxOf(rows.last().ts, events.lastOrNull()?.ts ?: 0L)
            reset()
            cur = t0
            map.controller.setZoom(16.0)
            map.controller.setCenter(GeoPoint(rows.first().lat, rows.first().lon))
            render()
            play()
        }
    }

    fun close() {
        pause()
        active = false
        clearOverlays()
        bubble.openKey = null
        InfoWindow.closeAllInfoWindowsOn(map)
        panel.visibility = View.GONE
        onClose()
    }

    private fun play() {
        if (rows.isEmpty()) return
        if (cur >= t1) {
            reset()
            cur = t0
        }
        playing = true
        lastFrame = SystemClock.uptimeMillis()
        handler.removeCallbacks(tick)
        handler.post(tick)
        updateButtons()
    }

    private fun pause() {
        playing = false
        handler.removeCallbacks(tick)
        updateButtons()
    }

    private fun updateButtons() {
        btnPlay.setText(if (playing) R.string.replay_pause else R.string.replay_play)
        val s = speeds[speedIx]
        btnSpeed.text = if (s >= 3600) act.getString(R.string.replay_speed_h)
        else act.tr(R.string.replay_speed_min, s / 60)
    }

    /** En position var 20:e meter eller var minut räcker för att rita vägen. */
    private fun decimate(all: List<ReplayRow>): List<ReplayRow> {
        val out = ArrayList<ReplayRow>()
        for (r in all) {
            val last = out.lastOrNull()
            if (last == null || r.ts - last.ts > 60_000L || Geo.km(last.lat, last.lon, r.lat, r.lon) > 0.02) out.add(r)
        }
        return out
    }

    private fun clearOverlays() {
        map.overlays.removeAll(added.toSet())
        added.clear()
        pathLine = null
        position = null
        map.invalidate()
    }

    /** Tömmer kartan och börjar om från dagens början. */
    private fun reset() {
        clearOverlays()
        InfoWindow.closeAllInfoWindowsOn(map)
        bubble.openKey = null
        map.overlays.clear()
        rowIx = 0
        eventIx = 0
        seenCells.clear()
        cellCount = 0
        lastEventLine = ""
        map.overlays.add(tapOutside)

        pathLine = Polyline(map).apply {
            outlinePaint.color = ContextCompat.getColor(act, R.color.mint)
            outlinePaint.strokeWidth = 4f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            setInfoWindow(null)
        }.also { add(it) }
        position = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(act, R.drawable.ic_serving)
            setInfoWindow(null)
        }
    }

    private fun add(o: Overlay) {
        map.overlays.add(o)
        added.add(o)
    }

    private fun render() {
        // Vägen fram till nu
        val upto = path.indexOfLast { it.ts <= cur }
        if (upto >= 0) {
            pathLine?.setPoints(path.subList(0, upto + 1).map { GeoPoint(it.lat, it.lon) })
            val here = path[upto]
            position?.let { m ->
                m.position = GeoPoint(here.lat, here.lon)
                if (m !in added) add(m)
                // Följ med om du rör dig utanför bilden
                if (!map.boundingBox.contains(m.position)) map.controller.animateTo(m.position)
            }
        }

        // Nya celler, där du hörde dem första gången
        var serving: ReplayRow? = null
        while (rowIx < rows.size && rows[rowIx].ts <= cur) {
            val r = rows[rowIx]
            if (seenCells.add(r.key)) {
                cellCount++
                add(Marker(map).apply {
                    position = GeoPoint(r.lat, r.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = cellDot()
                    title = Texts.cellLabel(r.key)
                    snippet = clock.format(Instant.ofEpochMilli(r.ts)) + (r.dbm?.let { " · $it dBm" } ?: "")
                    setInfoWindow(bubble)
                })
            }
            rowIx++
        }
        for (i in (rowIx - 1) downTo maxOf(0, rowIx - 40)) {
            if (rows[i].registered) { serving = rows[i]; break }
        }

        // Händelser i det ögonblick de inträffade: markeringen och rutan öppnas
        while (eventIx < events.size && events[eventIx].ts <= cur) {
            val f = events[eventIx]
            val m = eventMarker(f)
            add(m)
            m.showInfoWindow()
            lastEventLine = "? ${clock.format(Instant.ofEpochMilli(f.ts))} · ${Texts.reasonName(act, f.type)}"
            eventIx++
        }

        timeText.text = clock.format(Instant.ofEpochMilli(cur))
        infoText.text = listOfNotNull(
            serving?.let { act.tr(R.string.replay_connected, Texts.cellLabel(it.key) + (it.dbm?.let { d -> " · $d dBm" } ?: "")) },
            act.tr(R.string.replay_cells, cellCount, eventIx),
            lastEventLine.takeIf { it.isNotEmpty() },
        ).joinToString("\n")
        if (t1 > t0) seek.progress = ((cur - t0) * 1000L / (t1 - t0)).toInt()
        map.invalidate()
    }

    private var dot: BitmapDrawable? = null
    private fun cellDot(): BitmapDrawable = dot ?: run {
        val size = (16 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = ContextCompat.getColor(act, R.color.mint)
        c.drawCircle(size / 2f, size / 2f, size * 0.32f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * density
        p.color = Color.WHITE
        c.drawCircle(size / 2f, size / 2f, size * 0.32f, p)
        BitmapDrawable(act.resources, bmp).also { dot = it }
    }
}
