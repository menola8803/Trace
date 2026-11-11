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
    /** Beräknad mast: mint, för att skiljas från egna markerade master i guld. */
    val COLOR_TRI = Color.rgb(95, 196, 180)

    fun colorFor(type: String): Int = when (type) {
        CellDb.FLAG_MOVING -> COLOR_MOVING
        CellDb.FLAG_STRONG -> COLOR_STRONG
        CellDb.FLAG_SHORT -> COLOR_SHORT
        CellDb.FLAG_DOWNGRADE -> COLOR_DOWNGRADE
        else -> COLOR_NEW
    }

    /**
     * Bilderna sparas en gång, men varje knapp och markering får ett eget Drawable.
     * Kartan ändrar ett Drawables gränser när den ritar markeringen, så ett delat
     * Drawable försvinner ur knappar och teckenförklaringen.
     */
    private val cache = HashMap<String, Bitmap>()

    private fun drawable(ctx: Context, bmp: Bitmap) = BitmapDrawable(ctx.resources, bmp)

    /**
     * Rund markering med ett tecken i mitten: mörk fyllning, färgad ring och ett svagt sken runt om,
     * i samma stil som ringen i Trace-ikonen.
     */
    fun icon(ctx: Context, symbol: String, color: Int, sizeDp: Float = 30f): BitmapDrawable = drawable(ctx,
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
            bmp
        })

    /** Egen cell: färgad prick med nättypens siffra, 2, 3, 4 eller 5. */
    fun netDot(ctx: Context, net: Net, sizeDp: Float = 20f): BitmapDrawable = drawable(ctx,
        cache.getOrPut("net-${net.name}-$sizeDp") {
            val d = ctx.resources.displayMetrics.density
            val size = (sizeDp * d).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = net.color
            c.drawCircle(r, r, r - 1.2f * d, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.4f * d
            p.color = Color.argb(230, 15, 26, 28)
            c.drawCircle(r, r, r - 1.2f * d, p)
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.textSize = size * 0.58f
            p.typeface = ResourcesCompat.getFont(ctx, R.font.jakarta_bold)
            p.color = Color.rgb(15, 26, 28)
            c.drawText(net.digit, r, r - (p.descent() + p.ascent()) / 2f, p)
            bmp
        })

    /** Egen mastmarkering: guldring med en sändande mast, i samma stil som Trace-ikonen. */
    fun mastIcon(ctx: Context, sizeDp: Float = 38f, ring: Int = Color.rgb(232, 176, 74)): BitmapDrawable =
        drawable(ctx, cache.getOrPut("mast-$sizeDp-$ring") {
        val d = ctx.resources.displayMetrics.density
        val size = (sizeDp * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val gold = ring
        val pale = Color.rgb(243, 217, 160)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = Color.argb(80, Color.red(ring), Color.green(ring), Color.blue(ring))
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
        bmp
    })

    /**
     * Förklaringsruta med tunn kant. Den ligger kvar, även när kartan ritas om,
     * tills man trycker bredvid den på kartan.
     */
    class Bubble(map: MapView) : InfoWindow(R.layout.info_bubble, map) {
        /** Vilken markering rutan hör till, så att den kan öppnas igen efter omritning. */
        var openKey: String? = null

        /** Text för en knapp i rutan, till exempel Beräkna mast, eller null för ingen knapp. */
        var actionLabel: ((org.osmdroid.views.overlay.Marker) -> String?)? = null
        var onAction: ((org.osmdroid.views.overlay.Marker) -> Unit)? = null

        override fun onOpen(item: Any?) {
            val m = item as? OverlayWithIW ?: return
            openKey = (item as? org.osmdroid.views.overlay.Marker)?.relatedObject as? String
            mView.findViewById<TextView>(R.id.bubbleTitle).text = m.title
            mView.findViewById<TextView>(R.id.bubbleBody).text =
                Html.fromHtml(m.snippet.orEmpty(), Html.FROM_HTML_MODE_COMPACT)
            // Länk till CellMapper, om markeringen har en (lagras i subDescription)
            val link = mView.findViewById<TextView>(R.id.bubbleLink)
            val url = (item as? org.osmdroid.views.overlay.Marker)?.subDescription
            if (!url.isNullOrBlank() && url.startsWith("https://")) {
                link.visibility = android.view.View.VISIBLE
                link.setOnClickListener { UpdateChecker.open(mView.context, url) }
            } else {
                link.visibility = android.view.View.GONE
            }
            val action = mView.findViewById<TextView>(R.id.bubbleAction)
            val marker = item as? org.osmdroid.views.overlay.Marker
            val label = marker?.let { actionLabel?.invoke(it) }
            if (marker != null && label != null) {
                action.text = label
                action.visibility = android.view.View.VISIBLE
                action.setOnClickListener { onAction?.invoke(marker) }
            } else {
                action.visibility = android.view.View.GONE
            }
            // Tryck i själva rutan gör ingenting, så att den inte stängs av misstag.
            mView.setOnClickListener { }
        }

        override fun onClose() {}
    }
}
