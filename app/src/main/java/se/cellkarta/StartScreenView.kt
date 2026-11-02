package se.cellkarta

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow

/**
 * Trace egen startskärm. Alltid mörk. Samma upplägg som startskärmarna i Direct och Alif:
 * en 390 x 844-duk som skalas till skärmen, en guldring som ritar upp sig, ett T som en sändande mast,
 * två punkter som kretsar, krusningar, ordmärket i Cinzel, appens tre funktioner och utvecklaren längst ner.
 */
@SuppressLint("ViewConstructor")
class StartScreenView(context: Context, private val onDone: (StartScreenView) -> Unit) : View(context) {

    companion object {
        private const val W0 = 390f
        private const val H0 = 844f
        private const val SHOW_MS = 3600L
        private const val SHOW_REDUCED_MS = 1200L
        private const val FADE_MS = 450L
        private const val MIN_SKIP_MS = 700L

        private val BG = Color.parseColor("#0B1416")
        private val TEAL = Color.parseColor("#17393E")
        private val PANEL = Color.parseColor("#132225")
        private val LINE = Color.parseColor("#2A4448")
        private val GOLD = Color.parseColor("#E8B04A")
        private val GOLD_DIM = Color.parseColor("#8A6D2E")
        private val GOLD_PALE = Color.parseColor("#F3D9A0")
        private val MINT = Color.parseColor("#5FC4B4")
        private val INK = Color.parseColor("#B7C6C3")
        private val DIM = Color.parseColor("#8FA5A2")

        private const val CX = 195f
        private const val CY = 292f
    }

    private val reduce = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f
    private val born = SystemClock.uptimeMillis()
    private var finishing = false

    private val cinzel: Typeface = ResourcesCompat.getFont(context, R.font.cinzel_bold) ?: Typeface.SERIF
    private val sansBold: Typeface = ResourcesCompat.getFont(context, R.font.jakarta_bold) ?: Typeface.DEFAULT_BOLD
    private val sansMedium: Typeface = ResourcesCompat.getFont(context, R.font.jakarta_medium) ?: Typeface.DEFAULT

    private val tagline = context.getString(R.string.tagline)
    private val developer = context.getString(R.string.developer_line)
    private val learning = context.getString(R.string.learning_only_caps)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val bounds = android.graphics.Rect()
    private val dash = DashPathEffect(floatArrayOf(5f, 4.2f), 0f)

