package se.cellkarta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Signalgraf för uppspelningen: signalstyrkan för den anslutna cellen under hela inspelningen,
 * en lodrät linje där cellen byts, händelser som små trianglar och en guldlinje för nuet.
 * Visar vad som händer även när telefonen ligger still.
 */
class SignalChart @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    data class Point(val ts: Long, val dbm: Int, val key: String)
    data class Mark(val ts: Long, val color: Int)

    private val d = resources.displayMetrics.density
    private var t0 = 0L
    private var t1 = 1L
    private var points: List<Point> = emptyList()
    private var marks: List<Mark> = emptyList()
    private var cursor = 0L

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.line)
        strokeWidth = 1f * d
        pathEffect = DashPathEffect(floatArrayOf(3f * d, 4f * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.dim)
        textSize = 9.5f * d
        typeface = ResourcesCompat.getFont(ctx, R.font.jakarta_medium)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.mint)
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 95, 196, 180)
        style = Paint.Style.FILL
    }
    private val change = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 243, 217, 160)
        strokeWidth = 1f * d
    }
    private val now = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.gold)
        strokeWidth = 2f * d
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun setData(t0: Long, t1: Long, points: List<Point>, marks: List<Mark>) {
        this.t0 = t0
        this.t1 = maxOf(t1, t0 + 1)
        this.points = points
        this.marks = marks
        invalidate()
    }

    fun setCursor(ts: Long) {
        cursor = ts
        invalidate()
    }

    private val left get() = 30f * d
    private fun x(ts: Long) = left + (width - left - 4f * d) * ((ts - t0).toFloat() / (t1 - t0).toFloat())
    private fun y(dbm: Int): Float {
        val top = 6f * d
        val bottom = height - 12f * d
        val v = ((dbm.coerceIn(-125, -50) + 125) / 75f)
        return bottom - (bottom - top) * v
    }

    override fun onDraw(c: Canvas) {
        // Rutnät med dBm
        for (level in intArrayOf(-60, -80, -100, -120)) {
            val yy = y(level)
            c.drawLine(left, yy, width.toFloat(), yy, grid)
            c.drawText("$level", 0f, yy + 3.5f * d, label)
        }
        if (points.isEmpty()) return

        // Cellbyten
        var prev: String? = null
        for (p in points) {
            if (prev != null && p.key != prev) c.drawLine(x(p.ts), 4f * d, x(p.ts), height - 10f * d, change)
            prev = p.key
        }

        // Signalstyrkan, med en svag fyllning under
        path.reset()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.ts), y(p.dbm)) else path.lineTo(x(p.ts), y(p.dbm)) }
        c.drawPath(path, line)
        path.lineTo(x(points.last().ts), height - 12f * d)
        path.lineTo(x(points.first().ts), height - 12f * d)
        path.close()
        c.drawPath(path, fill)

        // Händelser som små trianglar längst ner
        for (m in marks) {
            val xx = x(m.ts)
            val yy = height - 1f * d
            mark.color = m.color
            path.reset()
            path.moveTo(xx, yy - 9f * d); path.lineTo(xx + 5f * d, yy); path.lineTo(xx - 5f * d, yy); path.close()
            c.drawPath(path, mark)
        }

        // Nuet
        val cx = x(cursor.coerceIn(t0, t1))
        c.drawLine(cx, 2f * d, cx, height - 10f * d, now)
    }
}
