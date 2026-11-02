package se.cellkarta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.text.Html
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.OverlayWithIW
import org.osmdroid.views.overlay.infowindow.InfoWindow

/** Markeringar på kartan: utropstecken för onormala antenner, frågetecken för var det hände. */
object MapMarks {

    val COLOR_MOVING = Color.rgb(226, 75, 74)
    val COLOR_STRONG = Color.rgb(212, 83, 126)
    val COLOR_SHORT = Color.rgb(29, 158, 117)
    val COLOR_NEW = Color.rgb(232, 176, 74)
    val COLOR_DOWNGRADE = Color.rgb(127, 119, 221)

    fun colorFor(type: String): Int = when (type) {
        CellDb.FLAG_MOVING -> COLOR_MOVING
        CellDb.FLAG_STRONG -> COLOR_STRONG
        CellDb.FLAG_SHORT -> COLOR_SHORT
        CellDb.FLAG_DOWNGRADE -> COLOR_DOWNGRADE
        else -> COLOR_NEW
    }

    private val cache = HashMap<String, BitmapDrawable>()

    /**
     * Rund markering med ett tecken i mitten: mörk fyllning, färgad ring och ett svagt sken runt om,
     * i samma stil som ringen i Trace-ikonen.
     */
    fun icon(ctx: Context, symbol: String, color: Int, sizeDp: Float = 30f): BitmapDrawable =
        cache.getOrPut("$symbol-$color-$sizeDp") {
            val d = ctx.resources.displayMetrics.density
            val size = (sizeDp * d).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            p.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
            c.drawCircle(r, r, r, p)
            p.color = Color.argb(240, 23, 38, 41)
            c.drawCircle(r, r, r * 0.72f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.4f * d
            p.color = color
            c.drawCircle(r, r, r * 0.72f, p)

            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.textSize = size * 0.42f
            p.typeface = ResourcesCompat.getFont(ctx, R.font.jakarta_bold)
            p.color = color
            c.drawText(symbol, r, r - (p.descent() + p.ascent()) / 2f, p)
            BitmapDrawable(ctx.resources, bmp)
        }

    /** Egen mastmarkering: guldring med en sändande mast, i samma stil som Trace-ikonen. */
    fun mastIcon(ctx: Context, sizeDp: Float = 38f): BitmapDrawable = cache.getOrPut("mast-$sizeDp") {
        val d = ctx.resources.displayMetrics.density
        val size = (sizeDp * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val gold = Color.rgb(232, 176, 74)
        val pale = Color.rgb(243, 217, 160)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = Color.argb(80, 232, 176, 74)
        c.drawCircle(r, r, r, p)
        p.color = Color.argb(245, 23, 38, 41)
        c.drawCircle(r, r, r * 0.74f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.4f * d
        p.color = gold
        c.drawCircle(r, r, r * 0.74f, p)

        // Masten: två ben, en tvärslå och en topp som sänder
        val u = size / 38f
        p.strokeWidth = 2f * u
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(r, r - 7 * u, r - 5.5f * u, r + 9 * u, p)
        c.drawLine(r, r - 7 * u, r + 5.5f * u, r + 9 * u, p)
        c.drawLine(r - 3f * u, r + 2.5f * u, r + 3f * u, r + 2.5f * u, p)
        p.color = pale
        for (rad in floatArrayOf(4.5f * u, 8f * u)) {
            val o = android.graphics.RectF(r - rad, r - 7 * u - rad, r + rad, r - 7 * u + rad)
            c.drawArc(o, 145f, 70f, false, p)
            c.drawArc(o, -35f, 70f, false, p)
        }
        p.style = Paint.Style.FILL
        c.drawCircle(r, r - 7 * u, 1.8f * u, p)
        BitmapDrawable(ctx.resources, bmp)
    }

    /**
     * Förklaringsruta med tunn kant. Den ligger kvar, även när kartan ritas om,
     * tills man trycker bredvid den på kartan.
     */
    class Bubble(map: MapView) : InfoWindow(R.layout.info_bubble, map) {
        /** Vilken markering rutan hör till, så att den kan öppnas igen efter omritning. */
        var openKey: String? = null

        override fun onOpen(item: Any?) {
            val m = item as? OverlayWithIW ?: return
            openKey = (item as? org.osmdroid.views.overlay.Marker)?.relatedObject as? String
            mView.findViewById<TextView>(R.id.bubbleTitle).text = m.title
            mView.findViewById<TextView>(R.id.bubbleBody).text =
                Html.fromHtml(m.snippet.orEmpty(), Html.FROM_HTML_MODE_COMPACT)
            // Tryck i själva rutan gör ingenting, så att den inte stängs av misstag.
            mView.setOnClickListener { }
        }

        override fun onClose() {}
    }
}
