package se.cellkarta

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Om Trace: samma upplägg som Om-sidan i Direct och Alif. */
class AboutActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val root = findViewById<View>(R.id.aboutRoot)
        val top = root.paddingTop
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = top + bars.top, bottom = bottom + bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        findViewById<TextView>(R.id.btnBack).apply {
            text = "‹  ${getString(R.string.about_trace)}"
            setOnClickListener { finish() }
        }

        findViewById<TextView>(R.id.headline).text = buildSpannedString {
            append(getString(R.string.about_headline_1) + " ")
            color(ContextCompat.getColor(this@AboutActivity, R.color.mint)) { append(getString(R.string.about_headline_2)) }
        }

        findViewById<TextView>(R.id.aboutVersion).text =
            tr(R.string.about_version, UpdateChecker.currentVersionName(this), UpdateChecker.currentBuild(this))

    }
}