    init {
        isClickable = true
        contentDescription = "Trace. $developer. $learning"
        postDelayed({ finish() }, if (reduce) SHOW_REDUCED_MS else SHOW_MS)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - born > MIN_SKIP_MS) finish()
        return true
    }

    private fun finish() {
        if (finishing) return
        finishing = true
        animate().alpha(0f).setDuration(FADE_MS).withEndAction { onDone(this) }.start()
    }

    // ---- Tid och rörelse ----

    private fun elapsed(): Float = if (reduce) 100_000f else (SystemClock.uptimeMillis() - born).toFloat()

    /** Infart: 0 till 1 med mjuk inbromsning. */
    private fun ent(t: Float, delay: Float, dur: Float): Float {
        val x = ((t - delay) / dur).coerceIn(0f, 1f)
        return 1f - (1f - x).pow(3)
    }

    /** Upprepning: 0 till 1 om och om igen. */
    private fun loop(t: Float, period: Float, delay: Float = 0f): Float =
        if (reduce) 0.5f else ((t - delay).coerceAtLeast(0f) % period) / period

    /** Pendling: mjukt fram och tillbaka mellan 0 och 1. */
    private fun ping(t: Float, period: Float, delay: Float = 0f): Float =
        if (reduce) 0.5f else (0.5f - 0.5f * cos(2f * PI.toFloat() * loop(t, period, delay)))

    /** Kort puls var tredje sekund, som ljuset runt funktionerna i Alif. */
    private fun pulse(t: Float, delay: Float): Float {
        if (reduce || t < delay) return 0f
        val p = (t - delay) % 3000f
        return when {
            p < 240f -> p / 240f
            p < 540f -> 1f
            p < 780f -> 1f - (p - 540f) / 240f
            else -> 0f
        }
    }

    private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x

    private fun Paint.tint(c: Int, alpha: Float) {
        color = c
        this.alpha = (alpha.coerceIn(0f, 1f) * Color.alpha(c)).toInt()
    }

    // ---- Ritning ----

    override fun onDraw(canvas: Canvas) {
        val t = elapsed()
        canvas.drawColor(BG)

        val s = min(width / W0, height / H0)
        canvas.save()
        canvas.translate((width - W0 * s) / 2f, (height - H0 * s) / 2f)
        canvas.scale(s, s)

        drawGlow(canvas)
        drawPattern(canvas, t)
        drawRipples(canvas, t)
        drawLogo(canvas, t)
        drawWordmark(canvas, t)
        drawConnectors(canvas, t)
        drawFeature(canvas, t, 51f, context.getString(R.string.splash_map), 850f, 1300f) { c, tt, x, y -> mapIcon(c, tt, x, y) }
        drawFeature(canvas, t, 159f, context.getString(R.string.splash_antennas), 1000f, 2000f) { c, tt, x, y -> mastIcon(c, tt, x, y) }
        drawFeature(canvas, t, 267f, context.getString(R.string.splash_abnormal), 1150f, 2700f) { c, tt, x, y -> triangleIcon(c, tt, x, y) }
        drawFooter(canvas, t)

        canvas.restore()
        if (!reduce && !finishing) postInvalidateOnAnimation()
    }

    private val glowBottom = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Grön glöd bakom loggan och en varm guldglöd längst ner, som i Direct och Alif. */
    private fun drawGlow(c: Canvas) {
        glow.shader = RadialGradient(
            CX, 278f, 330f,
            intArrayOf(TEAL, Color.argb(0, Color.red(TEAL), Color.green(TEAL), Color.blue(TEAL))),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(-W0, -H0, W0 * 2, H0 * 2, glow)
        glowBottom.shader = RadialGradient(
            CX, H0 + 17f, 260f,
            intArrayOf(Color.argb(33, 232, 176, 74), Color.argb(0, 232, 176, 74)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(-W0, -H0, W0 * 2, H0 * 2, glowBottom)
    }

    // Bakgrund: ett svagt nät av celler där signaler färdas mellan masterna.
    private val nodes = arrayOf(
        floatArrayOf(34f, 92f, 600f), floatArrayOf(118f, 58f, 0f), floatArrayOf(206f, 112f, 1200f),
        floatArrayOf(300f, 66f, 300f), floatArrayOf(360f, 148f, 1800f), floatArrayOf(72f, 180f, 900f),
        floatArrayOf(28f, 732f, 1500f), floatArrayOf(98f, 806f, 400f), floatArrayOf(192f, 760f, 2100f),
        floatArrayOf(286f, 818f, 1000f), floatArrayOf(364f, 742f, 1700f),
    )
    private val edges = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4), intArrayOf(0, 5), intArrayOf(5, 2),
        intArrayOf(6, 7), intArrayOf(7, 8), intArrayOf(8, 9), intArrayOf(9, 10), intArrayOf(6, 8), intArrayOf(8, 10),
    )
    // Signaler: från nod, till nod, period, fördröjning
    private val signals = arrayOf(
        floatArrayOf(5f, 2f, 3400f, 0f), floatArrayOf(10f, 8f, 4200f, 1100f), floatArrayOf(6f, 8f, 3800f, 2000f),
        floatArrayOf(1f, 3f, 4600f, 700f),
    )

    private fun drawPattern(c: Canvas, t: Float) {
        val fade = ent(t, 100f, 800f)
        stroke.strokeWidth = 1f
        stroke.tint(MINT, 0.16f * fade)
        for ((i, j) in edges) c.drawLine(nodes[i][0], nodes[i][1], nodes[j][0], nodes[j][1], stroke)

        nodes.forEachIndexed { i, (x, y, d) ->
            val a = fade * lerp(0.25f, 0.85f, ping(t, 3200f, d))
            if (i == 2 || i == 8) {
                // Två av noderna är master: en liten ring som sänder
                stroke.strokeWidth = 1.2f
                stroke.tint(MINT, a)
                c.drawCircle(x, y, 4.5f, stroke)
                fill.tint(MINT, a)
                c.drawCircle(x, y, 1.6f, fill)
            } else {
                fill.tint(MINT, a)
                c.drawCircle(x, y, 2.3f, fill)
            }
        }

        if (reduce) return
        for ((from, to, period, delay) in signals) {
            val v = loop(t, period, delay)
            val a = when {
                v < 0.08f -> v / 0.08f
                v > 0.92f -> (1f - v) / 0.08f
                else -> 1f
            }
            val p = nodes[from.toInt()]
            val q = nodes[to.toInt()]
            fill.tint(GOLD, a * fade)
            c.drawCircle(lerp(p[0], q[0], v), lerp(p[1], q[1], v), 3f, fill)
        }
    }

    private fun drawRipples(c: Canvas, t: Float) {
        if (reduce) return
        stroke.strokeWidth = 1.5f
        for ((color, delay) in listOf(GOLD to 0f, MINT to 1700f)) {
            if (t < delay + 900f) continue
            val v = 1f - (1f - loop(t, 3400f, delay + 900f)).pow(2)
            stroke.tint(color, lerp(0.45f, 0f, v))
            c.drawCircle(CX, CY, 58f * lerp(1f, 2.6f, v), stroke)
        }
    }

    private fun drawLogo(c: Canvas, t: Float) {
        val pop = ent(t, 50f, 700f)
        val draw = ent(t, 150f, 1000f)
        val inner = ent(t, 550f, 800f)

        c.save()
        val sc = lerp(0.82f, 1f, pop)
        c.scale(sc, sc, CX, CY)

        // Guldringen ritar upp sig själv
        stroke.strokeWidth = 3.5f
        stroke.tint(GOLD, pop)
        rect.set(CX - 56f, CY - 56f, CX + 56f, CY + 56f)
        c.drawArc(rect, -90f, 360f * draw, false, stroke)

        // Stort T i Cinzel med ett mjukt guldsken, som i ikonen
        val dy = lerp(8f, 0f, inner)
        text.typeface = cinzel
        text.textSize = 84f
        text.letterSpacing = 0f
        text.getTextBounds("T", 0, 1, bounds)
        val baseline = CY + 7.5f + dy - bounds.exactCenterY()
        text.tint(GOLD, inner)
        text.setShadowLayer(14f, 0f, 0f, Color.argb((90 * inner).toInt(), 232, 176, 74))
        c.drawText("T", CX, baseline, text)
        text.clearShadowLayer()

        // Signalvågor på båda sidor om stammen: masten sänder
        val my = CY + 9.7f + dy
        stroke.strokeWidth = 2.6f
        for ((r, d) in listOf(16f to 0f, 24f to 350f)) {
            val a = inner * lerp(0.3f, 1f, ping(t, 1600f, d))
            stroke.tint(if (r < 20f) GOLD_PALE else GOLD_DIM, a)
            rect.set(CX - r, my - r, CX + r, my + r)
            c.drawArc(rect, 148f, 64f, false, stroke)
            c.drawArc(rect, -32f, 64f, false, stroke)
        }

        // Två punkter som kretsar på ringen: en mint-triangel och en ljusguld punkt.
        // Triangeln står alltid upprätt medan den kretsar.
        val ang = Math.toRadians((loop(t, 9000f) * 360f - 45f).toDouble())
        val ax = CX + 56f * kotlin.math.sin(ang).toFloat()
        val ay = CY - 56f * kotlin.math.cos(ang).toFloat()
        fill.tint(MINT, 0.18f * inner)
        c.drawCircle(ax, ay, 12f, fill)
        fill.tint(BG, inner)
        path.reset()
        path.moveTo(ax, ay - 9.5f); path.lineTo(ax + 9f, ay + 6.5f); path.lineTo(ax - 9f, ay + 6.5f); path.close()
        c.drawPath(path, fill)
        fill.tint(MINT, inner)
        path.reset()
        path.moveTo(ax, ay - 6f); path.lineTo(ax + 5.6f, ay + 4.2f); path.lineTo(ax - 5.6f, ay + 4.2f); path.close()
        c.drawPath(path, fill)

        val bx = 2 * CX - ax
        val by = 2 * CY - ay
        fill.tint(GOLD_PALE, 0.18f * inner)
        c.drawCircle(bx, by, 12f, fill)
        fill.tint(BG, inner)
        c.drawCircle(bx, by, 7.5f, fill)
        fill.tint(GOLD_PALE, inner)
        c.drawCircle(bx, by, 5f, fill)

        c.restore()
    }

    private fun drawWordmark(c: Canvas, t: Float) {
        val v = ent(t, 600f, 700f)
        val dy = lerp(16f, 0f, v)

        text.typeface = cinzel
        text.textSize = 42f
        text.letterSpacing = 0.33f
        text.tint(GOLD, v)
        text.setShadowLayer(18f, 0f, 0f, Color.argb((77 * v).toInt(), 232, 176, 74))
        c.drawText("TRACE", CX + 7f, 428f + dy, text)
        text.clearShadowLayer()

        text.typeface = sansBold
        text.textSize = 10.5f
        text.letterSpacing = 0.25f
        text.tint(MINT, v)
        c.drawText(tagline, CX + 1.3f, 456f + dy, text)
    }

    private fun drawConnectors(c: Canvas, t: Float) {
        val fade = ent(t, 1200f, 800f)
        if (fade <= 0f) return
        stroke.strokeWidth = 1.5f
        stroke.tint(LINE, fade)
        c.drawLine(123f, 564f, 159f, 564f, stroke)
        c.drawLine(231f, 564f, 267f, 564f, stroke)
        if (reduce) return
        packet(c, t, 123f, 159f, 0f, GOLD, fade)
        packet(c, t, 231f, 267f, 1100f, MINT, fade)
    }

    private fun packet(c: Canvas, t: Float, x0: Float, x1: Float, delay: Float, color: Int, fade: Float) {
        val v = loop(t, 2200f, delay + 1200f)
        val a = when {
            v < 0.08f -> v / 0.08f
            v > 0.92f -> (1f - v) / 0.08f
            else -> 1f
        }
        val x = lerp(x0, x1, v)
        c.save()
        c.rotate(45f, x, 564f)
        fill.tint(color, a * fade)
        c.drawRect(x - 2.5f, 564f - 2.5f, x + 2.5f, 564f + 2.5f, fill)
        c.restore()
    }

    private fun drawFeature(
        c: Canvas, t: Float, x: Float, label: String, enter: Float, glowAt: Float,
        icon: (Canvas, Float, Float, Float) -> Unit,
    ) {
        val e = ent(t, enter, 700f)
        if (e <= 0f) return
        val lab = ent(t, enter + 400f, 800f)
        val p = pulse(t, glowAt)
        val cx = x + 36f
        val cy = 564f + lerp(16f, 0f, e)

        fill.tint(GOLD, 0.05f * p)
        c.drawCircle(cx, cy, 50f * lerp(0.9f, 1.2f, p), fill)
        fill.tint(GOLD, 0.10f * p)
        c.drawCircle(cx, cy, 42f * lerp(0.95f, 1.15f, p), fill)

        fill.tint(PANEL, e)
        c.drawCircle(cx, cy, 36f, fill)
        stroke.strokeWidth = 1f
        stroke.tint(LINE, e)
        c.drawCircle(cx, cy, 36f, stroke)
        if (p > 0f) {
            stroke.strokeWidth = 1.5f
            stroke.tint(GOLD, p * e)
            c.drawCircle(cx, cy, 36f, stroke)
        }

        c.save()
        c.saveLayerAlpha(cx - 36f, cy - 36f, cx + 36f, cy + 36f, (e * 255).toInt())
        icon(c, t, cx, cy)
        c.restore()
        c.restore()

        text.typeface = sansBold
        text.textSize = 9.5f
        text.letterSpacing = 0.17f
        text.tint(INK, lab)
        c.drawText(label, cx + 0.8f, 624f, text)
    }

    /** Hopvikt karta med en mint-nål. */
    private fun mapIcon(c: Canvas, t: Float, x: Float, y: Float) {
        stroke.strokeWidth = 2f
        stroke.tint(GOLD, 1f)
        path.reset()
        path.moveTo(x - 15f, y - 9f); path.lineTo(x - 5f, y - 13f); path.lineTo(x + 5f, y - 9f); path.lineTo(x + 15f, y - 13f)
        path.lineTo(x + 15f, y + 10f); path.lineTo(x + 5f, y + 14f); path.lineTo(x - 5f, y + 10f); path.lineTo(x - 15f, y + 14f); path.close()
        path.moveTo(x - 5f, y - 13f); path.lineTo(x - 5f, y + 10f)
        path.moveTo(x + 5f, y - 9f); path.lineTo(x + 5f, y + 14f)
        c.drawPath(path, stroke)
        val bob = lerp(1.5f, -1.5f, ping(t, 1800f))
        fill.tint(MINT, 1f)
        c.drawCircle(x + 1f, y - 2f + bob, 3.6f, fill)
        path.reset()
        path.moveTo(x - 2.4f, y + bob); path.lineTo(x + 1f, y + 6f + bob); path.lineTo(x + 4.4f, y + bob); path.close()
        c.drawPath(path, fill)
    }

    /** Mast som sänder åt båda hållen. */
    private fun mastIcon(c: Canvas, t: Float, x: Float, y: Float) {
        stroke.strokeWidth = 2f
        stroke.tint(GOLD, 1f)
        path.reset()
        path.moveTo(x, y - 10f); path.lineTo(x - 8f, y + 15f)
        path.moveTo(x, y - 10f); path.lineTo(x + 8f, y + 15f)
        path.moveTo(x - 4.5f, y + 4f); path.lineTo(x + 4.5f, y + 4f)
        c.drawPath(path, stroke)
        fill.tint(GOLD_PALE, 1f)
        c.drawCircle(x, y - 10f, 2.6f, fill)
        for ((r, d) in listOf(7f to 0f, 12.5f to 300f)) {
            stroke.tint(MINT, lerp(0.2f, 1f, ping(t, 1500f, d)))
            rect.set(x - r, y - 10f - r, x + r, y - 10f + r)
            c.drawArc(rect, 140f, 80f, false, stroke)
            c.drawArc(rect, -40f, 80f, false, stroke)
        }
    }

    /** Varningstriangel, samma form som avvikelserna på kartan. */
    private fun triangleIcon(c: Canvas, t: Float, x: Float, y: Float) {
        val lift = lerp(0f, -2f, ping(t, 2000f))
        stroke.strokeWidth = 2.2f
        stroke.tint(GOLD, 1f)
        path.reset()
        path.moveTo(x, y - 13f + lift); path.lineTo(x + 14f, y + 11f + lift); path.lineTo(x - 14f, y + 11f + lift); path.close()
        c.drawPath(path, stroke)
        fill.tint(MINT, 1f)
        c.drawRoundRect(x - 1.4f, y - 5f + lift, x + 1.4f, y + 3.5f + lift, 1.4f, 1.4f, fill)
        c.drawCircle(x, y + 7f + lift, 1.6f, fill)
    }

    /** Etikett under funktionerna, som "End-to-end encrypted" i Direct, och utvecklaren längst ner. */
    private fun drawFooter(c: Canvas, t: Float) {
        val v = ent(t, 1500f, 800f)

        text.typeface = sansBold
        text.textSize = 10f
        text.letterSpacing = 0.22f
        val w = text.measureText(learning)
        val iconW = 14f
        val left = CX - (w + iconW + 8f) / 2f

        // Liten uppslagen bok
        stroke.strokeWidth = 1.5f
        stroke.tint(DIM, v)
        path.reset()
        val bx = left
        val by = 668f
        path.moveTo(bx + 7f, by - 4f)
        path.quadTo(bx + 3.5f, by - 6f, bx, by - 5f); path.lineTo(bx, by + 5f)
        path.quadTo(bx + 3.5f, by + 4f, bx + 7f, by + 6f)
        path.quadTo(bx + 10.5f, by + 4f, bx + 14f, by + 5f); path.lineTo(bx + 14f, by - 5f)
        path.quadTo(bx + 10.5f, by - 6f, bx + 7f, by - 4f); path.lineTo(bx + 7f, by + 6f)
        c.drawPath(path, stroke)

        text.textAlign = Paint.Align.LEFT
        text.tint(DIM, v)
        c.drawText(learning, left + iconW + 8f, by + 3.6f, text)
        text.textAlign = Paint.Align.CENTER

        text.typeface = sansMedium
        text.textSize = 10.5f
        text.letterSpacing = 0.04f
        text.tint(DIM, v * 0.85f)
        c.drawText(developer, CX, 696f, text)
    }
}
