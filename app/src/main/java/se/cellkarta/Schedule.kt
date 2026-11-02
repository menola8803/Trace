package se.cellkarta

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schemalagd inspelning: starta vid en tid (t.ex. 21:00) och stoppa vid en annan (t.ex. 07:00),
 * valda veckodagar. Ett fönster som slutar tidigare än det börjar fortsätter till nästa dag.
 * Veckodagen gäller dagen då fönstret börjar.
 */
object Schedule {

    private const val FILE = "schedule"
    private const val TAG = "Trace"
    const val ACTION_ALARM = "se.cellkarta.SCHEDULE_ALARM"
    const val EXTRA_START = "start"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", false)
    fun startMin(ctx: Context) = sp(ctx).getInt("start", 21 * 60)
    fun endMin(ctx: Context) = sp(ctx).getInt("end", 7 * 60)
    /** Bitmask: måndag = bit 0 … söndag = bit 6. */
    fun days(ctx: Context) = sp(ctx).getInt("days", 0b1111111)

    fun setEnabled(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean("enabled", on) }
    fun setStart(ctx: Context, min: Int) = sp(ctx).edit { putInt("start", min) }
    fun setEnd(ctx: Context, min: Int) = sp(ctx).edit { putInt("end", min) }
    fun setDays(ctx: Context, mask: Int) = sp(ctx).edit { putInt("days", mask) }

    fun startedBySchedule(ctx: Context) = sp(ctx).getBoolean("started_by_schedule", false)
    fun setStartedBySchedule(ctx: Context, on: Boolean) = sp(ctx).edit { putBoolean("started_by_schedule", on) }

    private fun dayOn(mask: Int, day: DayOfWeek) = mask and (1 shl (day.value - 1)) != 0

    private fun window(date: LocalDate, start: Int, end: Int, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime> {
        val s = date.atStartOfDay(zone).plusMinutes(start.toLong())
        val endDate = if (end <= start) date.plusDays(1) else date
        val e = endDate.atStartOfDay(zone).plusMinutes(end.toLong())
        return s to e
    }

    /** Slutet på det fönster som pågår just nu, eller null. */
    fun activeUntil(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!enabled(ctx)) return null
        val mask = days(ctx)
        for (d in listOf(now.toLocalDate().minusDays(1), now.toLocalDate())) {
            if (!dayOn(mask, d.dayOfWeek)) continue
            val (s, e) = window(d, startMin(ctx), endMin(ctx), now.zone)
            if (!now.isBefore(s) && now.isBefore(e)) return e
        }
        return null
    }

    /** Nästa start efter nu, eller null om inga dagar är valda. */
    fun nextStart(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        if (!enabled(ctx)) return null
        val mask = days(ctx)
        for (i in 0..7) {
            val d = now.toLocalDate().plusDays(i.toLong())
            if (!dayOn(mask, d.dayOfWeek)) continue
            val (s, _) = window(d, startMin(ctx), endMin(ctx), now.zone)
            if (s.isAfter(now)) return s
        }
        return null
    }

    private fun pending(ctx: Context, start: Boolean): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1,
        Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_START, start),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * Ställer nästa väckning: slutet på pågående fönster, annars nästa start.
     * Startar också inspelningen direkt om ett fönster redan pågår.
     */
    fun apply(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, true))
        if (!enabled(ctx)) return

        val active = activeUntil(ctx)
        if (active != null) {
            if (!LiveStatus.state.value.running) startRecording(ctx)
            set(am, active.toInstant().toEpochMilli(), pending(ctx, false))
        } else {
            val next = nextStart(ctx) ?: return
            set(am, next.toInstant().toEpochMilli(), pending(ctx, true))
        }
    }

    private fun set(am: AlarmManager, at: Long, pi: PendingIntent) {
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun startRecording(ctx: Context) {
        runCatching {
            setStartedBySchedule(ctx, true)
            ContextCompat.startForegroundService(ctx, Intent(ctx, CellMonitorService::class.java))
        }.onFailure { Log.w(TAG, "Kunde inte starta schemalagd inspelning", it) }
    }

    fun stopRecording(ctx: Context) {
        if (!startedBySchedule(ctx)) return // startad för hand: låt den fortsätta
        setStartedBySchedule(ctx, false)
        ctx.stopService(Intent(ctx, CellMonitorService::class.java))
    }

    fun formatMin(min: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", min / 60, min % 60)

    fun millisToZoned(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
}

/** Tar emot schemats väckningar och omstart av telefonen. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Schedule.ACTION_ALARM -> {
                if (intent.getBooleanExtra(Schedule.EXTRA_START, true)) Schedule.startRecording(ctx)
                else Schedule.stopRecording(ctx)
            }
        }
        // Efter varje väckning, omstart eller ändrad tid: ställ nästa.
        Schedule.apply(ctx)
    }
}
