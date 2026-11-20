package se.cellkarta

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.view.Gravity
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : BaseActivity() {

    companion object {
        /** Öppna inställningarna och ladda ner och installera den nya versionen direkt. */
        const val EXTRA_INSTALL = "se.cellkarta.INSTALL_UPDATE"
    }


    private val fmt by lazy {
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Lang.locale(this)).withZone(ZoneId.systemDefault())
    }

    private lateinit var updateStatus: TextView
    private lateinit var btnCheck: Button
    private var latest: UpdateChecker.Release? = null
    private var updatingSchedule = false

    private val backgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enableSchedule()
            else {
                Toast.makeText(this, R.string.sched_bg_denied, Toast.LENGTH_LONG).show()
                Schedule.setEnabled(this, false)
                refreshSchedule()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val root = findViewById<View>(R.id.settingsRoot)
        val top = root.paddingTop
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = top + bars.top, bottom = bottom + bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        Ui.header(this, getString(R.string.settings), getString(R.string.settings_sub))

        // Utseende: mörkt eller beige, som i Alif
        findViewById<LinearLayout>(R.id.themeRow).apply {
            val current = Prefs.theme(this@SettingsActivity)
            listOf(Prefs.THEME_DARK to R.string.theme_dark, Prefs.THEME_BEIGE to R.string.theme_beige).forEach { (name, label) ->
                addView(Ui.themePreview(this@SettingsActivity, name == Prefs.THEME_DARK, name == current, getString(label)) {
                    if (name != Prefs.theme(this@SettingsActivity)) {
                        Prefs.setTheme(this@SettingsActivity, name)
                        recreate()
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        // Språk
        findViewById<android.widget.FrameLayout>(R.id.languageSeg).addView(Ui.Segmented(this).apply {
            set(listOf(0 to getString(R.string.language_sv), 1 to getString(R.string.language_en)),
                if (Prefs.language(this@SettingsActivity) == Lang.SV) 0 else 1)
            onChange = { v ->
                Prefs.setLanguage(this@SettingsActivity, if (v == 0) Lang.SV else Lang.EN)
                recreate()
            }
        })

        // Triangulering
        val triOptions = listOf(
            Prefs.TRI_OFF to getString(R.string.tri_off),
            Prefs.TRI_ON_DEMAND to getString(R.string.tri_on_demand),
            Prefs.TRI_AUTO to getString(R.string.tri_auto),
        )
        val triStatus = findViewById<TextView>(R.id.triStatus)
        fun showTri(mode: Int) {
            triStatus.text = when (mode) {
                Prefs.TRI_OFF -> getString(R.string.tri_status_off)
                Prefs.TRI_ON_DEMAND -> getString(R.string.tri_status_demand)
                else -> getString(R.string.tri_status_auto)
            }
            // Antal hittade master
            lifecycleScope.launch {
                val n = withContext(kotlinx.coroutines.Dispatchers.IO) { CellDb.get(this@SettingsActivity).foundCount() }
                if (mode != Prefs.TRI_OFF) triStatus.append("  " + tr(R.string.tri_found_count, n))
            }
        }
        findViewById<android.widget.FrameLayout>(R.id.triSeg).addView(Ui.Segmented(this).apply {
            set(triOptions, Prefs.triMode(this@SettingsActivity))
            onChange = { v ->
                Prefs.setTriMode(this@SettingsActivity, v)
                showTri(v)
                LiveStatus.bump()
            }
        })
        showTri(Prefs.triMode(this))
        findViewById<Switch>(R.id.switchTa).apply {
            isChecked = Prefs.timingAdvance(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setTimingAdvance(this@SettingsActivity, on)
                // Pågår inspelningen: slå på eller av direkt, utan omstart
                CellMonitorService.refreshTimingAdvance(this@SettingsActivity)
            }
        }

        setupSchedule()

        // Karta
        findViewById<Switch>(R.id.switchMastDetails).apply {
            isChecked = Prefs.mastDetails(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setMastDetails(this@SettingsActivity, on)
                LiveStatus.bump()
            }
        }

        // Apptrafik
        val openTraffic = findViewById<View>(R.id.btnOpenTraffic)
        openTraffic.visibility = if (Prefs.appTraffic(this)) View.VISIBLE else View.GONE
        openTraffic.setOnClickListener { startActivity(Intent(this, AppTrafficActivity::class.java)) }
        findViewById<Switch>(R.id.switchTraffic).apply {
            isChecked = Prefs.appTraffic(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setAppTraffic(this@SettingsActivity, on)
                openTraffic.visibility = if (on) View.VISIBLE else View.GONE
                if (!on) {
                    // Av betyder av: även loggningen av servernamn stängs
                    Prefs.setDnsLog(this@SettingsActivity, false)
                    DnsVpnService.stop(this@SettingsActivity)
                }
            }
        }

        // Updates
        findViewById<TextView>(R.id.versionValue).text =
            tr(R.string.version_value, UpdateChecker.currentVersionName(this), UpdateChecker.currentBuild(this))
        updateStatus = findViewById(R.id.updateStatus)
        btnCheck = findViewById(R.id.btnCheckUpdate)
        btnCheck.setOnClickListener {
            val r = latest
            if (r != null && r.build > UpdateChecker.currentBuild(this)) {
                // Som i CamOne: ladda ner, kontrollera och installera inne i appen
                if (r.hasApk) UpdateInstaller.downloadAndInstall(this, r) else UpdateChecker.open(this, r.pageUrl)
            } else checkNow()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { UpdateInstaller.state.collect { showDownload(it) } }
        }
        // Från rutan "Ny version" på kartan: sök och installera direkt
        if (intent.getBooleanExtra(EXTRA_INSTALL, false)) {
            intent.removeExtra(EXTRA_INSTALL)
            checkNow(install = true)
            findViewById<View>(R.id.btnCheckUpdate).post {
                findViewById<android.widget.ScrollView>(R.id.settingsRoot).smoothScrollTo(0, findViewById<View>(R.id.versionValue).top)
            }
        }
        findViewById<Switch>(R.id.switchAutoUpdate).apply {
            isChecked = Prefs.autoUpdate(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAutoUpdate(this@SettingsActivity, on) }
        }
        showLastCheck()
        loadChanges()

        // Nollställ
        findViewById<View>(R.id.btnClearMarks).setOnClickListener {
            confirmReset(R.string.reset_marks, R.string.reset_marks_confirm, R.string.reset_marks_done) {
                CellDb.get(this).clearFlags()
                CellDb.get(this).clearFound()
            }
        }
        findViewById<View>(R.id.btnClearAll).setOnClickListener {
            confirmReset(R.string.reset_all, R.string.reset_all_confirm, R.string.reset_all_done) {
                CellDb.get(this).clearRecordings()
            }
        }

        // Lagring
        findViewById<View>(R.id.btnDeleteFolder).setOnClickListener { confirmDeleteFolder() }

        // Developer
        findViewById<View>(R.id.rowAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    // ---- Schemalagd inspelning ----

    private fun setupSchedule() {
        findViewById<Switch>(R.id.schedEnabled).setOnCheckedChangeListener { _, on ->
            if (updatingSchedule) return@setOnCheckedChangeListener
            if (on) requestBackgroundThenEnable()
            else {
                Schedule.setEnabled(this, false)
                Schedule.apply(this)
                refreshSchedule()
            }
        }
        findViewById<Button>(R.id.schedFrom).setOnClickListener {
            pickTime(Schedule.startMin(this)) { Schedule.setStart(this, it) }
        }
        findViewById<Button>(R.id.schedTo).setOnClickListener {
            pickTime(Schedule.endMin(this)) { Schedule.setEnd(this, it) }
        }

        val row = findViewById<LinearLayout>(R.id.schedDays)
        val letters = getString(R.string.sched_days)
        val d = resources.displayMetrics.density
        for (i in 0 until 7) {
            row.addView(TextView(this).apply {
                text = letters.getOrNull(i)?.toString() ?: ""
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = ResourcesCompat.getFont(this@SettingsActivity, R.font.jakarta_bold)
                background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_day_chip)
                layoutParams = LinearLayout.LayoutParams(0, (38 * d).toInt(), 1f).apply {
                    marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt()
                }
                setOnClickListener {
                    Schedule.setDays(this@SettingsActivity, Schedule.days(this@SettingsActivity) xor (1 shl i))
                    Schedule.apply(this@SettingsActivity)
                    refreshSchedule()
                }
            })
        }
        refreshSchedule()
    }

    private fun pickTime(current: Int, save: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m ->
            save(h * 60 + m)
            Schedule.apply(this)
            refreshSchedule()
        }, current / 60, current % 60, true).show()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestBackgroundThenEnable() {
        when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Toast.makeText(this, R.string.sched_need_location, Toast.LENGTH_LONG).show()
                refreshSchedule()
            }
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> enableSchedule()
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.sched_bg_title)
                .setMessage(R.string.sched_bg_message)
                .setPositiveButton(R.string.sched_bg_continue) { _, _ ->
                    backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> refreshSchedule() }
                .setOnCancelListener { refreshSchedule() }
                .show()
        }
    }

    private fun enableSchedule() {
        Schedule.setEnabled(this, true)
        Schedule.apply(this)
        refreshSchedule()
    }

    private fun refreshSchedule() {
        updatingSchedule = true
        val on = Schedule.enabled(this)
        findViewById<Switch>(R.id.schedEnabled).isChecked = on
        updatingSchedule = false

        findViewById<Button>(R.id.schedFrom).text = tr(R.string.sched_from, Schedule.formatMin(Schedule.startMin(this)))
        findViewById<Button>(R.id.schedTo).text = tr(R.string.sched_to, Schedule.formatMin(Schedule.endMin(this)))

        val mask = Schedule.days(this)
        val row = findViewById<LinearLayout>(R.id.schedDays)
        for (i in 0 until row.childCount) {
            (row.getChildAt(i) as TextView).apply {
                isSelected = mask and (1 shl i) != 0
                setTextColor(ContextCompat.getColor(this@SettingsActivity, if (isSelected) R.color.on_gold else R.color.muted))
            }
        }

        val loc = Lang.locale(this)
        val clock = DateTimeFormatter.ofPattern("HH:mm", loc)
        val dayClock = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", loc)
        findViewById<TextView>(R.id.schedStatus).text = when {
            !on -> getString(R.string.sched_off)
            mask == 0 -> getString(R.string.sched_no_days)
            else -> Schedule.activeUntil(this)?.let { tr(R.string.sched_active, clock.format(it)) }
                ?: Schedule.nextStart(this)?.let { tr(R.string.sched_next, dayClock.format(it)) }
                ?: getString(R.string.sched_no_days)
        }
    }

    /** Den nya versionen: ändringar, storlek och när den publicerades. */
    private fun showRelease(r: UpdateChecker.Release) {
        findViewById<View>(R.id.updateNew).visibility = View.VISIBLE
        findViewById<TextView>(R.id.updateNewTitle).text = tr(R.string.update_new_title, r.versionName)
        val published = runCatching { fmt.format(Instant.parse(r.publishedAt)) }.getOrDefault(r.publishedAt)
        val size = if (r.size > 0) android.text.format.Formatter.formatShortFileSize(this, r.size) else "–"
        findViewById<TextView>(R.id.updateNewMeta).text =
            tr(R.string.update_new_meta, UpdateChecker.currentVersionName(this), size, published)
        val notes = findViewById<LinearLayout>(R.id.updateNotes)
        notes.removeAllViews()
        r.notes.forEachIndexed { i, line ->
            notes.addView(TextView(this).apply {
                text = if (i == 0) line else "•  $line"
                textSize = if (i == 0) 13.5f else 13f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, if (i == 0) R.color.ink else R.color.muted))
                if (i == 0) typeface = androidx.core.content.res.ResourcesCompat.getFont(this@SettingsActivity, R.font.jakarta_bold)
                setPadding(0, (3 * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
    }

    /** Nedladdningens förlopp och resultat. */
    private fun showDownload(st: UpdateInstaller.State) {
        val bar = findViewById<android.widget.ProgressBar>(R.id.updateProgress)
        val line = findViewById<TextView>(R.id.updateDlStatus)
        val name = latest?.versionName.orEmpty()
        fun say(text: String?, color: Int = R.color.muted) {
            line.visibility = if (text == null) View.GONE else View.VISIBLE
            line.text = text
            line.setTextColor(ContextCompat.getColor(this, color))
        }
        bar.visibility = if (st is UpdateInstaller.State.Downloading) View.VISIBLE else View.GONE
        when (st) {
            UpdateInstaller.State.None -> say(null)
            is UpdateInstaller.State.Downloading -> {
                bar.isIndeterminate = st.progress < 0
                if (st.progress >= 0) bar.progress = (st.progress * 1000).toInt()
                say(if (st.progress >= 0) tr(R.string.update_downloading_pct, (st.progress * 100).toInt()) else getString(R.string.update_downloading))
                btnCheck.isEnabled = false
            }
            is UpdateInstaller.State.Ready -> {
                say(getString(if (st.verified) R.string.update_verified else R.string.update_downloaded), R.color.mint)
                btnCheck.isEnabled = true
                if (name.isNotEmpty()) btnCheck.text = tr(R.string.update_install_again, name)
            }
            UpdateInstaller.State.NeedsPermission -> {
                say(getString(R.string.update_allow), R.color.gold)
                btnCheck.isEnabled = true
            }
            is UpdateInstaller.State.Failed -> {
                say(getString(when (st.error) {
                    UpdateInstaller.Error.NETWORK -> R.string.update_err_network
                    UpdateInstaller.Error.CHECKSUM -> R.string.update_err_checksum
                    UpdateInstaller.Error.WRONG_PACKAGE -> R.string.update_err_package
                    UpdateInstaller.Error.NOT_NEWER -> R.string.update_err_not_newer
                    UpdateInstaller.Error.SIGNATURE -> R.string.update_err_signature
                    UpdateInstaller.Error.INSTALL -> R.string.update_err_install
                }), R.color.danger)
                btnCheck.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Tillbaka från Androids inställning för att installera appar
        UpdateInstaller.resumeAfterPermission(this)
    }

    private fun showLastCheck() {
        val ts = Prefs.lastUpdateCheck(this)
        updateStatus.text = if (ts == 0L) getString(R.string.update_never)
        else tr(R.string.update_last, fmt.format(Instant.ofEpochMilli(ts)))
    }

    private fun checkNow(install: Boolean = false) {
        btnCheck.isEnabled = false
        updateStatus.text = getString(R.string.update_searching)
        lifecycleScope.launch {
            try {
                val r = UpdateChecker.latest(this@SettingsActivity)
                latest = r
                Prefs.setLastUpdateCheck(this@SettingsActivity, System.currentTimeMillis())
                if (r.build > UpdateChecker.currentBuild(this@SettingsActivity)) {
                    updateStatus.text = tr(R.string.update_available, r.versionName)
                    btnCheck.text = tr(R.string.update_install, r.versionName)
                    showRelease(r)
                    if (install && r.hasApk) UpdateInstaller.downloadAndInstall(this@SettingsActivity, r)
                } else {
                    updateStatus.text = getString(R.string.update_latest)
                    btnCheck.setText(R.string.update_check)
                    findViewById<View>(R.id.updateNew).visibility = View.GONE
                    UpdateInstaller.reset()
                }
            } catch (e: Exception) {
                updateStatus.text = e.message ?: getString(R.string.update_no_net)
            } finally {
                btnCheck.isEnabled = true
            }
        }
    }

    /** Senaste ändringarna i repot på GitHub, som allmän uppdateringsinformation. */
    private fun loadChanges() {
        val list = findViewById<LinearLayout>(R.id.changesList)
        list.removeAllViews()
        list.addView(line(getString(R.string.changes_loading), R.color.dim))
        lifecycleScope.launch {
            try {
                val changes = UpdateChecker.recentChanges(this@SettingsActivity)
                list.removeAllViews()
                changes.forEach { c ->
                    list.addView(LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (5 * resources.displayMetrics.density).toInt(), 0, 0)
                        addView(line(c.date, R.color.gold).apply {
                            minWidth = (56 * resources.displayMetrics.density).toInt()
                            typeface = ResourcesCompat.getFont(this@SettingsActivity, R.font.jakarta_bold)
                        })
                        addView(line(c.title, R.color.muted).apply {
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        })
                    })
                }
            } catch (e: Exception) {
                list.removeAllViews()
                list.addView(line(getString(R.string.changes_failed), R.color.dim))
            }
        }
    }

    private fun line(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@SettingsActivity, color))
    }

    private fun confirmReset(title: Int, message: Int, done: Int, work: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.reset_do) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { work() }
                    Notifier(this@SettingsActivity).clearAlerts()
                    Prefs.setEventsSeen(this@SettingsActivity, System.currentTimeMillis())
                    LiveStatus.bump()
                    Toast.makeText(this@SettingsActivity, done, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFolder() {
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_delete)
            .setMessage(R.string.storage_delete_confirm)
            .setPositiveButton(R.string.remove) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val n = withContext(Dispatchers.IO) { TraceFiles.deleteAll(this@SettingsActivity) }
                        Toast.makeText(this@SettingsActivity, tr(R.string.storage_deleted, n), Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, tr(R.string.storage_delete_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
