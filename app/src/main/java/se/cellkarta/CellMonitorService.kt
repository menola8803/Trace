package se.cellkarta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Förgrundstjänst som läser av mobilnätet med jämna mellanrum,
 * kopplar varje mätning till en GPS-position och skickar vidare till Detector.
 */
class CellMonitorService : Service(), LocationListener {

    companion object {
        /** Hur ofta cellerna läses av. Kortare intervall ger fler mätpunkter men drar mer batteri. */
        const val INTERVAL_MS = 15_000L
        const val MAX_ACCURACY_M = 100f
        const val MAX_LOCATION_AGE_MS = 60_000L
        /** Grövre eller äldre än så här sparas inte alls: positionen säger då för lite. */
        private const val MAX_SAVE_ACCURACY_M = 5_000f
        /** En gammal position blir osäkrare för varje sekund, ungefär som en promenad. */
        private const val AGE_GROWTH_M_PER_S = 2f
        private const val TAG = "Cellkarta"
        private const val ACTION_TA = "se.cellkarta.TA_CHANGED"
        private const val FIND_INTERVAL_MS = 120_000L
        private const val FIND_MAX_SITES = 12

        /** Timing Advance slogs på eller av i inställningarna: gäller direkt om inspelningen pågår. */
        fun refreshTimingAdvance(ctx: android.content.Context) {
            if (!LiveStatus.state.value.running) return
            runCatching { ctx.startService(Intent(ctx, CellMonitorService::class.java).setAction(ACTION_TA)) }
        }
    }

    private lateinit var tm: TelephonyManager
    private lateinit var lm: LocationManager
    private lateinit var notifier: Notifier
    private lateinit var detector: Detector
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    /** Svar som kommer efter att tjänsten stoppats kastas, i stället för att krascha. */
    private val safeWorker = java.util.concurrent.Executor { r ->
        if (!worker.isShutdown) runCatching { worker.execute(r) }
    }
    @Volatile private var lastLocation: Location? = null
    private var started = false

    private val tick = object : Runnable {
        override fun run() {
            scan()
            findMasts()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    // ---- Hitta master ----

    /** Beräkningarna tar tid, därför en egen tråd, så att mätningarna inte väntar. */
    private val finder = Executors.newSingleThreadExecutor()
    private val finding = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastFindAt = 0L
    private var findSince = 0L
    /** Master som väntar på beräkning. Används bara i beräkningstråden. */
    private val pending = LinkedHashSet<String>()

    /**
     * Med Triangulering på Auto: räkna om masterna vars celler har fått nya mätningar,
     * och placera ut dem som räcker till en position (minst tre platser).
     */
    private fun findMasts() {
        if (Prefs.triMode(this) != Prefs.TRI_AUTO) return
        val now = System.currentTimeMillis()
        if (now - lastFindAt < FIND_INTERVAL_MS || !finding.compareAndSet(false, true)) return
        lastFindAt = now
        val since = findSince
        runCatching {
            finder.execute {
                try {
                    val db = CellDb.get(this)
                    // Nya master först i kön. Hinns inte alla med tas resten nästa gång.
                    db.cellKeysSince(since).mapNotNull { Triangulate.siteKeyOf(it) }.forEach { pending.add(it) }
                    val batch = pending.take(FIND_MAX_SITES)
                    pending.removeAll(batch.toSet())
                    val placed = Triangulate.forSites(db, batch)
                        .filter { it.quality != Triangulate.Quality.INSUFFICIENT }
                    placed.forEach { db.saveFound(it) }
                    findSince = now
                    if (placed.isNotEmpty()) LiveStatus.bump()
                } catch (e: Exception) {
                    Log.w(TAG, "Masterna kunde inte beräknas", e)
                } finally {
                    finding.set(false)
                }
            }
        }.onFailure { finding.set(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        tm = getSystemService(TelephonyManager::class.java)
        lm = getSystemService(LocationManager::class.java)
        notifier = Notifier(this)
        detector = Detector(this, CellDb.get(this), notifier)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TA) {
            if (!started) { stopSelf(); return START_NOT_STICKY }
            if (Prefs.timingAdvance(this)) taKeeper.start() else taKeeper.stop()
            return START_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, Notifier.ONGOING_ID, notifier.ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            // Android tillåter inte alltid att inspelningen startar i bakgrunden (till exempel efter
            // att systemet har stängt appen). Då stoppas den i stället för att appen kraschar.
            Log.w(TAG, "Kunde inte starta inspelningen", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startLocationUpdates()
            startDisplayInfo()
            if (Prefs.timingAdvance(this)) taKeeper.start()
            handler.post(tick)
            LiveStatus.setRunning(true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        lm.removeUpdates(this)
        stopDisplayInfo()
        taKeeper.stop()
        worker.shutdown()
        finder.shutdown()
        started = false
        LiveStatus.setRunning(false)
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            LiveStatus.note(getString(R.string.note_location_off))
        }
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLocation = it }
        // minDistance 0: positionen uppdateras även när du står still, annars blir den för gammal.
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, this, Looper.getMainLooper())
        // Nätverksposition som reserv inomhus, där GPS ofta inte når fram.
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, this, Looper.getMainLooper())
        }
        // Androids egen sammanvägning av GPS, wifi och mobilnät: ger position även inomhus.
        if (android.os.Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
            runCatching { lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 5_000L, 0f, this, Looper.getMainLooper()) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        if (!hasLocationPermission()) return
        try {
            tm.requestCellInfoUpdate(safeWorker, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) = handle(cellInfo)
                override fun onError(errorCode: Int, detail: Throwable?) = handle(null)
            })
        } catch (e: SecurityException) {
            LiveStatus.note(getString(R.string.note_no_permission))
        }
    }

