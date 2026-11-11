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
        private const val TAG = "Cellkarta"
    }

    private lateinit var tm: TelephonyManager
    private lateinit var lm: LocationManager
    private lateinit var notifier: Notifier
    private lateinit var detector: Detector
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var lastLocation: Location? = null
    private var started = false

    private val tick = object : Runnable {
        override fun run() {
            scan()
            handler.postDelayed(this, INTERVAL_MS)
        }
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
        ServiceCompat.startForeground(
            this, Notifier.ONGOING_ID, notifier.ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
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
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        if (!hasLocationPermission()) return
        try {
            tm.requestCellInfoUpdate(worker, object : TelephonyManager.CellInfoCallback() {
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
        val loc = lastLocation
        if (loc == null ||
            loc.accuracy > MAX_ACCURACY_M ||
            System.currentTimeMillis() - loc.time > MAX_LOCATION_AGE_MS
        ) {
            LiveStatus.note(
                if (loc == null) getString(R.string.note_wait_position)
                else tr(R.string.note_wait_accuracy, loc.accuracy.toInt())
            )
            return
        }
        try {
            val list = fresh ?: tm.allCellInfo ?: emptyList()
            detector.process(CellReader.parse(list), loc)
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
