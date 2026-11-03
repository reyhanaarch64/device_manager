package com.devicemanager.data

import android.content.Context

/** Small bounded local event log; it never stores pairing codes or private keys. */
object ActivityLogRepository {
    private const val PREFS = "activity_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    @Synchronized
    fun record(context: Context, type: String, subject: String = "", success: Boolean, message: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = listOf(
            System.currentTimeMillis().toString(),
            clean(type),
            clean(subject),
            success.toString(),
            clean(message)
        ).joinToString("|")
        val previous = prefs.getString(KEY_ENTRIES, "").orEmpty()
            .lineSequence().filter(String::isNotBlank).take(MAX_ENTRIES - 1).toList()
        prefs.edit().putString(KEY_ENTRIES, (listOf(line) + previous).joinToString("\n")).apply()
    }

    fun read(context: Context, limit: Int = MAX_ENTRIES): List<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "").orEmpty()
            .lineSequence().filter(String::isNotBlank).take(limit.coerceIn(1, MAX_ENTRIES)).toList()

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ENTRIES).apply()
    }

    private fun clean(value: String): String = value
        .replace('|', '/')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(500)
}
