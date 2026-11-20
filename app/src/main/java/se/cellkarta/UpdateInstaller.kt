package se.cellkarta

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Uppdatering inne i appen, som i CamOne: APK-filen laddas ner från GitHub, kontrolleras
 * (kontrollsumma, rätt app, nyare version, samma signeringsnyckel) och lämnas sedan till
 * Androids paketinstallerare. När den nya versionen är installerad startar Trace om av sig själv.
 */
object UpdateInstaller {

    private const val TAG = "TraceUpdate"

    enum class Error { NETWORK, CHECKSUM, WRONG_PACKAGE, NOT_NEWER, SIGNATURE, INSTALL }

    sealed interface State {
        data object None : State
        /** [progress] 0..1, eller -1 om storleken är okänd. */
        data class Downloading(val progress: Float) : State
        data class Ready(val file: File, val verified: Boolean) : State
        data class Failed(val error: Error) : State
        /** Android måste först tillåta att Trace installerar appar. */
        data object NeedsPermission : State
    }

    val state = MutableStateFlow<State>(State.None)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var pendingRelease: UpdateChecker.Release? = null

    /** Laddar ner, kontrollerar och startar installationen. */
    fun downloadAndInstall(ctx: Context, release: UpdateChecker.Release) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        // En redan kontrollerad fil för samma version används igen
        (state.value as? State.Ready)?.let { ready ->
            if (pendingRelease?.build == release.build && ready.file.exists()) { install(ctx); return }
        }
        pendingRelease = release
        job = scope.launch {
            state.value = State.Downloading(0f)
            val result = withContext(Dispatchers.IO) { download(app, release) }
            state.value = result
            if (result is State.Ready) install(ctx)
        }
    }

    private fun download(ctx: Context, release: UpdateChecker.Release): State {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Trace-${release.versionName}.apk")
        try {
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Trace/${UpdateChecker.currentVersionName(ctx)}")
            }
            val sha = MessageDigest.getInstance("SHA-256")
            var done = 0L
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
                var lastReport = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sha.update(buf, 0, n)
                            done += n
                            if (done - lastReport > 128 * 1024) {
                                lastReport = done
                                state.value = State.Downloading(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (release.size > 0 && done != release.size) throw IllegalStateException("Fel storlek $done")

            // 1. Kontrollsumman från GitHub
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && release.sha256 != hex) {
                file.delete()
                return State.Failed(Error.CHECKSUM)
            }
            // 2. Rätt app och nyare version
            val pm = ctx.packageManager
            val archive = archiveInfo(pm, file.path)
            if (archive == null || archive.packageName != ctx.packageName) {
                file.delete()
                return State.Failed(Error.WRONG_PACKAGE)
            }
            val installed = installedInfo(ctx, pm)
            if (installed != null && archive.longVersionCode <= installed.longVersionCode) {
                file.delete()
                return State.Failed(Error.NOT_NEWER)
            }
            // 3. Samma signeringsnyckel, annars vägrar Android installera ovanpå
            if (installed != null && !sameSigner(archive, installed)) {
                file.delete()
                return State.Failed(Error.SIGNATURE)
            }
            return State.Ready(file, verified = release.sha256 != null)
        } catch (e: Exception) {
            Log.w(TAG, "Nedladdningen misslyckades", e)
            file.delete()
            return State.Failed(Error.NETWORK)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(ctx: Context, pm: PackageManager): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull()

    private fun signers(info: PackageInfo): Set<String>? {
        val si = info.signingInfo ?: return null
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        if (sigs.isNullOrEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Går nyckeln inte att läsa ur filen får Androids installerare avgöra. */
    private fun sameSigner(a: PackageInfo, b: PackageInfo): Boolean {
        val x = signers(a) ?: return true
        val y = signers(b) ?: return true
        return x.intersect(y).isNotEmpty()
    }

    /** Lämnar den kontrollerade filen till Androids paketinstallerare. */
    fun install(ctx: Context) {
        val ready = state.value as? State.Ready ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            // Filen finns kvar: när tillåtelsen är given installeras den med samma knapp
            state.value = State.NeedsPermission
            readyFile = ready
            return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", ready.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            UpdateRestart.markPending(ctx)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Kunde inte starta installationen", e)
            state.value = State.Failed(Error.INSTALL)
        }
    }

    private var readyFile: State.Ready? = null

    /** Tillbaka från Androids inställning för att installera appar: fortsätt om tillåtelsen finns nu. */
    fun resumeAfterPermission(ctx: Context) {
        val ready = readyFile ?: return
        if (state.value != State.NeedsPermission) return
        if (!ctx.packageManager.canRequestPackageInstalls()) return
        readyFile = null
        state.value = ready
        install(ctx)
    }

    /** Nedladdningen ska inte ligga kvar för en version som inte längre är aktuell. */
    fun reset() {
        if (job?.isActive == true) return
        state.value = State.None
        readyFile = null
        pendingRelease = null
    }
}

/**
 * Omstart efter en uppdatering som Trace själv startade: när Android har installerat den nya
 * versionen öppnas Trace igen. Android kan stoppa en app från att öppna sig själv i bakgrunden,
 * därför kommer också en avisering "Trace är uppdaterad, tryck för att öppna".
 */
object UpdateRestart {
    private const val PREFS = "trace_update"
    private const val CHANNEL = "trace-update"
    private const val NOTIFICATION_ID = 7330
    private const val MAX_AGE_MS = 60L * 60 * 1000

    fun markPending(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("pending", true)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    fun afterReplaced(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("pending", false) || System.currentTimeMillis() - p.getLong("at", 0) > MAX_AGE_MS) return
        p.edit().putBoolean("pending", false).apply()
        val launch = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { ctx.startActivity(launch) }
        runCatching { notifyUpdated(ctx, launch) }
    }

    private fun notifyUpdated(ctx: Context, launch: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val c = Lang.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.update_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(ctx, NOTIFICATION_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_cell)
            .setColor(0xFFE8B04A.toInt())
            .setContentTitle(c.tr(R.string.update_done_title, UpdateChecker.currentVersionName(ctx)))
            .setContentText(c.getString(R.string.update_done_body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    /**
     * Versionen om Trace har uppdaterats sedan förra starten, annars null.
     * Första starten efter en ny installation ger null. Tar också bort aviseringen.
     */
    fun consumeJustUpdated(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong("last_run_code", -1L)
        val now = UpdateChecker.currentBuild(ctx)
        if (last != now) p.edit().putLong("last_run_code", now).apply()
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        return if (last in 1 until now) UpdateChecker.currentVersionName(ctx) else null
    }
}

/** MY_PACKAGE_REPLACED skickas till den nya versionen direkt efter installationen. */
class UpdateRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UpdateRestart.afterReplaced(context)
    }
}
