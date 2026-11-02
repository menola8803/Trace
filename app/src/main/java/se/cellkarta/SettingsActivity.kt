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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : BaseActivity() {

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
        findViewById<TextView>(R.id.btnBack).apply {
            text = "‹  ${getString(R.string.settings)}"
            setOnClickListener { finish() }
        }

        // Språk
        findViewById<RadioGroup>(R.id.languageGroup).apply {
            check(if (Prefs.language(this@SettingsActivity) == Lang.SV) R.id.langSv else R.id.langEn)
            setOnCheckedChangeListener { _, id ->
                val tag = if (id == R.id.langSv) Lang.SV else Lang.EN
                if (tag != Prefs.language(this@SettingsActivity)) {
                    Prefs.setLanguage(this@SettingsActivity, tag)
                    recreate()
                }
            }
        }

        setupSchedule()

        // Updates
        findViewById<TextView>(R.id.versionValue).text =
            tr(R.string.version_value, UpdateChecker.currentVersionName(this), UpdateChecker.currentBuild(this))
        updateStatus = findViewById(R.id.updateStatus)
        btnCheck = findViewById(R.id.btnCheckUpdate)
        btnCheck.setOnClickListener {
            val r = latest
            if (r != null && r.build > UpdateChecker.currentBuild(this)) UpdateChecker.open(this, r.apkUrl)
            else checkNow()
        }
        findViewById<Switch>(R.id.switchAutoUpdate).apply {
            isChecked = Prefs.autoUpdate(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAutoUpdate(this@SettingsActivity, on) }
        }
        showLastCheck()
        loadChanges()

        // Lagring
        findViewById<View>(R.id.btnDeleteFolder).setOnClickListener { confirmDeleteFolder() }

        // Developer
        findViewById<View>(R.id.rowAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.rowAllVersions).setOnClickListener {
            UpdateChecker.open(this, "${UpdateChecker.REPO_URL}/releases")
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

    private fun showLastCheck() {
        val ts = Prefs.lastUpdateCheck(this)
        updateStatus.text = if (ts == 0L) getString(R.string.update_never)
        else tr(R.string.update_last, fmt.format(Instant.ofEpochMilli(ts)))
    }

    private fun checkNow() {
        btnCheck.isEnabled = false
        updateStatus.text = getString(R.string.update_searching)
        lifecycleScope.launch {
            try {
                val r = UpdateChecker.latest(this@SettingsActivity)
                latest = r
                Prefs.setLastUpdateCheck(this@SettingsActivity, System.currentTimeMillis())
                if (r.build > UpdateChecker.currentBuild(this@SettingsActivity)) {
                    updateStatus.text = tr(R.string.update_available, r.versionName)
                    btnCheck.text = tr(R.string.update_download, r.versionName)
                } else {
                    updateStatus.text = getString(R.string.update_latest)
                    btnCheck.setText(R.string.update_check)
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
