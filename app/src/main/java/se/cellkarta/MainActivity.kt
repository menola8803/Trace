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
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
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
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
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

class MainActivity : BaseActivity() {

    companion object {
        const val EXTRA_LAT = "se.cellkarta.LAT"
        const val EXTRA_LON = "se.cellkarta.LON"

        /** Från den här zoomnivån visas enskilda master, annars kluster. */
        private const val DETAIL_ZOOM = 11.0
        private const val MAX_POINTS = 20_000

        private val COLOR_KNOWN = Color.rgb(127, 140, 138)
        private val COLOR_OWN = Color.rgb(95, 196, 180)
        private val COLOR_TRACK = Color.rgb(226, 75, 74)
    }

    private data class Counts(val known: Long = 0, val seen: Long = 0, val anomalies: Int = 0, val events: Int = 0)

    private class MapData(
        val clusters: List<Cluster>,
        val known: List<KnownCell>,
        val own: List<CellEntry>,
        val anomalies: List<CellEntry>,
        val tracks: Map<String, List<Geo.TrackPoint>>,
        val events: List<FlagRow>,
        val counts: Counts,
    )

    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var btnRecord: android.widget.ImageButton
    private var pulse: android.animation.Animator? = null
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var bubble: MapMarks.Bubble
    private val db by lazy { CellDb.get(this) }
    private var refreshJob: Job? = null
    private var counts = Counts()
    private var importing = false
    private var centeredOnUser = false
    private val density by lazy { resources.displayMetrics.density }
    private val timeFmt by lazy {
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss", Lang.locale(this)).withZone(ZoneId.systemDefault())
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                enableMyLocation()
                startMonitor()
            } else {
                toast(getString(R.string.perm_needed))
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
        btnRecord = findViewById(R.id.btnRecord)
        bubble = MapMarks.Bubble(map)

        findViewById<TextView>(R.id.legend).text = legendText()

        applyInsets()
        setupMap()

        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnRecord.setOnClickListener {
            if (LiveStatus.state.value.running) stopMonitor() else requestPermissionsAndStart()
        }
        findViewById<Button>(R.id.btnCells).setOnClickListener {
            startActivity(Intent(this, CellListActivity::class.java))
        }
        findViewById<Button>(R.id.btnMore).setOnClickListener { showMoreMenu(it) }

        // Trace egen startskärm, i samma stil som Direct och Alif. Visas vid varje kallstart.
        if (savedInstanceState == null) {
            val root = findViewById<FrameLayout>(R.id.root)
            root.addView(
                StartScreenView(this) {
                    root.removeView(it)
                    maybeShowStorageInfo()
                }.apply { elevation = 100f * resources.displayMetrics.density },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        } else {
            maybeShowStorageInfo()
        }

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

    /** "Visa på kartan" från listan över celler eller loggen per dag. */
    private fun focusFromIntent(intent: Intent?) {
        if (intent == null) return
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return
        centeredOnUser = true
        map.controller.setZoom(15.0)
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

    // ---- Första start: berätta om mappen i Download ----

    private fun maybeShowStorageInfo() {
        if (Prefs.storageInfoShown(this)) return
        lifecycleScope.launch(Dispatchers.IO) { TraceFiles.ensureFolder(this@MainActivity) }
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_title)
            .setMessage(R.string.storage_message)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ -> Prefs.setStorageInfoShown(this) }
            .show()
    }

    // ---- Layout ----

    /** Teckenförklaring med samma symboler som på kartan. */
    private fun legendText() = buildSpannedString {
        val textPx = (12f * resources.displayMetrics.scaledDensity)
        fun mark(drawable: BitmapDrawable) {
            drawable.setBounds(0, 0, (textPx * 1.5f).toInt(), (textPx * 1.5f).toInt())
            inSpans(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)) { append(" ") }
        }
        mark(dot(COLOR_OWN)); append(" ${getString(R.string.legend_own)}   ")
        mark(dot(COLOR_KNOWN)); append(" ${getString(R.string.legend_known)}\n")
        mark(MapMarks.icon(this@MainActivity, "!", MapMarks.COLOR_MOVING, 22f)); append(" ${getString(R.string.legend_antenna)}   ")
        mark(MapMarks.icon(this@MainActivity, "?", MapMarks.COLOR_NEW, 22f)); append(" ${getString(R.string.legend_event)}")
    }

