package se.cellkarta

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** Komponenter i samma stil som Alif. */
object Ui {

    /** Fyller i sidhuvudet (view_header.xml): tillbakaknapp, rubrik och undertitel. */
    fun header(a: Activity, title: String, sub: String? = null) {
        a.findViewById<TextView>(R.id.headerTitle).text = title
        a.findViewById<TextView>(R.id.headerSub).apply {
            text = sub
            visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        a.findViewById<View>(R.id.headerBack).setOnClickListener { a.finish() }
    }

    /**
     * Segmentknapp som i Alif: en glaslik ram med valen bredvid varandra. Det valda är
     * markerat med en ljus bakgrund och ett litet streck under texten.
     */
    class Segmented(ctx: Context) : LinearLayout(ctx) {
        private val d = resources.displayMetrics.density
        private var items: List<Pair<Int, String>> = emptyList()
        private var selected: Int = 0
        var onChange: ((Int) -> Unit)? = null

        init {
            orientation = HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_segmented)
            val p = (4 * d).toInt()
            setPadding(p, p, p, p)
        }

        fun set(options: List<Pair<Int, String>>, value: Int) {
            items = options
            selected = value
            rebuild()
        }

        private fun rebuild() {
            removeAllViews()
            val bold = ResourcesCompat.getFont(context, R.font.jakarta_bold) ?: Typeface.DEFAULT_BOLD
            items.forEach { (value, label) ->
                val on = value == selected
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = (44 * d).toInt()
                    background = ContextCompat.getDrawable(context, R.drawable.bg_segment_on)
                    isSelected = on
                    contentDescription = label
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (1 * d).toInt(); marginEnd = (1 * d).toInt()
                    }
                    addView(TextView(context).apply {
                        text = label
                        textSize = 13f
                        maxLines = 1
                        typeface = bold
                        gravity = Gravity.CENTER
                        setTextColor(ContextCompat.getColor(context, if (on) R.color.gold else R.color.muted))
                    })
                    if (on) addView(View(context).apply {
                        background = GradientDrawable().apply {
                            setColor(ContextCompat.getColor(context, R.color.gold))
                            cornerRadius = 2 * d
                        }
                        layoutParams = LayoutParams((16 * d).toInt(), (3 * d).toInt()).apply { topMargin = (2 * d).toInt() }
                    })
                    setOnClickListener {
                        if (selected != value) {
                            selected = value
                            rebuild()
                            onChange?.invoke(value)
                        }
                    }
                })
            }
        }
    }

    /** Förhandsbild av ett tema, som i Alif. Färgerna anges direkt eftersom bara ett tema är laddat åt gången. */
    fun themePreview(ctx: Context, dark: Boolean, chosen: Boolean, label: String, onClick: () -> Unit): View {
        val d = ctx.resources.displayMetrics.density
        val bg = Color.parseColor(if (dark) "#0F1A1C" else "#EFEDE8")
        val card = Color.parseColor(if (dark) "#172629" else "#FAF8F4")
        val line = Color.parseColor(if (dark) "#25393D" else "#DDD8CD")
        val ink = Color.parseColor(if (dark) "#EAF1EF" else "#26231F")
        val accent = Color.parseColor(if (dark) "#5FC4B4" else "#7A5C2B")
        val muted = Color.parseColor(if (dark) "#B7C6C3" else "#6A6358")
        val primary = Color.parseColor(if (dark) "#E8B04A" else "#26231F")
        val word = Color.parseColor(if (dark) "#F3D9A0" else "#26231F")
        fun rect(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
            setColor(color); cornerRadius = radius * d; if (stroke != null) setStroke((1 * d).toInt(), stroke)
        }
        fun bar(color: Int, w: Int, h: Int, top: Int) = View(ctx).apply {
            background = rect(color, h / 2f)
            layoutParams = LinearLayout.LayoutParams((w * d).toInt(), (h * d).toInt()).apply { topMargin = (top * d).toInt() }
        }
        val preview = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rect(bg, 16f, line)
            val p = (10 * d).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams((120 * d).toInt(), (200 * d).toInt())
            addView(bar(ink, 60, 10, 0))
            addView(FrameLayout(ctx).apply {
                background = rect(card, 10f, line)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (46 * d).toInt()).apply { topMargin = (8 * d).toInt() }
                addView(TextView(ctx).apply {
                    text = "TRACE"
                    textSize = 14f
                    letterSpacing = 0.2f
                    setTextColor(word)
                    typeface = ResourcesCompat.getFont(ctx, R.font.cinzel_bold)
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = (8 * d).toInt() }
                })
            })
            addView(bar(accent, 70, 8, 8))
            addView(bar(muted, 90, 8, 8))
            addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
            addView(View(ctx).apply {
                background = rect(primary, 10f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (28 * d).toInt())
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(FrameLayout(ctx).apply {
                val p = (4 * d).toInt()
                setPadding(p, p, p, p)
                background = rect(Color.TRANSPARENT, 20f, if (chosen) ContextCompat.getColor(ctx, R.color.gold) else Color.TRANSPARENT)
                    .apply { if (chosen) setStroke((2 * d).toInt(), ContextCompat.getColor(ctx, R.color.gold)) }
                addView(preview)
            })
            addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                typeface = ResourcesCompat.getFont(ctx, R.font.jakarta_bold)
                setTextColor(ContextCompat.getColor(ctx, R.color.ink))
                setPadding(0, (8 * d).toInt(), 0, 0)
            })
        }
    }
}
