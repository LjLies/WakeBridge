package io.github.ljlies.wakebridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Phone-side timers for the app's own state. None needs to be exact (the alarm that wakes the
 * user lives on the watch, and the phone backup alarm has its own exact alarm), so inexact alarms
 * are used.
 */
object Scheduler {
    private const val ACTION_CHECK = "io.github.ljlies.wakebridge.CHECK"
    private const val ACTION_NIGHT = "io.github.ljlies.wakebridge.NIGHT"

    /** Schedules the next moment something is due for the session: alarm time, deadline or cleanup. */
    fun scheduleSession(context: Context, state: Session.State?) {
        val due = listOfNotNull(state?.wakeAt, state?.deadline, state?.latest, Session.cleanupDueAt(context)).minOrNull()
        set(context, ACTION_CHECK, due)
    }

    /**
     * Schedules the next night start: it arms the night's session ("Every night", or "Tonight"
     * once) and ends naps running into the night.
     */
    fun scheduleNight(context: Context) {
        val settings = Settings(context)
        val needed = settings.nightEvery || settings.nightTonight || settings.dayEvery
        set(context, ACTION_NIGHT, if (needed) nextOccurrence(settings.nightStart) else null)
    }

    /** "Arm tonight": now if the night has started, else at its start. */
    fun armTonight(context: Context) {
        val settings = Settings(context)
        settings.nightTonight = true
        if (settings.isNightTime(Session.now().atZone(Session.clock.zone).toLocalTime())) armNight(context)
        scheduleNight(context)
        Session.onChanged(context)
    }

    fun disarmTonight(context: Context) {
        Settings(context).nightTonight = false
        scheduleNight(context)
        Session.onChanged(context)
    }

    /** The first moment after [after] when the clock shows [time]. */
    fun nextOccurrence(time: LocalTime, after: Instant = Session.now()): Instant {
        val zone = Session.clock.zone
        val date = after.atZone(zone).toLocalDate()
        val sameDay = date.atTime(time).atZone(zone).toInstant()
        return if (sameDay > after) sameDay else date.plusDays(1).atTime(time).atZone(zone).toInstant()
    }

    /** The last moment, up to [before], when the clock showed [time]. */
    fun previousOccurrence(time: LocalTime, before: Instant = Session.now()): Instant {
        val zone = Session.clock.zone
        val date = before.atZone(zone).toLocalDate()
        val sameDay = date.atTime(time).atZone(zone).toInstant()
        return if (sameDay <= before) sameDay else date.minusDays(1).atTime(time).atZone(zone).toInstant()
    }

    /**
     * Puts back what a restart or a force stop wiped: the app's timers and the phone alarm, and
     * tonight's night if its start was missed meanwhile. After a restart, on app launch and when
     * the app is updated.
     */
    fun restore(context: Context) {
        val state = Session.checkTimers(context)
        scheduleSession(context, state)
        catchUpNight(context)
        scheduleNight(context)
        PhoneAlarm.pendingAt(context)?.let { PhoneAlarm.schedule(context, it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("scheduler", Context.MODE_PRIVATE)

    /** Arms the night now if it has started without being armed (phone off, app stopped). */
    private fun catchUpNight(context: Context) {
        val settings = Settings(context)
        val now = Session.now()
        val started = previousOccurrence(settings.nightStart, now)
        // First run of a version that keeps track: assume the last night start was handled
        if (!prefs(context).contains("night_handled")) {
            prefs(context).edit().putLong("night_handled", started.toEpochMilli()).apply()
            return
        }
        if (!settings.nightEvery && !settings.nightTonight) return
        if (!settings.isNightTime(now.atZone(Session.clock.zone).toLocalTime())) return
        val handled = prefs(context).getLong("night_handled", 0)
        if (handled >= started.toEpochMilli()) return
        EventLog.add(context, context.getString(R.string.log_night_caught_up))
        armNight(context)
    }

    private fun set(context: Context, action: String, at: Instant?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, TimerReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(intent)
        if (at == null) return
        // Exact where allowed: inexact ones can be put off for long, or limited to a few a day
        // for apps Android thinks are rarely used
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    /**
     * Night starts. A nap still running hands over to the night session, or is just cancelled
     * when no night is armed; a night session already running is left alone.
     */
    fun armNight(context: Context) {
        val settings = Settings(context)
        val current = Session.checkTimers(context)
        prefs(context).edit()
            .putLong("night_handled", previousOccurrence(settings.nightStart).toEpochMilli()).apply()
        if (!settings.nightEvery && !settings.nightTonight) {
            if (current?.kind == Session.Kind.NAP) {
                Session.cancel(context, context.getString(R.string.log_nap_ended_by_night))
            }
            return
        }
        settings.nightTonight = false
        if (current?.kind == Session.Kind.NIGHT) {
            EventLog.add(context, context.getString(R.string.log_night_skipped))
            return
        }

        var target = Duration.ofMinutes(settings.nightMinutes.toLong())
        if (settings.nightIncludeNaps) {
            val naps = NapLedger.today(context)
            if (!naps.isZero) EventLog.add(context, context.getString(R.string.log_naps_counted, naps.toMinutes()))
            target -= naps
        }
        if (target <= Duration.ZERO) {
            EventLog.add(context, context.getString(R.string.log_night_covered))
            if (current?.kind == Session.Kind.NAP) Session.cancel(context, context.getString(R.string.log_nap_ended_by_night))
            return
        }
        // The watch away at night start: rather than no alarm at all, ring the phone at the night's end
        var mode = settings.nightWakeMode
        if (Session.canStart(context, mode) == Session.StartResult.DISCONNECTED) {
            EventLog.add(context, context.getString(R.string.log_night_disconnected))
            mode = WakeMode.FIXED
        }
        val latest = nextOccurrence(settings.nightEnd)
        val night = { Session.start(context, Session.Kind.NIGHT, target, mode, latest) }
        if (current?.kind == Session.Kind.NAP) Session.handOverToNight(context, current, night) else night()
    }

    /** Some phones send this instead of BOOT_COMPLETED after a quick restart. */
    private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"

    /**
     * Right after a restart, before the phone is unlocked: only device-protected storage can be
     * read, so only the phone alarm is set again. The rest waits for BOOT_COMPLETED at unlock.
     */
    class LockedBootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
            PhoneAlarm.pendingAt(context)?.let { PhoneAlarm.schedule(context, it) }
        }
    }

    class TimerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CHECK -> {
                    Session.checkTimers(context)
                    scheduleSession(context, Session.current(context))
                }
                ACTION_NIGHT -> {
                    armNight(context)
                    scheduleNight(context)
                }
                Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> restore(context)
            }
        }
    }
}
