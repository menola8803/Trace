package se.cellkarta

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

/**
 * Apptrafik, nivå 2: ett lokalt VPN som bara tar emot namnuppslag (DNS).
 *
 * VPN:et får en påhittad DNS-adress och en väg till just den adressen. All annan trafik går
 * som vanligt utanför. Varje uppslag skickas oförändrat vidare till det riktiga nätets
 * DNS-server, svaret skickas tillbaka till appen, och namnet sparas med appen som frågade.
 * Android anger själv vilken app som äger uppslaget (getConnectionOwnerUid).
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "TraceDns"
        private const val ACTION_START = "se.cellkarta.DNS_START"
        private const val ACTION_STOP = "se.cellkarta.DNS_STOP"
        private const val TUN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"
        /** Bara uppslag av adresser loggas: A, AAAA och HTTPS. */
        private val LOGGED_TYPES = setOf(1, 28, 65)

        /** Privat DNS med eget servernamn i Android: då går uppslagen förbi loggen. */
        fun privateDnsName(ctx: Context): String? = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork)
            if (lp != null && lp.isPrivateDnsActive) lp.privateDnsServerName else null
        }.getOrNull()

        val running = MutableStateFlow(false)

        fun start(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_START)) }
                .onFailure { Log.w(TAG, "Kunde inte starta", it) }
        }

        fun stop(ctx: Context) {
            if (!running.value) return
            runCatching { ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopping = false
    private var reader: Thread? = null
    private var pool: ExecutorService? = null
    private lateinit var cm: ConnectivityManager
    private lateinit var db: TrafficDb
    private val writeLock = Any()

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        db = TrafficDb.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun != null) return START_STICKY
        // Startas om av systemet efter att appen stängts: bara om loggningen fortfarande är på.
        if (!Prefs.dnsLog(this) || !startTunnel()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startTunnel(): Boolean {
        val pfd = try {
            Builder()
                .setSession(getString(R.string.traffic_vpn_session))
                .addAddress(TUN_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)
                // Trace själv går utanför, så att vidarebefordringen inte hamnar i sig själv
                .addDisallowedApplication(packageName)
                // Mätt eller inte avgörs av nätet under, som vanligt
                .setMetered(false)
                .setBlocking(false)
                .establish()
        } catch (e: Exception) {
            Log.w(TAG, "VPN:et kunde inte startas", e)
            null
        } ?: return false

        tun = pfd
        stopping = false
        // Högst 8 uppslag åt gången. Kommer det fler kastas de äldsta, och appen frågar igen.
        pool = ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, LinkedBlockingQueue(256), ThreadPoolExecutor.DiscardOldestPolicy())
            .apply { allowCoreThreadTimeOut(true) }
        reader = Thread({ readLoop(pfd.fileDescriptor) }, "trace-dns").also { it.start() }
        running.value = true
        return true
    }

    private fun readLoop(fd: FileDescriptor) {
        val buf = ByteArray(32767)
        val poll = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
        while (!stopping) {
            try {
                poll[0].revents = 0
                if (Os.poll(poll, 1000) <= 0) continue
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) continue
                val packet = buf.copyOf(n)
                pool?.execute { runCatching { handle(packet, fd) }.onFailure { Log.d(TAG, "Uppslaget misslyckades", it) } }
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                if (!stopping) Log.w(TAG, "Läsningen stoppades", e)
                break
            } catch (e: Exception) {
                if (!stopping) Log.w(TAG, "Läsningen stoppades", e)
                break
            }
        }
        // Slutade läsningen av sig själv skulle alla namnuppslag i telefonen fastna.
        // Stäng då VPN:et så att telefonen genast använder vanlig DNS igen.
        if (!stopping) android.os.Handler(android.os.Looper.getMainLooper()).post {
            Prefs.setDnsLog(this, false)
            shutdown()
            stopSelf()
        }
    }

    /** Ett paket från en app: IPv4, UDP till DNS-adressen på port 53. */
    private fun handle(p: ByteArray, fd: FileDescriptor) {
        if (p.size < 28 || (p[0].toInt() shr 4) and 0xF != 4) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p[9].toInt() != 17 || p.size < ihl + 8) return
        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)
        if (dstPort != 53) return
        val end = minOf(p.size, ihl + u16(p, ihl + 4))
        if (end <= ihl + 8) return
        val query = p.copyOfRange(ihl + 8, end)
        val srcIp = p.copyOfRange(12, 16)
        val dstIp = p.copyOfRange(16, 20)

        // Vem frågar? Måste kontrolleras medan appens uppslag väntar på svar.
        val uid = try {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort),
            )
        } catch (e: Exception) {
            Process.INVALID_UID
        }
        question(query)?.let { (name, type) ->
            if (type in LOGGED_TYPES && name.isNotEmpty()) {
                runCatching { db.logLookup(uid, AppTraffic.packageOf(this, uid), name, System.currentTimeMillis()) }
            }
        }

        val answer = forward(query) ?: return
        val out = packet(dstIp, srcIp, 53, srcPort, answer)
        synchronized(writeLock) {
            if (!stopping) Os.write(fd, out, 0, out.size)
        }
    }

    /** Skickar frågan till det riktiga nätets DNS-server, utanför VPN:et. */
    private fun forward(query: ByteArray): ByteArray? {
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net)
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val servers = cm.getLinkProperties(net)?.dnsServers.orEmpty().sortedBy { if (it is Inet4Address) 0 else 1 }
        for (server in servers.take(3)) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    net.bindSocket(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(query, query.size, server, 53))
                    val buf = ByteArray(4096)
                    val r = DatagramPacket(buf, buf.size)
                    s.receive(r)
                    return buf.copyOf(r.length)
                }
            } catch (e: Exception) {
                Log.d(TAG, "DNS-servern $server svarade inte", e)
            }
        }
        return null
    }

    private fun shutdown() {
        stopping = true
        running.value = false
        pool?.shutdownNow()
        pool = null
        val t = tun
        tun = null
        runCatching { reader?.join(1500) }
        reader = null
        runCatching { t?.close() }
    }

    /** Ett annat VPN tog över, eller VPN:et stängdes av i systemets inställningar. */
    override fun onRevoke() {
        Prefs.setDnsLog(this, false)
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ---- Paket ----

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /** Namnet och typen i en DNS-fråga, till exempel ("example.com", 1). */
    private fun question(m: ByteArray): Pair<String, Int>? {
        if (m.size < 17 || u16(m, 4) < 1) return null
        var i = 12
        val sb = StringBuilder()
        while (true) {
            if (i >= m.size) return null
            val len = m[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0 || i + 1 + len > m.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..len) sb.append((m[i + k].toInt() and 0xFF).toChar())
            i += 1 + len
            if (sb.length > 253) return null
        }
        if (i + 2 > m.size) return null
        return sb.toString().lowercase() to u16(m, i)
    }

    /** IPv4- och UDP-huvud runt DNS-svaret. UDP-kontrollsumman får vara 0 i IPv4. */
    private fun packet(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45
        b[2] = (total shr 8).toByte(); b[3] = total.toByte()
        b[6] = 0x40 // får inte delas upp
        b[8] = 64
        b[9] = 17
        System.arraycopy(src, 0, b, 12, 4)
        System.arraycopy(dst, 0, b, 16, 4)
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(b, i)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val cs = sum.inv() and 0xFFFF
        b[10] = (cs shr 8).toByte(); b[11] = cs.toByte()
        b[20] = (srcPort shr 8).toByte(); b[21] = srcPort.toByte()
        b[22] = (dstPort shr 8).toByte(); b[23] = dstPort.toByte()
        val udpLen = 8 + payload.size
        b[24] = (udpLen shr 8).toByte(); b[25] = udpLen.toByte()
        System.arraycopy(payload, 0, b, 28, payload.size)
        return b
    }
}
