package se.cellkarta

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SettingsActivity : ComponentActivity() {

    private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.forLanguageTag("sv-SE"))
        .withZone(ZoneId.systemDefault())

    private lateinit var updateStatus: TextView
    private lateinit var btnCheck: Button
    private var latest: UpdateChecker.Release? = null

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

        // Updates
        findViewById<TextView>(R.id.versionValue).text =
            "${UpdateChecker.currentVersionName(this)} (build ${UpdateChecker.currentBuild(this)})"
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

        // Om appen
        findViewById<View>(R.id.rowSource).setOnClickListener { UpdateChecker.open(this, UpdateChecker.REPO_URL) }
        findViewById<View>(R.id.rowAllVersions).setOnClickListener {
            UpdateChecker.open(this, "${UpdateChecker.REPO_URL}/releases")
        }
    }

    private fun showLastCheck() {
        val ts = Prefs.lastUpdateCheck(this)
        updateStatus.text = if (ts == 0L) "Har inte sökt efter uppdateringar än."
        else "Senast kontrollerad ${fmt.format(Instant.ofEpochMilli(ts))}."
    }

    private fun checkNow() {
        btnCheck.isEnabled = false
        updateStatus.text = "Söker efter uppdatering…"
        lifecycleScope.launch {
            try {
                val r = UpdateChecker.latest()
                latest = r
                Prefs.setLastUpdateCheck(this@SettingsActivity, System.currentTimeMillis())
                if (r.build > UpdateChecker.currentBuild(this@SettingsActivity)) {
                    updateStatus.text = "Version ${r.versionName} finns att hämta."
                    btnCheck.text = "Ladda ner ${r.versionName}"
                } else {
                    updateStatus.text = "Du har den senaste versionen."
                    btnCheck.text = "Sök efter uppdatering"
                }
            } catch (e: Exception) {
                updateStatus.text = e.message ?: "Kunde inte nå GitHub. Kontrollera internetanslutningen."
            } finally {
                btnCheck.isEnabled = true
            }
        }
    }
}
