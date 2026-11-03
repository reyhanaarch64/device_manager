package com.devicemanager.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val ADB_SESSION_ID = "adb_session"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ADB_SESSION_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ADB_SESSION_ID,
                    "sesi adb",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "status pairing dan koneksi ADB yang sedang berjalan"
                    setShowBadge(false)
                }
            )
        }
    }
}