    /** Körs på worker-tråden, så databasarbetet stör inte gränssnittet. */
    @SuppressLint("MissingPermission")
    private fun handle(fresh: List<CellInfo>?) {
        // Inomhus kommer GPS ofta inte fram. Mätningen sparas ändå, med positionens verkliga
        // osäkerhet, så att inspelningen inte blir tom. Bara exakta positioner används för masterna.
        val loc = freshestLocation()
        if (loc == null) {
            LiveStatus.note(getString(R.string.note_wait_position))
            return
        }
        val ageS = ((System.currentTimeMillis() - loc.time) / 1000L).coerceAtLeast(0L)
        val acc = loc.accuracy + if (ageS * 1000L > MAX_LOCATION_AGE_MS) (ageS - MAX_LOCATION_AGE_MS / 1000L) * AGE_GROWTH_M_PER_S else 0f
        if (acc > MAX_SAVE_ACCURACY_M) {
            LiveStatus.note(tr(R.string.note_wait_accuracy, acc.toInt()))
            return
        }
        val precise = acc <= MAX_ACCURACY_M
        try {
            val list = fresh ?: tm.allCellInfo ?: emptyList()
            detector.process(CellReader.parse(list), loc, acc, precise)
            if (!precise) LiveStatus.note(tr(R.string.note_imprecise, acc.toInt()))
        } catch (e: Exception) {
            Log.w(TAG, "Mätningen misslyckades", e)
        }
    }

    // ---- Vad statusraden visar: 4G, 4G+, 5G via 4G ----

    private var displayCallback: Any? = null
    private val taKeeper by lazy { TaKeeper(this) }

    private fun startDisplayInfo() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        try {
            val cb = object : android.telephony.TelephonyCallback(),
                android.telephony.TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: android.telephony.TelephonyDisplayInfo) {
                    LiveStatus.display(describeDisplay(info))
                }
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            displayCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Kan inte läsa nättypen från statusraden", e)
        }
    }

    private fun stopDisplayInfo() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        (displayCallback as? android.telephony.TelephonyCallback)?.let {
            runCatching { tm.unregisterTelephonyCallback(it) }
        }
        displayCallback = null
        LiveStatus.display(null)
    }

    private fun describeDisplay(info: android.telephony.TelephonyDisplayInfo): String? {
        val o = info.overrideNetworkType
        return when (o) {
            android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> getString(R.string.net_5g_nsa)
            android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> getString(R.string.net_5g_plus)
            android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
            android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G+"
            else -> when (info.networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
                TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
                else -> null
            }
        }
    }

    /** Den senaste positionen, eller en färskare som någon annan app redan har tagit fram. */
    @SuppressLint("MissingPermission")
    private fun freshestLocation(): Location? {
        val candidates = listOfNotNull(lastLocation) + runCatching {
            lm.allProviders.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        }.getOrDefault(emptyList())
        // Den med minst osäkerhet när åldern räknas in
        val now = System.currentTimeMillis()
        return candidates.minByOrNull { it.accuracy + ((now - it.time) / 1000f).coerceAtLeast(0f) * AGE_GROWTH_M_PER_S }
    }

    override fun onLocationChanged(location: Location) {
        val last = lastLocation
        val useIt = last == null ||
            location.provider == LocationManager.GPS_PROVIDER ||
            location.accuracy <= last.accuracy ||
            location.time - last.time > 20_000L
        if (useIt) lastLocation = location
    }

    // Behövs för Android 10, där metoderna saknar standardimplementation.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            LiveStatus.note(getString(R.string.note_gps_off))
        }
    }
    @Deprecated("Krävs på äldre Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
