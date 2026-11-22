package io.github.ljlies.wakebridge

import android.content.Context
import java.time.Instant

/** The most recent events, shown on the setup screen to check that Gadgetbridge's events arrive. */
object EventLog {
    private const val MAX_ENTRIES = 30
    private const val KEY = "entries"

    fun add(context: Context, message: String) {
        val prefs = prefs(context)
        val entries = load(context).toMutableList()
        entries.add(0, "${Session.now().toEpochMilli()}\t$message")
        prefs.edit().putString(KEY, entries.take(MAX_ENTRIES).joinToString("\n")).apply()
    }

    /** Newest first, as "timestamp\tmessage". */
    private fun load(context: Context): List<String> =
        prefs(context).getString(KEY, null)?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    fun format(context: Context): String =
        load(context).joinToString("\n") { line ->
            val (millis, message) = line.split("\t", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            "${Format.dateTime(context, Instant.ofEpochMilli(millis.toLongOrNull() ?: 0))}  $message"
        }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // Device-protected, so the phone alarm can log while the phone is still locked after a restart
    private fun prefs(context: Context) = Storage.deviceProtected(context, "event_log")
}
