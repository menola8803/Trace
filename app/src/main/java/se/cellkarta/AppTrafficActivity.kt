package se.cellkarta

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Apptrafik: hur mycket data varje app har använt (nivå 1) och, om loggningen är på,
 * vilka servrar apparna har slagit upp (nivå 2).
 */
class AppTrafficActivity : BaseActivity() {

    private val d by lazy { resources.displayMetrics.density }
    private val zone = ZoneId.systemDefault()
    private val timeFmt by lazy { DateTimeFormatter.ofPattern("d MMM HH:mm", Lang.locale(this)).withZone(zone) }
    private lateinit var list: LinearLayout
    private var period = 0
    private var loadJob: Job? = null
    private val expanded = HashSet<Int>()
    private var dnsSwitch: Switch? = null
    private var updatingSwitch = false

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) startDnsLog()
        else {
            Toast.makeText(this, R.string.traffic_dns_denied, Toast.LENGTH_LONG).show()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traffic)
        list = findViewById(R.id.trafficList)
        Ui.header(this, getString(R.string.traffic_title), getString(R.string.traffic_sub))
        val root = findViewById<View>(R.id.trafficRoot)
        val top = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = top + bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DnsVpnService.running.collect { render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Tillbaka från systemets inställningar för användningsåtkomst
        render()
    }

    private fun startDnsLog() {
        Prefs.setDnsLog(this, true)
        DnsVpnService.start(this)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            if (!DnsVpnService.running.value) {
                Prefs.setDnsLog(this@AppTrafficActivity, false)
                Toast.makeText(this@AppTrafficActivity, R.string.traffic_dns_failed, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun periodStart(): Long {
        val today = LocalDate.now(zone)
        val first = when (period) { 0 -> today; 1 -> today.minusDays(6); else -> today.minusDays(29) }
        return first.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun bytes(n: Long) = Formatter.formatShortFileSize(this, n)

    private fun text(s: String, color: Int, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(ContextCompat.getColor(this@AppTrafficActivity, color))
        if (bold) typeface = ResourcesCompat.getFont(this@AppTrafficActivity, R.font.jakarta_bold)
        setLineSpacing(2f * d, 1f)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@AppTrafficActivity, R.drawable.bg_card)
        val p = (14 * d).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() }
    }

    private fun section(title: String) = text(title, R.color.gold, 12f, bold = true).apply {
        letterSpacing = 0.12f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (22 * d).toInt() }
    }

    /** Bygger hela sidan. Siffrorna hämtas i bakgrunden. */
    private fun render() {
        loadJob?.cancel()
        list.removeAllViews()
        val access = AppTraffic.hasAccess(this)

        if (!access) {
            list.addView(card().apply {
                addView(text(getString(R.string.traffic_access_title), R.color.ink, 15f, bold = true))
                addView(text(getString(R.string.traffic_access_text), R.color.muted, 13f).apply { setPadding(0, (4 * d).toInt(), 0, 0) })
                addView(text(getString(R.string.traffic_access_restricted), R.color.dim, 12f).apply { setPadding(0, (6 * d).toInt(), 0, 0) })
                addView(Button(this@AppTrafficActivity, null, 0, R.style.Trace_Button_Primary).apply {
                    text = getString(R.string.traffic_access_button)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() }
                    setOnClickListener {
                        // Först med root, om telefonen har det. Annars systemets inställningar.
                        isEnabled = false
                        lifecycleScope.launch {
                            val granted = withContext(Dispatchers.IO) { AppTraffic.grantWithRoot(this@AppTrafficActivity) }
                            if (granted) {
                                Toast.makeText(this@AppTrafficActivity, R.string.traffic_access_root, Toast.LENGTH_SHORT).show()
                                render()
                            } else {
                                isEnabled = true
                                AppTraffic.openAccessSettings(this@AppTrafficActivity)
                            }
                        }
                    }
                })
            })
        }

        // Logga servernamn (lokalt VPN)
        list.addView(dnsCard())

        if (access) {
            list.addView(FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * d).toInt() }
                addView(Ui.Segmented(this@AppTrafficActivity).apply {
                    set(listOf(0 to getString(R.string.traffic_today), 1 to getString(R.string.traffic_7d), 2 to getString(R.string.traffic_30d)), period)
                    onChange = { v -> period = v; render() }
                })
            })
        }

        val apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(apps)
        apps.addView(text(getString(R.string.preparing), R.color.dim, 13f).apply { setPadding(0, (16 * d).toInt(), 0, 0) })

        val from = periodStart()
        loadJob = lifecycleScope.launch {
            val (usage, hosts) = withContext(Dispatchers.IO) {
                val u = if (access) runCatching { AppTraffic.usage(this@AppTrafficActivity, from, System.currentTimeMillis()) }.getOrDefault(emptyMap())
                else emptyMap()
                u to runCatching { TrafficDb.get(this@AppTrafficActivity).hostsSince(from) }.getOrDefault(emptyMap())
            }
            apps.removeAllViews()
            fillApps(apps, usage, hosts)
        }
    }

    private fun dnsCard(): View = card().apply {
        val on = Prefs.dnsLog(this@AppTrafficActivity) && DnsVpnService.running.value
        dnsSwitch = Switch(this@AppTrafficActivity).apply {
            text = getString(R.string.traffic_dns_title)
            setTextColor(ContextCompat.getColor(this@AppTrafficActivity, R.color.ink))
            textSize = 16f
            minHeight = (48 * d).toInt()
            updatingSwitch = true
            isChecked = on
            updatingSwitch = false
            setOnCheckedChangeListener { _, checked ->
                if (updatingSwitch) return@setOnCheckedChangeListener
                if (checked) {
                    val consent = VpnService.prepare(this@AppTrafficActivity)
                    if (consent != null) vpnConsent.launch(consent) else startDnsLog()
                } else {
                    Prefs.setDnsLog(this@AppTrafficActivity, false)
                    DnsVpnService.stop(this@AppTrafficActivity)
                }
            }
        }
        addView(dnsSwitch)
        addView(text(getString(R.string.traffic_dns_desc), R.color.muted, 12f).apply { setPadding(0, (4 * d).toInt(), 0, 0) })
        val status = text(getString(R.string.traffic_dns_off), if (on) R.color.mint else R.color.dim, 13f, bold = true).apply {
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        addView(status)
        if (on) DnsVpnService.privateDnsName(this@AppTrafficActivity)?.let { name ->
            addView(text(tr(R.string.traffic_dns_private, name), R.color.danger, 12f).apply { setPadding(0, (6 * d).toInt(), 0, 0) })
        }
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { TrafficDb.get(this@AppTrafficActivity).lookups() }.getOrDefault(0L) }
            status.text = if (on) tr(R.string.traffic_dns_on, n) else getString(R.string.traffic_dns_off)
            if (n > 0) addView(Button(this@AppTrafficActivity, null, 0, R.style.Trace_Button).apply {
                text = getString(R.string.traffic_dns_clear)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
                setOnClickListener {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { TrafficDb.get(this@AppTrafficActivity).clear() }
                        Toast.makeText(this@AppTrafficActivity, R.string.traffic_dns_cleared, Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            })
        }
    }

    private fun fillApps(container: LinearLayout, usage: Map<Int, AppTraffic.Usage>, hosts: Map<Int, List<TrafficDb.Host>>) {
        val uids = (usage.filterValues { it.total > 0 }.keys + hosts.keys).distinct()
            .sortedWith(compareByDescending<Int> { usage[it]?.total ?: 0L }.thenByDescending { hosts[it]?.sumOf { h -> h.count } ?: 0 })
        if (usage.isNotEmpty()) {
            val mobile = usage.values.sumOf { it.mobile }
            val wifi = usage.values.sumOf { it.wifi }
            container.addView(section(getString(R.string.traffic_title).uppercase(Lang.locale(this))))
            container.addView(text(tr(R.string.traffic_total, bytes(mobile), bytes(wifi)), R.color.ink, 14f, bold = true))
            container.addView(text(getString(R.string.traffic_note), R.color.dim, 11.5f))
        }
        if (uids.isEmpty()) {
            container.addView(text(getString(R.string.traffic_none), R.color.muted, 14f).apply {
                gravity = Gravity.CENTER
                setPadding(0, (32 * d).toInt(), 0, 0)
            })
            return
        }
        val max = usage.values.maxOfOrNull { it.total }?.coerceAtLeast(1L) ?: 1L
        uids.take(80).forEach { uid -> container.addView(appCard(uid, usage[uid], hosts[uid].orEmpty(), max)) }
    }

    private fun appCard(uid: Int, u: AppTraffic.Usage?, servers: List<TrafficDb.Host>, max: Long): View = card().apply {
        val name = AppTraffic.name(this@AppTrafficActivity, uid, servers.firstOrNull()?.pkg)
        addView(LinearLayout(this@AppTrafficActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@AppTrafficActivity).apply {
                if (name.icon != null) setImageDrawable(name.icon)
                else setImageDrawable(MapMarks.icon(this@AppTrafficActivity, "?", ContextCompat.getColor(this@AppTrafficActivity, R.color.dim), 34f))
                layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt())
            })
            addView(LinearLayout(this@AppTrafficActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * d).toInt() }
                addView(text(name.label, R.color.ink, 15f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(
                    if (u != null && u.total > 0) tr(R.string.traffic_split, bytes(u.mobile), bytes(u.wifi))
                    else getString(R.string.traffic_only_lookups),
                    R.color.muted, 12.5f))
            })
            if (u != null && u.total > 0) addView(text(bytes(u.total), R.color.gold, 15f, bold = true).apply {
                setPadding((8 * d).toInt(), 0, 0, 0)
            })
        })

        if (u != null && u.total > 0) {
            // Stapel: mobilnät och wifi, i proportion till appen som använt mest
            val width = (u.total.toDouble() / max).coerceIn(0.02, 1.0)
            addView(LinearLayout(this@AppTrafficActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, (5 * d).toInt()).apply { topMargin = (10 * d).toInt() }
                weightSum = 1f
                fun part(w: Double, color: Int) = View(this@AppTrafficActivity).apply {
                    setBackgroundColor(color)
                    layoutParams = LinearLayout.LayoutParams(0, -1, w.toFloat())
                }
                val m = if (u.total > 0) u.mobile.toDouble() / u.total else 0.0
                addView(part(width * m, Net.G4.color))
                addView(part(width * (1 - m), ContextCompat.getColor(this@AppTrafficActivity, R.color.gold)))
            })
            addView(text(tr(R.string.traffic_down_up, bytes(u.rx), bytes(u.tx)), R.color.dim, 12f).apply {
                setPadding(0, (6 * d).toInt(), 0, 0)
            })
        }

        if (servers.isNotEmpty()) {
            val open = uid in expanded
            addView(text(
                if (open) getString(R.string.traffic_servers_hide) else tr(R.string.traffic_servers_n, servers.size),
                R.color.mint, 13f, bold = true
            ).apply {
                minHeight = (40 * d).toInt()
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@AppTrafficActivity, android.R.drawable.list_selector_background)
                setOnClickListener {
                    if (!expanded.remove(uid)) expanded.add(uid)
                    render()
                }
            })
            if (open) {
                servers.take(60).forEach { h ->
                    addView(text(h.host, R.color.ink, 13f).apply { setPadding(0, (6 * d).toInt(), 0, 0); setTextIsSelectable(true) })
                    addView(text(tr(R.string.traffic_server_line, h.count, timeFmt.format(Instant.ofEpochMilli(h.lastTs))), R.color.dim, 11.5f))
                }
                if (servers.size > 60) addView(text(tr(R.string.traffic_more_servers, servers.size - 60), R.color.dim, 12f).apply {
                    setPadding(0, (6 * d).toInt(), 0, 0)
                })
            }
        }
    }
}
