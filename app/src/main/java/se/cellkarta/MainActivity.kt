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
import org.osmdroid.views.overlay.Polygon
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
        const val EXTRA_REPLAY_DAY = "se.cellkarta.REPLAY_DAY"

        /** Från den här zoomnivån visas enskilda master, annars kluster. */
        private const val DETAIL_ZOOM = 11.0
        private const val MAX_POINTS = 20_000
        private const val OWN_MARKER_LIMIT = 400

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
        val masts: List<Pair<UserMast, MastGuess>>,
        val mastInfo: Map<String, MastInfo>,
        val found: List<Triangulate.Result>,
        val counts: Counts,
    )

    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var btnRecord: android.widget.ImageButton
    private var pulse: android.animation.Animator? = null
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var bubble: MapMarks.Bubble
    private lateinit var replay: Replay
    private val replayBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = replay.close()
    }
    private val pickerBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hidePicker()
    }
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
        // Knappen Beräkna mast i cellernas rutor, och Beräkna igen i den beräknade mastens ruta
        bubble.actionLabel = { m ->
            val tag = m.relatedObject as? String
            when {
                Prefs.triMode(this) == Prefs.TRI_OFF || tag == null -> null
                tag.startsWith("tri:") -> getString(R.string.tri_again)
                cellKeyOf(tag)?.let { Triangulate.siteKeyOf(it) } != null -> getString(R.string.tri_locate)
                else -> null
            }
        }
        bubble.onAction = { m ->
            val tag = m.relatedObject as? String
            val siteKey = when {
                tag == null -> null
                tag.startsWith("tri:") -> tag.removePrefix("tri:")
                else -> cellKeyOf(tag)?.let { Triangulate.siteKeyOf(it) }
            }
            if (siteKey != null) locateMast(siteKey)
        }

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
        findViewById<View>(R.id.btnEvents).setOnClickListener { showEvents() }

        replay = Replay(this, map, db, bubble, findViewById(R.id.replayPanel), ::eventMarker, tapOutside) { backToLive() }
        findViewById<View>(R.id.btnReplayDay).setOnClickListener { chooseReplayDay() }
        findViewById<View>(R.id.btnReplay).setOnClickListener { chooseReplayDay() }
        findViewById<View>(R.id.btnBackLive).setOnClickListener { replay.close() }
        // Under uppspelning slutar kartan ovanför panelen, så att positionen alltid syns.
        findViewById<View>(R.id.replayPanel).addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val margin = if (v.visibility == View.VISIBLE) bottom - top else 0
            val lp = map.layoutParams as MarginLayoutParams
            if (lp.bottomMargin != margin) map.post { map.updateLayoutParams<MarginLayoutParams> { bottomMargin = margin } }
        }
        onBackPressedDispatcher.addCallback(this, replayBack)
        onBackPressedDispatcher.addCallback(this, pickerBack)
        findViewById<View>(R.id.pickerScrim).setOnClickListener { hidePicker() }
        findViewById<View>(R.id.pickerClose).setOnClickListener { hidePicker() }

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
        intent.getStringExtra(EXTRA_REPLAY_DAY)?.let { day ->
            intent.removeExtra(EXTRA_REPLAY_DAY)
            map.post { startReplay(day) }
            return
        }
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
        findViewById<TextView>(R.id.legend).text = legendText()
        map.onResume()
        enableMyLocation()
    }

    override fun onPause() {
        replay.pause()
        myLocation.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    // ---- Första start: berätta om mappen i Download ----

    private fun maybeShowStorageInfo() {
        if (Prefs.storageInfoShown(this)) return maybeShowMastTip()
        lifecycleScope.launch(Dispatchers.IO) { TraceFiles.ensureFolder(this@MainActivity) }
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_title)
            .setMessage(R.string.storage_message)
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { _, _ ->
                Prefs.setStorageInfoShown(this)
                maybeShowMastTip()
            }
            .show()
    }

    private fun maybeShowMastTip() {
        if (Prefs.mastTipShown(this)) return
        Prefs.setMastTipShown(this)
        toast(getString(R.string.mast_tip))
    }

    // ---- Layout ----

    /** Teckenförklaring med samma symboler som på kartan. */
    private fun legendText() = buildSpannedString {
        val textPx = (12f * resources.displayMetrics.scaledDensity)
        fun mark(drawable: BitmapDrawable) {
            drawable.setBounds(0, 0, (textPx * 1.5f).toInt(), (textPx * 1.5f).toInt())
            inSpans(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)) { append(" ") }
        }
        Net.entries.forEach { mark(MapMarks.netDot(this@MainActivity, it, 18f)) }
        append(" ${getString(R.string.legend_own)}   ")
        mark(dot(COLOR_KNOWN)); append(" ${getString(R.string.legend_known)}   ")
        mark(MapMarks.mastIcon(this@MainActivity, 24f)); append(" ${getString(R.string.legend_mast)}")
        if (Prefs.triMode(this@MainActivity) != Prefs.TRI_OFF) {
            append("   ")
            mark(MapMarks.mastIcon(this@MainActivity, 24f, MapMarks.COLOR_TRI)); append(" ${getString(R.string.legend_tri)}")
        }
        append("\n")
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
        val replayBtn = findViewById<View>(R.id.btnReplay)
        val replayTop = (replayBtn.layoutParams as MarginLayoutParams).topMargin
        val live = findViewById<View>(R.id.btnBackLive)
        val events = findViewById<View>(R.id.eventWrap)
        val eventsTop = (events.layoutParams as MarginLayoutParams).topMargin
        val pickerScrim = findViewById<View>(R.id.pickerScrim)
        val pickerTop = pickerScrim.paddingTop
        val pickerList = findViewById<View>(R.id.pickerList)
        val pickerBottom = pickerList.paddingBottom
        val replayPanel = findViewById<View>(R.id.replayPanel)
        val replayBottom = replayPanel.paddingBottom
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
            replayBtn.updateLayoutParams<MarginLayoutParams> {
                topMargin = replayTop + bars.top
                rightMargin = gearEnd + bars.right
            }
            live.updateLayoutParams<MarginLayoutParams> {
                topMargin = baseTop + bars.top
                leftMargin = baseStart + bars.left
            }
            events.updateLayoutParams<MarginLayoutParams> {
                topMargin = eventsTop + bars.top
                rightMargin = gearEnd + bars.right
            }
            replayPanel.updatePadding(bottom = replayBottom + bars.bottom)
            pickerScrim.updatePadding(top = pickerTop + bars.top)
            pickerList.updatePadding(bottom = pickerBottom + bars.bottom)
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
        Schedule.setStartedBySchedule(this, false)
        ContextCompat.startForegroundService(this, Intent(this, CellMonitorService::class.java))
    }

    private fun stopMonitor() {
        Schedule.setStartedBySchedule(this, false)
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
            menu.add(0, 7, 0, R.string.menu_replay)
            menu.add(0, 6, 0, R.string.menu_log)
            if (Prefs.appTraffic(this@MainActivity)) menu.add(0, 8, 0, R.string.menu_traffic)
            menu.add(0, 5, 1, R.string.settings)
            menu.add(0, 1, 2, R.string.menu_download)
            menu.add(0, 2, 3, if (hasKey) R.string.menu_key_saved else R.string.menu_key)
            menu.add(0, 3, 4, R.string.menu_import)
            menu.add(0, 4, 5, R.string.menu_export)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    7 -> chooseReplayDay()
                    8 -> startActivity(Intent(this@MainActivity, AppTrafficActivity::class.java))
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
            st.serving != null -> tr(R.string.status_connected, st.serving) +
                (st.display?.let { "\n" + tr(R.string.net_line, it) } ?: "")
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
        // Felsökning medan inspelningen pågår: hur många avläsningar som faktiskt sparas
        if (st.running) lines += tr(R.string.status_diag, st.asked, st.answered, st.saved) +
            (st.problem?.let { " · $it" } ?: "")
        status.text = lines.joinToString("\n")
    }

    // ---- Uppspelning ----

    /** Listan som öppnas från replay-knappen: ett kort per dag med dagens händelser. */
    private fun chooseReplayDay() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                db.days().map { d ->
                    val from = java.time.LocalDate.parse(d.day).atStartOfDay(zone).toInstant().toEpochMilli()
                    d to db.flagsBetween(from, from + 86_400_000L)
                }
            }
            if (data.isEmpty()) return@launch toast(getString(R.string.replay_no_days))
            showReplayPicker(data)
        }
    }

    private fun showReplayPicker(data: List<Pair<DaySummary, List<FlagRow>>>) {
        val loc = Lang.locale(this)
        val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", loc)
        val hm = DateTimeFormatter.ofPattern("HH:mm", loc).withZone(ZoneId.systemDefault())
        val hms = DateTimeFormatter.ofPattern("HH:mm:ss", loc).withZone(ZoneId.systemDefault())
        val d = density

        fun label(text: String, color: Int, sp: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(ContextCompat.getColor(this@MainActivity, color))
            if (bold) typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MainActivity, R.font.jakarta_bold)
            setLineSpacing(2f * d, 1f)
        }

        val list = findViewById<android.widget.LinearLayout>(R.id.pickerList)
        list.removeAllViews()

        data.forEach { (day, events) ->
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
                val p = (14 * d).toInt()
                setPadding(p, p, p, (8 * d).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
            }
            list.addView(card)
            card.addView(label(dayFmt.format(java.time.LocalDate.parse(day.day)).replaceFirstChar { it.titlecase(loc) },
                R.color.ink, 15f, bold = true))
            card.addView(label(tr(R.string.log_day_summary, day.measurements, day.cells,
                hm.format(Instant.ofEpochMilli(day.firstTs)), hm.format(Instant.ofEpochMilli(day.lastTs))),
                R.color.muted, 12.5f))

            card.addView(label(getString(R.string.replay_whole_day), R.color.mint, 14f, bold = true).apply {
                minHeight = (44 * d).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
                setOnClickListener { hidePicker(); startReplay(day.day) }
            })

            events.forEach { f ->
                card.addView(android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = (48 * d).toInt()
                    background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
                    addView(android.widget.ImageView(this@MainActivity).apply {
                        setImageDrawable(MapMarks.icon(this@MainActivity, "?", MapMarks.colorFor(f.type), 28f))
                        layoutParams = android.widget.LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
                    })
                    addView(android.widget.LinearLayout(this@MainActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (10 * d).toInt() }
                        addView(label("${hms.format(Instant.ofEpochMilli(f.ts))} · ${Texts.reasonName(this@MainActivity, f.type)}",
                            R.color.ink, 13f, bold = true))
                        val detail = Texts.detailText(this@MainActivity, f.type, f.detail)
                        addView(label(listOf(detail, Texts.cellLabel(f.key)).filter { it.isNotBlank() }.joinToString(" · "),
                            R.color.muted, 12f))
                    })
                    addView(label("▶", R.color.gold, 16f).apply { setPadding((8 * d).toInt(), 0, 0, 0) })
                    setOnClickListener { hidePicker(); startReplay(day.day, f.ts) }
                })
            }
        }

        findViewById<android.widget.ScrollView>(R.id.pickerScroll).scrollTo(0, 0)
        val scrim = findViewById<View>(R.id.pickerScrim)
        val sheet = findViewById<View>(R.id.pickerSheet)
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180L).start()
        sheet.translationY = 400f * d
        sheet.animate().translationY(0f).setDuration(260L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        pickerBack.isEnabled = true
    }

    private fun hidePicker() {
        val scrim = findViewById<View>(R.id.pickerScrim)
        if (scrim.visibility != View.VISIBLE) return
        pickerBack.isEnabled = false
        scrim.animate().alpha(0f).setDuration(160L).withEndAction { scrim.visibility = View.GONE }.start()
    }

    // Kartläget innan uppspelningen, så att Live-kartan kan återställas exakt.
    private var savedZoom = 0.0
    private var savedCenter: GeoPoint? = null

    private fun startReplay(day: String, startAt: Long? = null) {
        refreshJob?.cancel()
        if (!replay.active) {
            savedZoom = map.zoomLevelDouble
            savedCenter = GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
        }
        showReplayMode(true)
        replayBack.isEnabled = true
        replay.open(day, startAt)
    }

    /** Under uppspelning: Live-knappen ersätter teckenförklaringen och panelen nertill göms. */
    private fun showReplayMode(on: Boolean) {
        val live = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.panel).visibility = live
        findViewById<View>(R.id.legend).visibility = live
        findViewById<View>(R.id.btnReplay).visibility = live
        findViewById<View>(R.id.btnBackLive).visibility = if (on) View.VISIBLE else View.GONE
        if (on) findViewById<View>(R.id.eventWrap).visibility = View.GONE
    }

    /** Tillbaka till Live-kartan: samma utsnitt och zoom som före uppspelningen. */
    private fun backToLive() {
        replayBack.isEnabled = false
        map.updateLayoutParams<MarginLayoutParams> { bottomMargin = 0 }
        showReplayMode(false)
        savedCenter?.let {
            map.controller.setZoom(savedZoom)
            map.controller.setCenter(it)
        }
        savedCenter = null
        scheduleRefresh(0)
    }

    // ---- Händelseknappen ----

    private var latestEvents: List<FlagRow> = emptyList()

    /** Knappen syns när något har hänt. Siffran visar händelser sedan du tittade senast. */
    private fun updateEventButton(events: List<FlagRow>) {
        latestEvents = events
        val wrap = findViewById<View>(R.id.eventWrap)
        val badge = findViewById<TextView>(R.id.eventBadge)
        if (events.isEmpty()) {
            wrap.visibility = View.GONE
            return
        }
        val seen = Prefs.eventsSeen(this)
        val unseen = events.count { it.ts > seen }
        wrap.visibility = View.VISIBLE
        findViewById<android.widget.ImageButton>(R.id.btnEvents).setImageDrawable(
            MapMarks.icon(this, "!", if (unseen > 0) MapMarks.COLOR_MOVING else MapMarks.COLOR_NEW, 34f)
        )
        badge.visibility = if (unseen > 0) View.VISIBLE else View.GONE
        badge.text = if (unseen > 99) "99+" else unseen.toString()
    }

    private fun showEvents() {
        val events = latestEvents
        if (events.isEmpty()) return toast(getString(R.string.events_none))
        val seen = Prefs.eventsSeen(this)
        val unseen = events.count { it.ts > seen }
        val labels = events.map { f ->
            val mark = if (f.ts > seen) "● " else ""
            mark + fmt(f.ts) + "\n" + Texts.reasonName(this, f.type) + " · " + Texts.cellLabel(f.key)
        }.toTypedArray()
        Prefs.setEventsSeen(this, System.currentTimeMillis())
        updateEventButton(events)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.events_title) + if (unseen > 0) " · " + tr(R.string.events_new, unseen) else "")
            .setItems(labels) { _, i ->
                val f = events[i]
                // Visa händelsen på kartan med sin förklaringsruta öppen
                bubble.openKey = "event:${f.key}:${f.type}:${f.ts}"
                centeredOnUser = true
                map.controller.setZoom(17.0)
                map.controller.setCenter(GeoPoint(f.lat, f.lon))
                scheduleRefresh(0)
            }
            .setNegativeButton(R.string.close, null)
            .show()
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
            if (map.width == 0 || map.height == 0 || replay.active) return@launch
            val box = map.boundingBox
            val zoom = map.zoomLevelDouble
            val data = withContext(Dispatchers.IO) { loadMapData(box, zoom) }
            if (replay.active) return@launch
            draw(data)
            updateEventButton(data.events)
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
        val masts = db.masts().map { it to MastGuess.of(db, it.lat, it.lon) }
        // Mastinformation för cellerna som får en ruta, om det är påslaget i Settings
        val mastInfo = if (!Prefs.mastDetails(this)) emptyMap() else
            (if (own.size <= OWN_MARKER_LIMIT) own.map { it.key } else emptyList())
                .plus(anomalies.map { it.key })
                .distinct()
                .mapNotNull { k -> MastInfo.of(db, k)?.let { k to it } }
                .toMap()

        // Hittade master: sparade, så de finns kvar när appen startas om
        val found = if (Prefs.triMode(this) == Prefs.TRI_OFF) emptyList() else db.foundInBox(s, w, n, e)

        return MapData(
            clusters, known, own, anomalies, tracks, events, masts, mastInfo, found,
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
        // Dina celler med nättypens siffra. Är de väldigt många ritas de som enkla prickar.
        if (d.own.size in 1..OWN_MARKER_LIMIT) d.own.forEach { overlays.add(ownMarker(it, d.mastInfo[it.key])) }
        else if (d.own.isNotEmpty()) overlays.add(ownOverlay(d.own))
        val servingKey = LiveStatus.state.value.servingKey
        d.own.firstOrNull { it.key == servingKey }?.let { overlays.add(servingMarker(it)) }
        // Beräknade master: osäkerhetscirkel, avståndsringar, sektorpilar och masten
        d.found.forEach { addTriangulation(it, showRings = keepOpen == "tri:${it.siteKey}") }
        d.masts.forEach { (m, g) -> overlays.add(mastMarker(m, g)) }
        d.tracks.forEach { (key, points) -> addTrack(key, points) }
        // Frågetecken där det hände, sedan utropstecken på antennerna överst.
        d.events.forEach { overlays.add(eventMarker(it)) }
        val flagsByKey = d.events.groupBy { it.key }
        d.anomalies.forEach {
            overlays.add(antennaMarker(it, d.tracks[it.key]?.lastOrNull(), flagsByKey[it.key].orEmpty().sortedBy { f -> f.ts },
                d.mastInfo[it.key]))
        }

        if (keepOpen != null) {
            overlays.filterIsInstance<Marker>().firstOrNull { it.relatedObject == keepOpen }?.showInfoWindow()
                ?: run { bubble.openKey = null }
        }
        map.invalidate()
    }

    /** Ett tryck bredvid en förklaringsruta, på själva kartan, stänger den. */
    // ---- Triangulering ----

    private var lastTriAt = 0L

    /** "own:KEY", "antenna:KEY" och "serving:KEY" bär en cellnyckel. */
    private fun cellKeyOf(tag: String): String? =
        if (tag.startsWith("own:") || tag.startsWith("antenna:") || tag.startsWith("serving:")) tag.substringAfter(':') else null

    /** Beräkna en mast vid behov, när man trycker Beräkna mast i en ruta. */
    private fun locateMast(siteKey: String) {
        toast(getString(R.string.tri_calculating))
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) { runCatching { Triangulate.forSite(db, siteKey) } }
            outcome.exceptionOrNull()?.let { e ->
                return@launch toast(tr(R.string.tri_failed, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")))
            }
            val r = outcome.getOrNull() ?: return@launch toast(getString(R.string.tri_not_enough))
            // Säg direkt vad resultatet blev, och varför det är osäkert om det är det
            toast(
                if (r.places < 3) tr(R.string.tri_done_few, r.places)
                else tr(R.string.tri_done, qualityLabel(r.quality), Triangulate.round(r.radiusM))
            )
            withContext(Dispatchers.IO) { db.saveFound(r) }
            bubble.openKey = "tri:$siteKey"
            map.controller.animateTo(GeoPoint(r.lat, r.lon))
            scheduleRefresh(0)
        }
    }

    private fun qualityLabel(q: Triangulate.Quality) = getString(when (q) {
        Triangulate.Quality.INSUFFICIENT -> R.string.q_insufficient
        Triangulate.Quality.ROUGH -> R.string.q_rough
        Triangulate.Quality.GOOD -> R.string.q_good
        Triangulate.Quality.SECURE -> R.string.q_secure
    })

    /** En hittad mast. Avståndsringarna ritas bara när mastens ruta är öppen, annars blir kartan full. */
    private fun addTriangulation(r: Triangulate.Result, showRings: Boolean) {
        val center = GeoPoint(r.lat, r.lon)
        val gold = ContextCompat.getColor(this, R.color.gold)
        val tri = MapMarks.COLOR_TRI

        // Osäkerhetscirkel: bara en kant, ingen fyllning, så att kartan behåller sina färger.
        // Heldragen när positionen är säker, streckad när den är grov.
        map.overlays.add(Polygon(map).apply {
            points = Polygon.pointsAsCircle(center, r.radiusM)
            fillPaint.color = Color.TRANSPARENT
            outlinePaint.color = Color.argb(if (r.radiusM <= 150) 230 else 170, Color.red(tri), Color.green(tri), Color.blue(tri))
            outlinePaint.strokeWidth = (if (r.radiusM <= 150) 2.5f else 1.5f) * density
            if (r.radiusM > 150) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
            setInfoWindow(null)
            setOnClickListener { _, _, _ -> false }
        })

        // Avståndsringar från Timing Advance, runt mätpunkterna
        if (showRings) r.rings.forEach { ring ->
            map.overlays.add(Polygon(map).apply {
                points = Polygon.pointsAsCircle(GeoPoint(ring.lat, ring.lon), ring.radiusM)
                fillPaint.color = Color.TRANSPARENT
                outlinePaint.color = Color.argb(150, Color.red(gold), Color.green(gold), Color.blue(gold))
                outlinePaint.strokeWidth = 1.5f * density
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
                setInfoWindow(null)
                setOnClickListener { _, _, _ -> false }
            })
        }

        // Sektorernas riktning: pilar från masten
        val len = maxOf(60.0, minOf(200.0, r.radiusM * 1.5))
        r.sectors.forEach { s ->
            val tip = center.destinationPoint(len, s.bearingDeg)
            val left = tip.destinationPoint(len * 0.18, s.bearingDeg + 150)
            val right = tip.destinationPoint(len * 0.18, s.bearingDeg - 150)
            map.overlays.add(Polyline(map).apply {
                setPoints(listOf(center, tip, left, tip, right))
                outlinePaint.color = tri
                outlinePaint.strokeWidth = 3f * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                setInfoWindow(null)
            })
        }

        // Andra möjliga platsen: mätningarna ligger nästan på en linje, masten kan stå på andra sidan
        if (r.altLat != null && r.altLon != null) {
            val alt = GeoPoint(r.altLat, r.altLon)
            map.overlays.add(Polyline(map).apply {
                setPoints(listOf(center, alt))
                outlinePaint.color = Color.argb(140, Color.red(tri), Color.green(tri), Color.blue(tri))
                outlinePaint.strokeWidth = 1.5f * density
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 6f * density), 0f)
                setInfoWindow(null)
            })
            map.overlays.add(Polygon(map).apply {
                points = Polygon.pointsAsCircle(alt, r.radiusM)
                fillPaint.color = Color.TRANSPARENT
                outlinePaint.color = Color.argb(150, Color.red(tri), Color.green(tri), Color.blue(tri))
                outlinePaint.strokeWidth = 1.5f * density
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
                setInfoWindow(null)
                setOnClickListener { _, _, _ -> false }
            })
            map.overlays.add(Marker(map).apply {
                position = alt
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = MapMarks.mastIcon(this@MainActivity, 32f, tri).apply { alpha = 150 }
                relatedObject = "trialt:${r.siteKey}"
                title = getString(R.string.tri_alt_title)
                snippet = listOf(
                    getString(R.string.tri_alt_text),
                    "",
                    Texts.row(getString(R.string.tri_advice),
                        r.adviceOctant?.let { tr(R.string.tri_alt_advice, resources.getStringArray(R.array.compass)[it]) } ?: ""),
                    Texts.row(getString(R.string.row_position), String.format(Locale.ROOT, "%.5f, %.5f", r.altLat, r.altLon)),
                ).joinToString("<br>")
                setInfoWindow(bubble)
            })
        }

        // Masten
        map.overlays.add(Marker(map).apply {
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = MapMarks.mastIcon(this@MainActivity, 40f, tri)
            relatedObject = "tri:${r.siteKey}"
            title = tr(R.string.tri_title_op, r.operator)
            snippet = (triRows(r) + "<i>${getString(R.string.tri_tap_again)}</i>").joinToString("<br>")
            setInfoWindow(bubble)
            // Tryck en gång: rutan. Tryck igen: fråga om den ska tas bort.
            setOnMarkerClickListener { marker, _ ->
                if (bubble.isOpen && bubble.openKey == marker.relatedObject) confirmRemoveTri(r.siteKey)
                else {
                    marker.showInfoWindow()
                    // Rita om med mastens avståndsringar
                    scheduleRefresh(0)
                }
                true
            }
        })
    }

    private fun confirmRemoveTri(siteKey: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.tri_remove_title)
            .setMessage(R.string.tri_remove_msg)
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { db.deleteFound(siteKey); LiveStatus.bump() }
                bubble.openKey = null
                InfoWindow.closeAllInfoWindowsOn(map)
                scheduleRefresh(0)
            }
            .setNeutralButton(R.string.tri_remove_all) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { db.clearFound(); LiveStatus.bump() }
                bubble.openKey = null
                InfoWindow.closeAllInfoWindowsOn(map)
                scheduleRefresh(0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun triRows(r: Triangulate.Result): List<String> = buildList {
        add(Texts.row(getString(R.string.tri_quality), qualityLabel(r.quality)))
        add(Texts.row(getString(R.string.tri_uncertainty), tr(R.string.tri_unc_value, Triangulate.round(r.radiusM))))
        if (r.ambiguous) add(Texts.row(getString(R.string.tri_alt_row), getString(R.string.tri_alt_value)))
        add(Texts.row(getString(R.string.tri_measurements), tr(R.string.tri_meas_value, r.points, r.places)))
        add(Texts.row(getString(R.string.tri_ta),
            if (r.taPoints > 0) tr(R.string.tri_ta_value, r.taPoints) else getString(R.string.tri_ta_none)))
        if (r.sectors.isNotEmpty()) add(Texts.row(getString(R.string.tri_sectors),
            r.sectors.joinToString(", ") { tr(R.string.tri_sector_value, "${it.sector ?: "–"}", Triangulate.compass(this@MainActivity, it.bearingDeg)) }))
        add(Texts.row(getString(R.string.tri_ocid),
            r.ocidDistM?.let { tr(R.string.tri_ocid_value, Triangulate.round(it)) } ?: getString(R.string.tri_ocid_none)))
        add(Texts.row(getString(R.string.tri_advice),
            r.adviceOctant?.let {
                tr(if (r.ambiguous) R.string.tri_alt_advice else R.string.tri_advice_value, resources.getStringArray(R.array.compass)[it])
            } ?: getString(R.string.tri_advice_done)))
        add(Texts.row(getString(R.string.row_mast_id), "${r.radio} ${r.site}"))
        add(Texts.row(getString(R.string.row_position), String.format(Locale.ROOT, "%.5f, %.5f", r.lat, r.lon)))
        add("")
        add("<i>${getString(R.string.tri_note)}</i>")
    }

    private val tapOutside by lazy {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (bubble.openKey == null && !bubble.isOpen) return false
                bubble.openKey = null
                InfoWindow.closeAllInfoWindowsOn(map)
                return true
            }

            /** Håll fingret på kartan för att markera en mast där. */
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                map.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                lifecycleScope.launch {
                    val id = withContext(Dispatchers.IO) { db.addMast(p.latitude, p.longitude, System.currentTimeMillis()) }
                    toast(getString(R.string.mast_added))
                    bubble.openKey = "mast:$id"
                    scheduleRefresh(0)
                }
                return true
            }
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

    /** En av dina celler: prick med 2, 3, 4 eller 5 och en ruta med detaljer. */
    private fun ownMarker(e: CellEntry, info: MastInfo?) = Marker(map).apply {
        position = GeoPoint(e.lat, e.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        val net = Net.of(e.key)
        icon = MapMarks.netDot(this@MainActivity, net)
        relatedObject = "own:${e.key}"
        title = Net.label(this@MainActivity, net) + " · " + (Operators.fromKey(e.key) ?: "")
        snippet = listOf(
            Texts.row(getString(R.string.row_cell), Texts.cellLabel(e.key)),
            Texts.row(getString(R.string.row_status), e.statusLabel(this@MainActivity)),
            Texts.row(getString(R.string.row_count), e.count.toString()),
            Texts.row(getString(R.string.row_best), e.bestDbm?.let { "$it dBm" } ?: getString(R.string.unknown_value)),
            Texts.row(getString(R.string.row_first), fmt(e.firstTs)),
            Texts.row(getString(R.string.row_last), fmt(e.lastTs)),
        ).plus(mastRows(info, e.lat, e.lon)).joinToString("<br>")
        subDescription = info?.cellMapperUrl(e.lat, e.lon)
        setInfoWindow(bubble)
    }

    /** Mastinformationen som extra rader i rutan, med en tom rad före. */
    private fun mastRows(info: MastInfo?, lat: Double, lon: Double): List<String> =
        if (info == null) emptyList() else listOf("") + info.rows(this, lat, lon)

    private fun servingMarker(e: CellEntry) = Marker(map).apply {
        position = GeoPoint(e.lat, e.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_serving)
        relatedObject = "serving:${e.key}"
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
    private fun antennaMarker(
        e: CellEntry, lastTrackPoint: Geo.TrackPoint?, events: List<FlagRow>, info: MastInfo?,
    ) = Marker(map).apply {
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
            addAll(mastRows(info, e.lat, e.lon))
            add("")
            add("<i>${getString(R.string.expl_not_proof)}</i>")
        }.joinToString("<br>")
        subDescription = info?.cellMapperUrl(e.lat, e.lon)
        setInfoWindow(bubble)
    }

    /**
     * Mast som du har markerat. Ett tryck visar rutan med trolig operatör, ett tryck till
     * frågar om markeringen ska tas bort.
     */
    private fun mastMarker(m: UserMast, g: MastGuess) = Marker(map).apply {
        position = GeoPoint(m.lat, m.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = MapMarks.mastIcon(this@MainActivity)
        relatedObject = "mast:${m.id}"
        title = if (g.operator != null) tr(R.string.mast_title_op, g.operator) else getString(R.string.mast_title)
        snippet = buildList {
            add(Texts.row(getString(R.string.mast_operator), g.operator ?: getString(R.string.mast_unknown)))
            if (g.siteId != null) {
                add(Texts.row(getString(R.string.mast_own),
                    tr(R.string.mast_own_value, g.operator ?: "?", g.siteRadio ?: "", g.siteId, g.bestDbm?.toString() ?: "?")))
                add(Texts.row(getString(R.string.mast_sectors),
                    g.sectors.joinToString(", ") { c -> "${Operators.sector(c.key)} (${c.bestDbm ?: "?"} dBm)" }))
            }
            add(Texts.row(getString(R.string.mast_known),
                if (g.knownOperators.isEmpty()) getString(R.string.mast_known_none)
                else g.knownOperators.joinToString(", ") { (op, n) -> "$op ($n)" }))
            add(Texts.row(getString(R.string.mast_marked), fmt(m.ts)))
            add(Texts.row(getString(R.string.row_position), String.format(Locale.ROOT, "%.5f, %.5f", m.lat, m.lon)))
            add("")
            add("<i>${getString(R.string.mast_only_own)}</i>")
            add("<i>${getString(R.string.mast_tap_again)}</i>")
        }.joinToString("<br>")
        setInfoWindow(bubble)
        setOnMarkerClickListener { marker, _ ->
            if (bubble.isOpen && bubble.openKey == marker.relatedObject) confirmRemoveMast(m)
            else marker.showInfoWindow()
            true
        }
    }

    private fun confirmRemoveMast(m: UserMast) {
        AlertDialog.Builder(this)
            .setTitle(R.string.mast_remove_title)
            .setMessage(R.string.mast_remove_msg)
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.deleteMast(m.id) }
                    bubble.openKey = null
                    InfoWindow.closeAllInfoWindowsOn(map)
                    toast(getString(R.string.mast_removed))
                    scheduleRefresh(0)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
