package com.devicemanager.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts only an ADB session that the user explicitly left enabled. */
class AdbRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!AdbRuntime.sessionEnabled(context)) return
        runCatching { AdbSessionService.start(context, AdbSessionService.ACTION_RESTORE) }
            .onFailure { error ->
                context.getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit()
                    .putString("restore_error", error.message ?: error.javaClass.simpleName)
                    .apply()
            }
    }
}
