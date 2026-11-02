package se.cellkarta

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
 *
 * Händelseläge: uppspelningen börjar en stund före händelsen, går långsamt, stannar exakt när
 * händelsen inträffar, zoomar in och öppnar förklaringsrutan. Förra/Nästa hoppar mellan händelser.
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
    companion object {
        private const val TAG = "Trace"
        /** Virtuella sekunder per verklig sekund. */
        private val SPEEDS = intArrayOf(10, 30, 60, 300, 900, 3600)
        private const val DAY_SPEED = 3      // 5 min/s för en hel dag
        private const val EVENT_SPEED = 1    // 30 s/s runt en händelse
        /** Händelseläget börjar så här långt före händelsen. */
        private const val EVENT_LEAD_MS = 3 * 60_000L
    }

    private var speedIx = DAY_SPEED

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
    private val eventRow = panel.findViewById<View>(R.id.replayEventRow)
    private val btnPrev = panel.findViewById<Button>(R.id.btnReplayPrev)
    private val btnNext = panel.findViewById<Button>(R.id.btnReplayNext)

    private var rows: List<ReplayRow> = emptyList()
    private var path: List<ReplayRow> = emptyList()
    private var events: List<FlagRow> = emptyList()
    private var t0 = 0L
    private var t1 = 0L
    private var cur = 0L
    private var playing = false
    private var lastFrame = 0L

    /** Händelsen som uppspelningen ska stanna vid, och vilken händelse som är vald. */
    private var stopAt: Long? = null
    private var focusIx = -1

    // Det som hittills har ritats, så att uppspelningen bara lägger till det nya.
    private var rowIx = 0
    private var eventIx = 0
    private val seenCells = HashSet<String>()
    private var cellCount = 0
    private val added = ArrayList<Overlay>()
    private val eventMarkers = HashMap<Int, Marker>()
    private var pathLine: Polyline? = null
    private var position: Marker? = null

    var active = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            try {
                val now = SystemClock.uptimeMillis()
                val next = cur + (now - lastFrame) * SPEEDS[speedIx]
                lastFrame = now
                val stop = stopAt
                when {
                    stop != null && next >= stop -> {
                        cur = stop
                        render()
                        arriveAtEvent()
                        return
                    }
                    next >= t1 -> {
                        cur = t1
                        playing = false
                    }
                    else -> cur = next
                }
                render()
                if (playing) handler.postDelayed(this, 40L) else updateButtons()
            } catch (e: Exception) {
                Log.w(TAG, "Uppspelningen stoppades", e)
                pause()
            }
        }
    }

    init {
        panel.findViewById<View>(R.id.btnReplayClose).setOnClickListener { close() }
        btnPlay.setOnClickListener { if (playing) pause() else play() }
        btnSpeed.setOnClickListener {
            speedIx = (speedIx + 1) % SPEEDS.size
            updateButtons()
        }
        btnPrev.setOnClickListener { jumpToEvent(prevEventIx()) }
        btnNext.setOnClickListener { jumpToEvent(nextEventIx()) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || t1 <= t0) return
                stopAt = null
                seekTo(t0 + (t1 - t0) * p / 1000L)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = pause()
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    /** Spelar upp dagen. Med startAt spelas händelsen vid den tidpunkten upp i händelseläge. */
    fun open(day: String, startAt: Long? = null) {
        active = true
        pause()
        InfoWindow.closeAllInfoWindowsOn(map)
        bubble.openKey = null
        panel.visibility = View.VISIBLE
        dateText.text = dayFmt.format(LocalDate.parse(day)).replaceFirstChar { it.titlecase(Lang.locale(act)) }
        timeText.text = ""
        infoText.text = act.getString(R.string.preparing)
        eventRow.visibility = View.GONE

        act.lifecycleScope.launch {
            try {
                val from = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
                val to = from + 86_400_000L
                val loaded = withContext(Dispatchers.IO) { db.replayRows(from, to) to db.flagsBetween(from, to) }
                rows = loaded.first
                events = loaded.second
                if (rows.isEmpty() && events.isEmpty()) {
                    infoText.text = act.getString(R.string.replay_empty)
                    return@launch
                }
                path = decimate(rows)
                t0 = minOf(rows.firstOrNull()?.ts ?: Long.MAX_VALUE, events.firstOrNull()?.ts ?: Long.MAX_VALUE)
                t1 = maxOf(rows.lastOrNull()?.ts ?: 0L, events.lastOrNull()?.ts ?: 0L)
                eventRow.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE

                val focus = startAt?.let { s -> events.indices.minByOrNull { kotlin.math.abs(events[it].ts - s) } }
                if (focus != null) {
                    jumpToEvent(focus)
                } else {
                    speedIx = DAY_SPEED
                    stopAt = null
                    focusIx = -1
                    reset()
                    cur = t0
                    rows.firstOrNull()?.let {
                        map.controller.setZoom(16.0)
                        map.controller.setCenter(GeoPoint(it.lat, it.lon))
                    }
                    render()
                    play()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Kunde inte starta uppspelningen", e)
                infoText.text = act.getString(R.string.error_generic)
            }
        }
    }

    fun close() {
        pause()
        active = false
        stopAt = null
        clearOverlays()
        bubble.openKey = null
        InfoWindow.closeAllInfoWindowsOn(map)
        panel.visibility = View.GONE
        onClose()
    }

    // ---- Händelseläge ----

    private fun prevEventIx(): Int {
        if (events.isEmpty()) return -1
        val before = events.indexOfLast { it.ts < cur - 1000L }
        return if (focusIx > 0 && events[focusIx].ts >= cur - 1000L) focusIx - 1 else maxOf(before, 0)
    }

    private fun nextEventIx(): Int {
        if (events.isEmpty()) return -1
        val after = events.indexOfFirst { it.ts > cur + 1000L }
        return if (after >= 0) after else events.lastIndex
    }

    /** Börjar en stund före händelsen, spelar långsamt och stannar när den inträffar. */
    private fun jumpToEvent(ix: Int) {
        if (ix !in events.indices) return
        pause()
        focusIx = ix
        val f = events[ix]
        stopAt = f.ts
        speedIx = EVENT_SPEED
        seekTo((f.ts - EVENT_LEAD_MS).coerceAtLeast(t0))
        // Visa platsen redan från början
        val start = path.lastOrNull { it.ts <= cur } ?: path.firstOrNull()
        map.controller.setZoom(16.5)
        map.controller.setCenter(if (start != null) GeoPoint(start.lat, start.lon) else GeoPoint(f.lat, f.lon))
        play()
    }

    /** Uppspelningen har nått händelsen: pausa, zooma in och öppna rutan. */
    private fun arriveAtEvent() {
        pause()
        stopAt = null
        val ix = focusIx.takeIf { it in events.indices } ?: return
        val f = events[ix]
        map.controller.animateTo(GeoPoint(f.lat, f.lon), 17.5, 600L)
        eventMarkers[ix]?.let { m ->
            bubble.openKey = m.relatedObject as? String
            m.showInfoWindow()
        }
        render()
    }

    // ---- Uppspelning ----

    private fun play() {
        if (t1 <= t0 && rows.isEmpty()) return
        if (cur >= t1) seekTo(t0)
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

    private fun seekTo(target: Long) {
        if (target < cur || added.isEmpty()) reset()
        cur = target.coerceIn(t0, maxOf(t0, t1))
        render()
    }

    private fun updateButtons() {
        btnPlay.setText(if (playing) R.string.replay_pause else R.string.replay_play)
        val s = SPEEDS[speedIx]
        btnSpeed.text = when {
            s >= 3600 -> act.getString(R.string.replay_speed_h)
            s >= 60 -> act.tr(R.string.replay_speed_min, s / 60)
            else -> act.tr(R.string.replay_speed_s, s)
        }
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
        eventMarkers.clear()
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
                if (playing && !map.boundingBox.contains(m.position)) map.controller.animateTo(m.position)
            }
        }

        // Nya celler, där du hörde dem första gången, med nättypens siffra och färg
        while (rowIx < rows.size && rows[rowIx].ts <= cur) {
            val r = rows[rowIx]
            if (seenCells.add(r.key)) {
                cellCount++
                add(Marker(map).apply {
                    position = GeoPoint(r.lat, r.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = MapMarks.netDot(act, Net.of(r.key))
                    title = Net.label(act, Net.of(r.key)) + " · " + Texts.cellLabel(r.key)
                    snippet = clock.format(Instant.ofEpochMilli(r.ts)) + (r.dbm?.let { " · $it dBm" } ?: "")
                    setInfoWindow(bubble)
                })
            }
            rowIx++
        }
        var serving: ReplayRow? = null
        for (i in (rowIx - 1) downTo maxOf(0, rowIx - 40)) {
            if (rows[i].registered) { serving = rows[i]; break }
        }

        // Händelser i det ögonblick de inträffade
        while (eventIx < events.size && events[eventIx].ts <= cur) {
            val m = eventMarker(events[eventIx])
            add(m)
            eventMarkers[eventIx] = m
            if (stopAt == null && playing) m.showInfoWindow()
            eventIx++
        }

        timeText.text = clock.format(Instant.ofEpochMilli(cur))
        val focus = events.getOrNull(focusIx)
        infoText.text = listOfNotNull(
            focus?.let {
                act.tr(R.string.replay_event_of, focusIx + 1, events.size, Texts.reasonName(act, it.type),
                    clock.format(Instant.ofEpochMilli(it.ts)))
            },
            serving?.let {
                act.tr(R.string.replay_connected,
                    Net.label(act, Net.of(it.key)) + " · " + Texts.cellLabel(it.key) + (it.dbm?.let { d -> " · $d dBm" } ?: ""))
            },
            act.tr(R.string.replay_cells, cellCount, eventIx),
        ).joinToString("\n")
        if (t1 > t0) seek.progress = ((cur - t0) * 1000L / (t1 - t0)).toInt()
        btnPrev.isEnabled = events.isNotEmpty()
        btnNext.isEnabled = events.isNotEmpty()
        map.invalidate()
    }
}
