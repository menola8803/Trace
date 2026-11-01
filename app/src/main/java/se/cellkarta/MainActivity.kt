package se.cellkarta

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LAT = "se.cellkarta.LAT"
        const val EXTRA_LON = "se.cellkarta.LON"

        /** Från den här zoomnivån visas enskilda master, annars kluster. */
        private const val DETAIL_ZOOM = 11.0
        private const val MAX_POINTS = 20_000

        private val COLOR_KNOWN = Color.rgb(95, 94, 90)
        private val COLOR_NEW = Color.rgb(239, 159, 39)
        private val COLOR_TRACK = Color.rgb(192, 57, 43)
        private val COLOR_DOWNGRADE = Color.rgb(127, 119, 221)
        private val COLOR_OWN = Color.rgb(24, 95, 165)

        private val SV: Locale = Locale.forLanguageTag("sv-SE")
    }

    private data class Counts(
        val known: Long = 0, val seen: Long = 0, val newCells: Int = 0, val moving: Int = 0, val downgrades: Int = 0,
    )

    private class MapData(
        val clusters: List<Cluster>,
        val known: List<KnownCell>,
        val own: List<CellEntry>,
        val newCells: List<SeenRow>,
        val tracks: List<Pair<SeenRow, List<Geo.TrackPoint>>>,
        val downgrades: List<FlagRow>,
        val counts: Counts,
    )

    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private lateinit var myLocation: MyLocationNewOverlay
    private val db by lazy { CellDb.get(this) }
    private var refreshJob: Job? = null
    private var counts = Counts()
    private var importing = false
    private var centeredOnUser = false
    private val density by lazy { resources.displayMetrics.density }
    private val timeFmt = DateTimeFormatter.ofPattern("d MMM HH:mm", SV).withZone(ZoneId.systemDefault())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                enableMyLocation()
                startMonitor()
            } else {
                toast("Appen behöver exakt plats för att kunna läsa cellinformation.")
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::runImport) }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(::runExport) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }

        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        status = findViewById(R.id.status)
        btnStart = findViewById(R.id.btnStart)

        findViewById<TextView>(R.id.legend).text = buildSpannedString {
            color(COLOR_OWN) { append("●") }; append(" Dina  ")
            color(COLOR_KNOWN) { append("●") }; append(" Kända  ")
            color(COLOR_NEW) { append("▲") }; append(" Ny  ")
            color(COLOR_TRACK) { append("━") }; append(" Rör sig  ")
            color(COLOR_DOWNGRADE) { append("◆") }; append(" Nedgradering")
        }

        applyInsets()
        setupMap()

        btnStart.setOnClickListener {
            if (LiveStatus.state.value.running) stopMonitor() else requestPermissionsAndStart()
        }
        findViewById<Button>(R.id.btnCells).setOnClickListener {
            startActivity(Intent(this, CellListActivity::class.java))
        }
        findViewById<Button>(R.id.btnMore).setOnClickListener { showMoreMenu(it) }

        focusFromIntent(intent)
        autoCheckForUpdate()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LiveStatus.state.collect { renderStatus(it) } }
                launch { LiveStatus.dataVersion.collect { scheduleRefresh(0) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        focusFromIntent(intent)
    }

    /** "Visa på kartan" från listan över sparade celler. */
    private fun focusFromIntent(intent: Intent?) {
        if (intent == null) return
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return
        map.controller.setZoom(14.0)
        map.controller.setCenter(GeoPoint(lat, lon))
        intent.removeExtra(EXTRA_LAT)
        intent.removeExtra(EXTRA_LON)
        scheduleRefresh(0)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        enableMyLocation()
    }

    override fun onPause() {
        myLocation.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    // ---- Layout ----

    private fun applyInsets() {
        val panel = findViewById<View>(R.id.panel)
        val legend = findViewById<View>(R.id.legend)
        val baseBottom = panel.paddingBottom
        val baseTop = (legend.layoutParams as MarginLayoutParams).topMargin
        val baseStart = (legend.layoutParams as MarginLayoutParams).leftMargin

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            panel.updatePadding(bottom = baseBottom + bars.bottom)
            legend.updateLayoutParams<MarginLayoutParams> {
                topMargin = baseTop + bars.top
                leftMargin = baseStart + bars.left
            }
            insets
        }
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setTilesScaledToDpi(true)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 4.0
        map.maxZoomLevel = 19.0

        // Startvy: hela Sverige
        map.controller.setZoom(4.6)
        map.controller.setCenter(GeoPoint(62.4, 16.8))

        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        map.overlays.add(myLocation)

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { scheduleRefresh(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { scheduleRefresh(); return false }
        })
        map.addOnFirstLayoutListener { _, _, _, _, _ -> scheduleRefresh(0) }
    }

    // ---- Övervakning ----

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun enableMyLocation() {
        if (!hasFineLocation()) return
        myLocation.enableMyLocation()
        if (!centeredOnUser) {
            myLocation.runOnFirstFix {
                runOnUiThread {
                    val here = myLocation.myLocation ?: return@runOnUiThread
                    if (centeredOnUser) return@runOnUiThread
                    centeredOnUser = true
                    map.controller.animateTo(here, 14.0, 800L)
                }
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) startMonitor() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startMonitor() {
        ContextCompat.startForegroundService(this, Intent(this, CellMonitorService::class.java))
    }

    private fun stopMonitor() {
        stopService(Intent(this, CellMonitorService::class.java))
    }

    // ---- Uppdateringar ----

    /** Söker efter ny version högst en gång per dygn, om det är påslaget i inställningarna. */
    private fun autoCheckForUpdate() {
        if (!Prefs.autoUpdate(this)) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(this) < 24 * 3_600_000L) return
        lifecycleScope.launch {
            val latest = try { UpdateChecker.latest() } catch (e: Exception) { return@launch }
            Prefs.setLastUpdateCheck(this@MainActivity, System.currentTimeMillis())
            if (latest.build > UpdateChecker.currentBuild(this@MainActivity)) {
                UpdateChecker.offer(this@MainActivity, latest)
            }
        }
    }

    // ---- Meny, nyckel, import och export ----

    private fun showMoreMenu(anchor: View) {
        val hasKey = Prefs.apiKey(this) != null
        PopupMenu(this, anchor).apply {
            menu.add(0, 5, 0, "Inställningar")
            menu.add(0, 1, 0, "Ladda ner Sverige från OpenCellID")
            menu.add(0, 2, 1, if (hasKey) "OpenCellID-nyckel (sparad)" else "OpenCellID-nyckel…")
            menu.add(0, 3, 2, "Importera fil…")
            menu.add(0, 4, 3, "Exportera mätlogg")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    5 -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    1 -> downloadSweden()
                    2 -> editApiKey()
                    3 -> if (!importing) importLauncher.launch(arrayOf("*/*"))
                    4 -> exportLauncher.launch("trace-logg.csv")
                }
                true
            }
        }.show()
    }

    private fun editApiKey(thenDownload: Boolean = false) {
        val input = EditText(this).apply {
            setText(Prefs.apiKey(this@MainActivity).orEmpty())
            hint = "pk.0123456789abcdef"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val pad = (20 * density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("OpenCellID-nyckel")
            .setMessage(
                "Skapa ett gratis konto på opencellid.org och klistra in din API-nyckel här. " +
                    "Den sparas bara i appen på den här telefonen."
            )
            .setView(box)
            .setPositiveButton("Spara") { _, _ ->
                val key = input.text.toString().trim()
                Prefs.setApiKey(this, key)
                if (key.isEmpty()) toast("Nyckeln är borttagen.")
                else {
                    toast("Nyckeln är sparad.")
                    if (thenDownload) downloadSweden()
                }
            }
            .setNeutralButton("Ta bort") { _, _ ->
                Prefs.setApiKey(this, null)
                toast("Nyckeln är borttagen.")
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun downloadSweden() {
        val key = Prefs.apiKey(this) ?: return editApiKey(thenDownload = true)
        if (importing) return
        runReferenceJob("Laddade ner") { progress ->
            OpenCellIdImporter.downloadAndImport(this, key, progress = progress)
        }
    }

    private fun runImport(uri: Uri) {
        if (importing) return
        runReferenceJob("Importerade") { progress ->
            OpenCellIdImporter.importFile(this, uri, progress)
        }
    }

    private fun runReferenceJob(doneVerb: String, job: suspend (suspend (String) -> Unit) -> Long) {
        importing = true
        lifecycleScope.launch {
            status.text = "Förbereder…"
            try {
                val n = job { text -> withContext(Dispatchers.Main) { status.text = text } }
                toast(String.format(SV, "%s %,d celler.", doneVerb, n))
            } catch (e: Exception) {
                toast(e.message ?: "Något gick fel.")
            } finally {
                importing = false
                LiveStatus.bump()
            }
        }
    }

    private fun runExport(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { db.exportObservations(it) }
                }
                toast("Loggen är sparad.")
            } catch (e: Exception) {
                toast("Exporten misslyckades: ${e.message}")
            }
        }
    }

    // ---- Status ----

    private fun renderStatus(st: LiveStatus.State) {
        btnStart.text = if (st.running) "Stoppa" else "Starta"
        if (importing) return

        val first = when {
            !st.running -> "Övervakningen är avstängd."
            st.note != null -> st.note
            st.serving != null -> "Ansluten: ${st.serving}"
            else -> "Söker celler…"
        }
        val lines = mutableListOf(
            first,
            String.format(
                SV, "Dina celler: %,d · Kända: %,d · Nya: %d · Rör sig: %d · Nedgraderingar: %d",
                counts.seen, counts.known, counts.newCells, counts.moving, counts.downgrades
            )
        )
        when {
            counts.seen == 0L && !st.running ->
                lines += "Tryck Starta. Cellerna du hör dyker upp som blå prickar där du är."
            counts.seen == 0L ->
                lines += "Väntar på första mätningen. Gå gärna ut, GPS:en behöver fri sikt."
            counts.known == 0L ->
                lines += "Ladda ner Sverige under Mer för att se kända master och kunna upptäcka nya celler."
        }
        status.text = lines.joinToString("\n")
    }

    // ---- Karta ----

    private fun scheduleRefresh(delayMs: Long = 300) {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            delay(delayMs)
            if (map.width == 0 || map.height == 0) return@launch
            val box = map.boundingBox
            val zoom = map.zoomLevelDouble
            val data = withContext(Dispatchers.IO) { loadMapData(box, zoom) }
            draw(data)
            counts = data.counts
            renderStatus(LiveStatus.state.value)
        }
    }

    /** Rutstorlek i grader som motsvarar ungefär 70 dp på skärmen vid aktuell zoom. */
    private fun gridDegrees(zoom: Double) = 70.0 * 360.0 / (256.0 * 2.0.pow(zoom))

    private fun loadMapData(box: BoundingBox, zoom: Double): MapData {
        val s = box.latSouth
        val n = box.latNorth
        val w = box.lonWest
        val e = box.lonEast
        val detailed = zoom >= DETAIL_ZOOM

        val known = if (detailed) db.knownInBox(s, w, n, e, MAX_POINTS) else emptyList()
        val clusters = if (detailed) emptyList() else db.clustersInBox(s, w, n, e, gridDegrees(zoom))
        val own = db.seenInBox(s, w, n, e, MAX_POINTS)
        val newCells = db.flaggedSeen(CellDb.FLAG_NEW)
        val tracks = db.flaggedSeen(CellDb.FLAG_MOVING).map { it to Geo.track(db.observations(it.key, 0L, 5000)) }
        val downgrades = db.flags(CellDb.FLAG_DOWNGRADE, 200)
        val fc = db.flagCounts()

        return MapData(
            clusters, known, own, newCells, tracks, downgrades,
            Counts(
                db.knownCount(),
                db.seenCount(),
                fc[CellDb.FLAG_NEW] ?: 0,
                fc[CellDb.FLAG_MOVING] ?: 0,
                fc[CellDb.FLAG_DOWNGRADE] ?: 0,
            )
        )
    }

    private fun draw(d: MapData) {
        InfoWindow.closeAllInfoWindowsOn(map)
        val overlays = map.overlays
        overlays.clear()

        if (d.known.isNotEmpty()) overlays.add(knownOverlay(d.known))
        d.clusters.forEach { overlays.add(clusterMarker(it)) }
        if (d.own.isNotEmpty()) overlays.add(ownOverlay(d.own))
        val servingKey = LiveStatus.state.value.servingKey
        d.own.firstOrNull { it.key == servingKey }?.let { overlays.add(servingMarker(it)) }
        d.downgrades.forEach { overlays.add(downgradeMarker(it)) }
        d.tracks.forEach { (cell, points) -> addTrack(cell, points) }
        d.newCells.forEach { overlays.add(newCellMarker(it)) }
        overlays.add(myLocation)

        map.invalidate()
    }

    private fun knownOverlay(cells: List<KnownCell>): SimpleFastPointOverlay {
        val points: List<IGeoPoint> = cells.map { LabelledGeoPoint(it.lat, it.lon, it.key) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_KNOWN
            style = Paint.Style.FILL
        }
        val options = SimpleFastPointOverlayOptions.getDefaultStyle()
            .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
            .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
            .setRadius(4f * density)
            .setCellSize(12)
            .setPointStyle(paint)
            .setIsClickable(true)

        return SimpleFastPointOverlay(SimplePointTheme(points, false), options).apply {
            setOnClickListener { pts, index ->
                val p = pts.get(index) as LabelledGeoPoint
                toast("Känd cell ${p.label}")
            }
        }
    }

    /** Cellerna du själv har hört, på deras uppskattade position. */
    private fun ownOverlay(cells: List<CellEntry>): SimpleFastPointOverlay {
        val points: List<IGeoPoint> = cells.map { LabelledGeoPoint(it.lat, it.lon, it.key) }
        val byKey = cells.associateBy { it.key }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_OWN
            style = Paint.Style.FILL
        }
        val options = SimpleFastPointOverlayOptions.getDefaultStyle()
            .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
            .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
            .setRadius(5f * density)
            .setCellSize(10)
            .setPointStyle(paint)
            .setIsClickable(true)

        return SimpleFastPointOverlay(SimplePointTheme(points, false), options).apply {
            setOnClickListener { pts, index ->
                val key = (pts.get(index) as LabelledGeoPoint).label
                val e = byKey[key]
                toast(if (e == null) key else "$key\n${e.statusLabel} · ${e.count} mätningar · senast ${fmt(e.lastTs)}")
            }
        }
    }

    private fun servingMarker(e: CellEntry) = Marker(map).apply {
        position = GeoPoint(e.lat, e.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_serving)
        title = "Ansluten nu"
        snippet = "${e.key}<br>${e.statusLabel} · ${e.count} mätningar"
    }

    private fun clusterMarker(c: Cluster) = Marker(map).apply {
        position = GeoPoint(c.lat, c.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = clusterIcon(c.count)
        setInfoWindow(null)
        setOnMarkerClickListener { m, mv ->
            mv.controller.animateTo(m.position, mv.zoomLevelDouble + 2.0, 400L)
            true
        }
    }

    private fun clusterIcon(count: Int): BitmapDrawable {
        val label = when {
            count >= 10_000 -> "${count / 1000}k"
            count >= 1_000 -> String.format(Locale.ROOT, "%.1fk", count / 1000.0)
            else -> count.toString()
        }
        val size = ((24 + 7 * log10(count.coerceAtLeast(1).toDouble())) * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size / 2f
        canvas.drawCircle(r, r, r - density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, Color.red(COLOR_KNOWN), Color.green(COLOR_KNOWN), Color.blue(COLOR_KNOWN))
        })
        canvas.drawCircle(r, r, r - density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = Color.WHITE
        })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(label, r, r - (text.descent() + text.ascent()) / 2, text)
        return BitmapDrawable(resources, bmp)
    }

    private fun newCellMarker(s: SeenRow) = Marker(map).apply {
        position = GeoPoint(s.lat, s.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_new_cell)
        title = "Ny cell"
        snippet = "${s.key}<br>Först sedd ${fmt(s.firstTs)}<br>Senast sedd ${fmt(s.lastTs)}" +
            "<br>${s.count} mätningar, starkast ${s.bestDbm ?: "?"} dBm"
    }

    private fun addTrack(cell: SeenRow, points: List<Geo.TrackPoint>) {
        if (points.isEmpty()) return

        val line = Polyline(map).apply {
            setPoints(points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.apply {
                color = COLOR_TRACK
                strokeWidth = 4f * density
                strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(12f * density, 7f * density), 0f)
            }
            title = "Cell som flyttat sig"
            snippet = cell.key
        }
        map.overlays.add(line)

        points.forEachIndexed { i, p ->
            val last = i == points.lastIndex
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(
                    this@MainActivity, if (last) R.drawable.ic_track_now else R.drawable.ic_track_point
                )
                title = fmt(p.ts) + if (last) " (senast)" else ""
                snippet = "${cell.key}<br>Uppskattad position från ${p.samples} mätningar"
            })
        }
    }

    private fun downgradeMarker(f: FlagRow) = Marker(map).apply {
        position = GeoPoint(f.lat, f.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_downgrade)
        title = "Nedgradering ${fmt(f.ts)}"
        snippet = "${f.detail ?: ""}<br>${f.key}"
    }

    // ---- Hjälpare ----

    private fun fmt(ts: Long): String = timeFmt.format(Instant.ofEpochMilli(ts))

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