    private fun dot(color: Int): BitmapDrawable {
        val size = (18 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return BitmapDrawable(resources, bmp)
    }

    private fun applyInsets() {
        val panel = findViewById<View>(R.id.panel)
        val legend = findViewById<View>(R.id.legend)
        val baseBottom = panel.paddingBottom
        val baseTop = (legend.layoutParams as MarginLayoutParams).topMargin
        val baseStart = (legend.layoutParams as MarginLayoutParams).leftMargin
        val gear = findViewById<View>(R.id.btnSettings)
        val record = findViewById<View>(R.id.recordWrap)
        val recordTop = (record.layoutParams as MarginLayoutParams).topMargin
        val gearTop = (gear.layoutParams as MarginLayoutParams).topMargin
        val gearEnd = (gear.layoutParams as MarginLayoutParams).rightMargin
        val scrim = findViewById<View>(R.id.topScrim)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            panel.updatePadding(bottom = baseBottom + bars.bottom)
            legend.updateLayoutParams<MarginLayoutParams> {
                topMargin = baseTop + bars.top
                leftMargin = baseStart + bars.left
            }
            gear.updateLayoutParams<MarginLayoutParams> {
                topMargin = gearTop + bars.top
                rightMargin = gearEnd + bars.right
            }
            record.updateLayoutParams<MarginLayoutParams> {
                topMargin = recordTop + bars.top
                rightMargin = gearEnd + bars.right
            }
            // Kartan börjar under statusraden. Där ovanför syns appens mörka bakgrund,
            // och en tonad övergång leder ner i kartan.
            map.updateLayoutParams<MarginLayoutParams> { topMargin = bars.top }
            scrim.updateLayoutParams<MarginLayoutParams> { topMargin = bars.top }
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
            val latest = try { UpdateChecker.latest(this@MainActivity) } catch (e: Exception) { return@launch }
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
            menu.add(0, 6, 0, R.string.menu_log)
            menu.add(0, 5, 1, R.string.settings)
            menu.add(0, 1, 2, R.string.menu_download)
            menu.add(0, 2, 3, if (hasKey) R.string.menu_key_saved else R.string.menu_key)
            menu.add(0, 3, 4, R.string.menu_import)
            menu.add(0, 4, 5, R.string.menu_export)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    6 -> startActivity(Intent(this@MainActivity, LogActivity::class.java))
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
            .setTitle(R.string.key_title)
            .setMessage(R.string.key_message)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = input.text.toString().trim()
                Prefs.setApiKey(this, key)
                if (key.isEmpty()) toast(getString(R.string.key_removed))
                else {
                    toast(getString(R.string.key_saved))
                    if (thenDownload) downloadSweden()
                }
            }
            .setNeutralButton(R.string.remove) { _, _ ->
                Prefs.setApiKey(this, null)
                toast(getString(R.string.key_removed))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadSweden() {
        val key = Prefs.apiKey(this) ?: return editApiKey(thenDownload = true)
        if (importing) return
        runReferenceJob(R.string.downloaded_n) { progress ->
            OpenCellIdImporter.downloadAndImport(this, key, progress = progress)
        }
    }

    private fun runImport(uri: Uri) {
        if (importing) return
        runReferenceJob(R.string.imported_n) { progress ->
            OpenCellIdImporter.importFile(this, uri, progress)
        }
    }

    private fun runReferenceJob(doneText: Int, job: suspend (suspend (String) -> Unit) -> Long) {
        importing = true
        lifecycleScope.launch {
            status.text = getString(R.string.preparing)
            try {
                val n = job { text -> withContext(Dispatchers.Main) { status.text = text } }
                toast(tr(doneText, n))
            } catch (e: Exception) {
                toast(e.message ?: getString(R.string.error_generic))
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
                toast(getString(R.string.log_saved))
            } catch (e: Exception) {
                toast(tr(R.string.export_failed, e.message))
            }
        }
    }

    // ---- Status ----

    private fun renderStatus(st: LiveStatus.State) {
        showRecording(st.running)
        if (importing) return

        val first = when {
            !st.running -> getString(R.string.status_off)
            st.note != null -> st.note
            st.serving != null -> tr(R.string.status_connected, st.serving)
            else -> getString(R.string.status_searching)
        }
        val lines = mutableListOf(
            first,
            tr(R.string.status_counts, counts.seen, counts.known, counts.anomalies, counts.events)
        )
        when {
            counts.seen == 0L && !st.running -> lines += getString(R.string.hint_press_start)
            counts.seen == 0L -> lines += getString(R.string.hint_waiting)
            counts.known == 0L -> lines += getString(R.string.hint_download)
        }
        status.text = lines.joinToString("\n")
    }

    /** Röd punkt när appen inte mäter, stoppfyrkant med pulserande röd ring när den mäter. */
    private fun showRecording(running: Boolean) {
        btnRecord.setImageResource(if (running) R.drawable.ic_record_stop else R.drawable.ic_record)
        btnRecord.contentDescription = getString(if (running) R.string.stop else R.string.start)
        val ring = findViewById<View>(R.id.recordPulse)
        if (running && pulse == null) {
            val scaleX = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f)
            val scaleY = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f)
            val alpha = android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f)
            pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(ring, scaleX, scaleY, alpha).apply {
                duration = 1400L
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else if (!running && pulse != null) {
            pulse?.cancel()
            pulse = null
            ring.alpha = 0f
        }
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
        val anomalies = db.anomalies()
        val tracks = anomalies.filter { it.moving }
            .associate { it.key to Geo.track(db.observations(it.key, 0L, 5000)) }
        val events = db.allFlags(400)

        return MapData(
            clusters, known, own, anomalies, tracks, events,
            Counts(db.knownCount(), db.seenCount(), anomalies.size, events.size)
        )
    }

    private fun draw(d: MapData) {
        // Kartan ritas om ofta. Är en förklaringsruta öppen öppnas den igen på samma markering.
        val keepOpen = bubble.openKey
        InfoWindow.closeAllInfoWindowsOn(map)
        val overlays = map.overlays
        overlays.clear()
        overlays.add(tapOutside)

        if (d.known.isNotEmpty()) overlays.add(knownOverlay(d.known))
        d.clusters.forEach { overlays.add(clusterMarker(it)) }
        // Din position ritas under markeringarna. Annars döljer figuren cellerna du hört där du står.
        overlays.add(myLocation)
        if (d.own.isNotEmpty()) overlays.add(ownOverlay(d.own))
        val servingKey = LiveStatus.state.value.servingKey
        d.own.firstOrNull { it.key == servingKey }?.let { overlays.add(servingMarker(it)) }
        d.tracks.forEach { (key, points) -> addTrack(key, points) }
        // Frågetecken där det hände, sedan utropstecken på antennerna överst.
        d.events.forEach { overlays.add(eventMarker(it)) }
        val flagsByKey = d.events.groupBy { it.key }
        d.anomalies.forEach {
            overlays.add(antennaMarker(it, d.tracks[it.key]?.lastOrNull(), flagsByKey[it.key].orEmpty().sortedBy { f -> f.ts }))
        }

        if (keepOpen != null) {
            overlays.filterIsInstance<Marker>().firstOrNull { it.relatedObject == keepOpen }?.showInfoWindow()
                ?: run { bubble.openKey = null }
        }
        map.invalidate()
    }

    /** Ett tryck bredvid en förklaringsruta, på själva kartan, stänger den. */
    private val tapOutside by lazy {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (bubble.openKey == null && !bubble.isOpen) return false
                bubble.openKey = null
                InfoWindow.closeAllInfoWindowsOn(map)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
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
                toast(tr(R.string.known_cell, Texts.cellLabel(p.label)))
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
                toast(
                    if (e == null) key
                    else tr(R.string.own_cell_toast, Texts.cellLabel(key), e.statusLabel(this@MainActivity), e.count, fmt(e.lastTs))
                )
            }
        }
    }

    private fun servingMarker(e: CellEntry) = Marker(map).apply {
        position = GeoPoint(e.lat, e.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_serving)
        relatedObject = "serving"
        title = getString(R.string.connected_now)
        snippet = listOf(
            Texts.row(getString(R.string.row_cell), Texts.cellLabel(e.key)),
            Texts.row(getString(R.string.row_best), e.bestDbm?.let { "$it dBm" } ?: getString(R.string.unknown_value)),
            Texts.row(getString(R.string.row_count), e.count.toString()),
        ).joinToString("<br>")
        setInfoWindow(bubble)
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

    /**
     * Utropstecken med ring på en onormal antenn. Ringens färg visar den viktigaste orsaken:
     * röd rör sig, rosa onormal styrka, grön kortvarig, guld ny. En cell som rör sig visas där den hördes senast.
     */
    private fun antennaMarker(e: CellEntry, lastTrackPoint: Geo.TrackPoint?, events: List<FlagRow>) = Marker(map).apply {
        position = if (lastTrackPoint != null) GeoPoint(lastTrackPoint.lat, lastTrackPoint.lon) else GeoPoint(e.lat, e.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        relatedObject = "antenna:${e.key}"
        val main = e.reasonTypes.firstOrNull() ?: CellDb.FLAG_NEW
        icon = MapMarks.icon(this@MainActivity, "!", MapMarks.colorFor(main), 34f)
        title = "! " + e.reasons(this@MainActivity).joinToString(" · ")

        val ctx = this@MainActivity
        snippet = buildList {
            e.reasonTypes.forEach { add(Texts.explanation(ctx, it)) }
            add("")
            add(Texts.row(getString(R.string.row_cell), Texts.cellLabel(e.key)))
            add(Texts.row(getString(R.string.row_first), fmt(e.firstTs)))
            add(Texts.row(getString(R.string.row_last), fmt(e.lastTs)))
            add(Texts.row(getString(R.string.row_best), e.bestDbm?.let { "$it dBm" } ?: getString(R.string.unknown_value)))
            add(Texts.row(getString(R.string.row_count), e.count.toString()))
            events.forEach { f ->
                val detail = Texts.detailText(ctx, f.type, f.detail)
                if (detail.isNotBlank()) add(Texts.row(Texts.reasonName(ctx, f.type), "$detail (${fmt(f.ts)})"))
            }
            add("")
            add("<i>${getString(R.string.expl_not_proof)}</i>")
        }.joinToString("<br>")
        setInfoWindow(bubble)
    }

    /** Frågetecken med ring där avvikelsen upptäcktes, det vill säga där du var. */
    private fun eventMarker(f: FlagRow) = Marker(map).apply {
        position = GeoPoint(f.lat, f.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        relatedObject = "event:${f.key}:${f.type}:${f.ts}"
        icon = MapMarks.icon(this@MainActivity, "?", MapMarks.colorFor(f.type), 28f)
        val ctx = this@MainActivity
        title = "? " + Texts.eventTitle(ctx, f.type)
        snippet = buildList {
            add(Texts.explanation(ctx, f.type))
            add("")
            add(Texts.row(getString(R.string.row_time), fmt(f.ts)))
            Texts.dbm(f.detail)?.let { add(Texts.row(getString(R.string.row_strength), "$it dBm")) }
            Texts.detailText(ctx, f.type, f.detail).takeIf { it.isNotBlank() }
                ?.let { add(Texts.row(getString(R.string.row_detail), it)) }
            add(Texts.row(getString(R.string.row_cell), Texts.cellLabel(f.key)))
            add(Texts.row(getString(R.string.row_position), String.format(Locale.ROOT, "%.5f, %.5f", f.lat, f.lon)))
        }.joinToString("<br>")
        setInfoWindow(bubble)
    }

    private fun addTrack(key: String, points: List<Geo.TrackPoint>) {
        if (points.isEmpty()) return

        val line = Polyline(map).apply {
            setPoints(points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.apply {
                color = COLOR_TRACK
                strokeWidth = 4f * density
                strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(12f * density, 7f * density), 0f)
            }
            title = getString(R.string.track_title)
            snippet = Texts.cellLabel(key)
            setInfoWindow(bubble)
        }
        map.overlays.add(line)

        // Den sista punkten får antennens utropstecken.
        points.dropLast(1).forEach { p ->
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                relatedObject = "track:$key:${p.ts}"
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_track_point)
                title = fmt(p.ts)
                snippet = Texts.cellLabel(key) + "<br>" + tr(R.string.track_point, p.samples)
                setInfoWindow(bubble)
            })
        }
    }

    // ---- Hjälpare ----

    private fun fmt(ts: Long): String = timeFmt.format(Instant.ofEpochMilli(ts))

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
