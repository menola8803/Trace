package se.cellkarta

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Etiketter för inspelningarna: Liveinspelning, Schemalagd inspelning, Tidigare inspelning, Triangulering. */
object Recordings {

    fun kindLabel(ctx: Context, kind: String): String = ctx.getString(when (kind) {
        CellDb.KIND_LIVE -> R.string.rec_live
        CellDb.KIND_SCHEDULED -> R.string.rec_scheduled
        else -> R.string.rec_earlier
    })

    private fun kindColor(kind: String) = when (kind) {
        CellDb.KIND_LIVE -> R.color.mint
        CellDb.KIND_SCHEDULED -> R.color.gold
        else -> R.color.dim
    }

    /** Rubrik i uppspelningen, till exempel "Liveinspelning · torsdag 12 november 20:18". */
    fun title(ctx: Context, r: Recording): String {
        val loc = Lang.locale(ctx)
        val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM HH:mm", loc).withZone(ZoneId.systemDefault())
        return kindLabel(ctx, r.kind) + " · " + fmt.format(Instant.ofEpochMilli(r.from))
    }

    /** Små etiketter i rad: typ av inspelning, och Triangulering om masterna räknades under den. */
    fun pills(ctx: Context, r: Recording): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(pill(ctx, kindLabel(ctx, r.kind), kindColor(r.kind)))
        if (r.tri) addView(pill(ctx, ctx.getString(R.string.rec_tri), R.color.mint).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = (6 * ctx.resources.displayMetrics.density).toInt()
        })
    }

    private fun pill(ctx: Context, text: String, colorRes: Int): TextView {
        val d = ctx.resources.displayMetrics.density
        val color = ContextCompat.getColor(ctx, colorRes)
        return TextView(ctx).apply {
            this.text = text
            textSize = 11.5f
            setTextColor(color)
            typeface = ResourcesCompat.getFont(ctx, R.font.jakarta_bold)
            setPadding((9 * d).toInt(), (3 * d).toInt(), (9 * d).toInt(), (3 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 50 * d
                setStroke((1 * d).toInt().coerceAtLeast(1), color)
                setColor(android.graphics.Color.argb(28, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)))
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
    }
}
