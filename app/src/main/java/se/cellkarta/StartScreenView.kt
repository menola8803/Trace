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
    private val dash = DashPathEffect(floatArrayOf(5f, 4.2f), 0f)

    init {
        isClickable = true
        contentDescription = "Trace. Developer: Claes Sundman S.N.C AB. For learning purpose only."
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
        drawFeature(canvas, t, 51f, "KARTA", 850f, 1300f) { c, tt, x, y -> mapIcon(c, tt, x, y) }
        drawFeature(canvas, t, 159f, "ANTENNER", 1000f, 2000f) { c, tt, x, y -> mastIcon(c, tt, x, y) }
        drawFeature(canvas, t, 267f, "AVVIKELSER", 1150f, 2700f) { c, tt, x, y -> triangleIcon(c, tt, x, y) }
        drawFooter(canvas, t)

        canvas.restore()
        if (!reduce && !finishing) postInvalidateOnAnimation()
    }

    private fun drawGlow(c: Canvas) {
        glow.shader = RadialGradient(
            CX, CY, 320f,
            intArrayOf(Color.argb(210, Color.red(TEAL), Color.green(TEAL), Color.blue(TEAL)), Color.argb(0, 11, 20, 22)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(-W0, -H0, W0 * 2, H0 * 2, glow)
    }

    /** Svag karta i bakgrunden: några gator och celler som blinkar till. */
    private fun drawPattern(c: Canvas, t: Float) {
        val fade = ent(t, 100f, 900f)
        stroke.strokeWidth = 1f
        stroke.tint(MINT, 0.10f * fade)
        path.reset()
        path.moveTo(0f, 150f); path.lineTo(120f, 172f); path.lineTo(390f, 128f)
        path.moveTo(0f, 700f); path.lineTo(170f, 688f); path.lineTo(390f, 712f)
        path.moveTo(40f, 0f); path.lineTo(64f, 844f)
        path.moveTo(352f, 0f); path.lineTo(330f, 844f)
        c.drawPath(path, stroke)

        val cells = arrayOf(
            floatArrayOf(22f, 214f, 600f), floatArrayOf(372f, 196f, 1500f), floatArrayOf(24f, 420f, 300f),
            floatArrayOf(366f, 440f, 1100f), floatArrayOf(28f, 612f, 1200f), floatArrayOf(364f, 620f, 300f),
        )
        stroke.strokeWidth = 1.2f
        cells.forEachIndexed { i, (x, y, d) ->
            val a = fade * lerp(0.2f, 0.75f, ping(t, 3600f, d))
            stroke.tint(MINT, a)
            if (i % 3 == 1) {
                path.reset()
                path.moveTo(x, y - 6f); path.lineTo(x + 6f, y + 4.5f); path.lineTo(x - 6f, y + 4.5f); path.close()
                c.drawPath(path, stroke)
            } else {
                c.drawCircle(x, y, 5f, stroke)
            }
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

        // Streckad inre cirkel som långsamt roterar, som en radar
        c.save()
        c.rotate(loop(t, 40_000f) * 360f, CX, CY)
        stroke.strokeWidth = 1.2f
        stroke.pathEffect = dash
        stroke.tint(GOLD_DIM, inner * pop)
        c.drawCircle(CX, CY, 42f, stroke)
        stroke.pathEffect = null
        c.restore()

        // Guldringen ritar upp sig själv
        stroke.strokeWidth = 3.5f
        stroke.tint(GOLD, pop)
        rect.set(CX - 56f, CY - 56f, CX + 56f, CY + 56f)
        c.drawArc(rect, -90f, 360f * draw, false, stroke)

        // T i Cinzel, med ett mjukt guldsken
        val dy = lerp(8f, 0f, inner)
        text.typeface = cinzel
        text.textSize = 60f
        text.letterSpacing = 0f
        text.tint(GOLD, inner)
        text.setShadowLayer(14f, 0f, 0f, Color.argb((90 * inner).toInt(), 232, 176, 74))
        c.drawText("T", CX, CY + 30f + dy, text)
        text.clearShadowLayer()

        // Mastens topp sänder
        val tipY = CY - 28f + dy
        fill.tint(GOLD_PALE, inner)
        c.drawCircle(CX, tipY, 3.2f, fill)
        stroke.strokeWidth = 2.2f
        for ((r, d) in listOf(9f to 0f, 16f to 350f)) {
            val a = inner * lerp(0.25f, 1f, ping(t, 1600f, d))
            stroke.tint(if (r < 12f) GOLD_PALE else GOLD, a)
            rect.set(CX - r, tipY - r, CX + r, tipY + r)
            c.drawArc(rect, 145f, 70f, false, stroke)
            c.drawArc(rect, -35f, 70f, false, stroke)
        }

        // Två punkter som kretsar på ringen: en mint-triangel och en ljusguld punkt
        c.save()
        c.rotate(loop(t, 9000f) * 360f - 45f, CX, CY)
        val ax = CX
        val ay = CY - 56f
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

        val by = CY + 56f
        fill.tint(GOLD_PALE, 0.18f * inner)
        c.drawCircle(ax, by, 12f, fill)
        fill.tint(BG, inner)
        c.drawCircle(ax, by, 7.5f, fill)
        fill.tint(GOLD_PALE, inner)
        c.drawCircle(ax, by, 5f, fill)
        c.restore()

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
        c.drawText("— SER ANTENNERNA RUNT DIG —", CX + 1.3f, 456f + dy, text)
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

    private fun drawFooter(c: Canvas, t: Float) {
        val v = ent(t, 1400f, 800f)
        text.typeface = sansMedium
        text.textSize = 11f
        text.letterSpacing = 0.02f
        text.tint(DIM, v)
        c.drawText("Developer: Claes Sundman S.N.C AB - 2026", CX, 752f, text)
        text.typeface = sansBold
        text.textSize = 10.5f
        text.letterSpacing = 0.12f
        text.tint(MINT, v)
        c.drawText("FOR LEARNING PURPOSE ONLY", CX + 0.6f, 772f, text)
    }
}
