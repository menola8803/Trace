package se.cellkarta

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

class Notifier(private val ctx: Context) {

    companion object {
        const val ONGOING_ID = 1
        private const val CH_ONGOING = "monitor"
        private const val CH_ALERT = "alerts"
        private val nextId = AtomicInteger(100)
    }

    private val nm = NotificationManagerCompat.from(ctx)

    init {
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_ONGOING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(ctx.getString(R.string.channel_monitor))
                .build()
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_ALERT, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(ctx.getString(R.string.channel_alerts))
                .build()
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    fun ongoing(): Notification =
        NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_cell)
            .setContentTitle("Trace")
            .setContentText(ctx.getString(R.string.ongoing_text))
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    @SuppressLint("MissingPermission")
    fun alert(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        nm.notify(
            nextId.getAndIncrement(),
            NotificationCompat.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_cell)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
        )
    }
}
