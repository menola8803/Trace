package se.cellkarta

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Håller mobildatan igång under inspelning så att nätet anger Timing Advance.
 * Telefonen gör ett mycket litet anrop var fjärde sekund, alltid via mobilnätet
 * även när den är ansluten till wifi. Ungefär 0,5 MB i timmen.
 */
class TaKeeper(private val ctx: Context) {

    companion object {
        private const val TAG = "Trace"
        private const val INTERVAL_MS = 4_000L
        private const val URL_204 = "https://connectivitycheck.gstatic.com/generate_204"
    }

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var cellular: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val ping = object : Runnable {
        override fun run() {
            val net = cellular
            if (net != null) {
                try {
                    val c = net.openConnection(URL(URL_204)) as HttpURLConnection
                    c.connectTimeout = 3_000
                    c.readTimeout = 3_000
                    c.useCaches = false
                    c.responseCode
                    c.disconnect()
                } catch (e: Exception) {
                    Log.d(TAG, "Datahållaren: ${e.message}")
                }
            }
            handler?.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (thread != null) return
        thread = HandlerThread("trace-ta").also { it.start() }
        handler = Handler(thread!!.looper)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { cellular = network }
            override fun onLost(network: Network) { if (cellular == network) cellular = null }
        }
        try {
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb
            )
            callback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Kan inte begära mobildata", e)
        }
        handler?.post(ping)
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        cellular = null
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
