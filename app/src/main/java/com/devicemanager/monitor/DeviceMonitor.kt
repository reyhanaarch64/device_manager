package com.devicemanager.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.io.RandomAccessFile
import java.util.Locale

object DeviceMonitor {
    data class Snapshot(
        val ramTotal: Long,
        val ramAvailable: Long,
        val storageTotal: Long,
        val storageAvailable: Long,
        val batteryPercent: Int?,
        val charging: Boolean?,
        val batteryVoltageMv: Int?,
        val batteryTempC: Float?,
        val uptimeMs: Long,
        val cpuPercent: Float?,
        val cpuCores: Int,
        val thermalSensors: List<ThermalReader.Reading>
    ) {
        val ramUsed: Long get() = (ramTotal - ramAvailable).coerceAtLeast(0)
        val storageUsed: Long get() = (storageTotal - storageAvailable).coerceAtLeast(0)
    }

    private var previousCpu: Pair<Long, Long>? = null

    fun read(context: Context): Snapshot {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val storageTotal = stat.totalBytes
        val storageAvailable = stat.availableBytes
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.div(10f)
        return Snapshot(
            ramTotal = memory.totalMem,
            ramAvailable = memory.availMem,
            storageTotal = storageTotal,
            storageAvailable = storageAvailable,
            batteryPercent = percent,
            charging = charging,
            batteryVoltageMv = voltage,
            batteryTempC = temp,
            uptimeMs = SystemClock.elapsedRealtime(),
            cpuPercent = readCpuPercent(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            thermalSensors = ThermalReader.readAvailable()
        )
    }

    private fun readCpuPercent(): Float? = runCatching {
        RandomAccessFile("/proc/stat", "r").use { file ->
            val values = file.readLine().trim().split(Regex("\\s+")).drop(1).map(String::toLong)
            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            val total = values.take(8).sum()
            val previous = previousCpu
            previousCpu = total to idle
            if (previous == null) return null
            val totalDelta = total - previous.first
            val idleDelta = idle - previous.second
            if (totalDelta <= 0) null else ((totalDelta - idleDelta).toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
        }
    }.getOrNull()

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        do { value /= 1024.0; index++ } while (value >= 1024 && index < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    fun formatUptime(ms: Long): String {
        val totalSeconds = ms / 1000
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        return if (days > 0) "${days} hari ${hours}j ${minutes}m" else "${hours}j ${minutes}m"
    }
}
