package se.cellkarta

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * Apptrafik, nivå 1: hur mycket data varje app har skickat och tagit emot, enligt Androids
 * egen räkning. Kräver användningsåtkomst, som användaren slår på i systemets inställningar.
 */
object AppTraffic {

    class Usage(val uid: Int) {
        var mobileRx = 0L; var mobileTx = 0L
        var wifiRx = 0L; var wifiTx = 0L
        val mobile get() = mobileRx + mobileTx
        val wifi get() = wifiRx + wifiTx
        val total get() = mobile + wifi
        val rx get() = mobileRx + wifiRx
        val tx get() = mobileTx + wifiTx
    }

    data class AppName(val label: String, val icon: Drawable?)

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openAccessSettings(ctx: Context) {
        val withPkg = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        runCatching { ctx.startActivity(withPkg) }
            .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
    }

    /** Data per app (uid) mellan två tidpunkter, över mobilnät och wifi. */
    @Suppress("DEPRECATION")
    fun usage(ctx: Context, from: Long, to: Long): Map<Int, Usage> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        val out = HashMap<Int, Usage>()
        for (type in intArrayOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
            val stats = runCatching { nsm.querySummary(type, null, from, to) }.getOrNull() ?: continue
            try {
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    val u = out.getOrPut(b.uid) { Usage(b.uid) }
                    if (type == ConnectivityManager.TYPE_MOBILE) { u.mobileRx += b.rxBytes; u.mobileTx += b.txBytes }
                    else { u.wifiRx += b.rxBytes; u.wifiTx += b.txBytes }
                }
            } finally {
                stats.close()
            }
        }
        return out
    }

    /** Appens namn och ikon. [pkgHint] används när appen har avinstallerats sedan uppslaget sparades. */
    fun name(ctx: Context, uid: Int, pkgHint: String? = null): AppName {
        val pm = ctx.packageManager
        when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> return AppName(ctx.getString(R.string.traffic_removed), null)
            NetworkStats.Bucket.UID_TETHERING -> return AppName(ctx.getString(R.string.traffic_tethering), null)
            Process.SYSTEM_UID, 0 -> return AppName(ctx.getString(R.string.traffic_system), runCatching { pm.getApplicationIcon("android") }.getOrNull())
            Process.INVALID_UID -> return AppName(ctx.getString(R.string.traffic_unknown_app), null)
        }
        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()?.toList().orEmpty()
        val pkg = pkgs.firstOrNull() ?: pkgHint
        if (pkg != null) {
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString() + if (pkgs.size > 1) " +${pkgs.size - 1}" else ""
                return AppName(label, pm.getApplicationIcon(info))
            }
            return AppName(pkg, null)
        }
        return AppName(if (uid < Process.FIRST_APPLICATION_UID) ctx.getString(R.string.traffic_system)
            else ctx.getString(R.string.traffic_unknown_app) + " ($uid)", null)
    }

    fun packageOf(ctx: Context, uid: Int): String? =
        runCatching { ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
}

/**
 * Apptrafik, nivå 2: servernamnen som apparna har slagit upp, en rad per app och namn.
 * Fylls av DnsVpnService.
 */
class TrafficDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "traffic.db", null, 1) {

    data class Host(val uid: Int, val pkg: String?, val host: String, val firstTs: Long, val lastTs: Long, val count: Int)

    companion object {
        @Volatile private var instance: TrafficDb? = null
        fun get(ctx: Context): TrafficDb = instance ?: synchronized(this) {
            instance ?: TrafficDb(ctx.applicationContext).also { instance = it }
        }
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE hosts(uid INTEGER NOT NULL, pkg TEXT, host TEXT NOT NULL, first_ts INTEGER NOT NULL, " +
                "last_ts INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(uid, host))"
        )
        db.execSQL("CREATE INDEX idx_hosts_last ON hosts(last_ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Räknar ett uppslag. Android 10 saknar UPSERT i SQLite, därför uppdatering först och sedan ny rad. */
    @Synchronized
    fun logLookup(uid: Int, pkg: String?, host: String, ts: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val upd = db.compileStatement("UPDATE hosts SET last_ts=?, count=count+1 WHERE uid=? AND host=?").apply {
                bindLong(1, ts); bindLong(2, uid.toLong()); bindString(3, host)
            }
            val changed = upd.executeUpdateDelete()
            upd.close()
            if (changed == 0) {
                db.insert("hosts", null, ContentValues().apply {
                    put("uid", uid); if (pkg != null) put("pkg", pkg) else putNull("pkg")
                    put("host", host); put("first_ts", ts); put("last_ts", ts); put("count", 1)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Alla namn som slagits upp sedan tidpunkten, per app, mest uppslagna först. */
    fun hostsSince(since: Long): Map<Int, List<Host>> =
        readableDatabase.rawQuery(
            "SELECT uid, pkg, host, first_ts, last_ts, count FROM hosts WHERE last_ts >= ? ORDER BY count DESC",
            arrayOf(since.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Host(c.getInt(0), if (c.isNull(1)) null else c.getString(1), c.getString(2),
                    c.getLong(3), c.getLong(4), c.getInt(5)))
            }
        }.groupBy { it.uid }

    fun lookups(): Long = DatabaseUtils.longForQuery(readableDatabase, "SELECT COALESCE(SUM(count), 0) FROM hosts", null)

    fun clear() {
        writableDatabase.delete("hosts", null, null)
    }
}
