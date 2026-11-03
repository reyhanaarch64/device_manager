package com.devicemanager.monitor

import android.app.ActivityManager
import com.devicemanager.adb.AdbRuntime
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
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
        val cpuStatus: String,
        val cpuCores: Int,
        val thermalSensors: List<ThermalReader.Reading>
    ) {
        val ramUsed: Long get() = (ramTotal - ramAvailable).coerceAtLeast(0)
        val storageUsed: Long get() = (storageTotal - storageAvailable).coerceAtLeast(0)
    }

    private var previousCpu: CpuStatCounter? = null

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
        val cpu = readCpuPercent()
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
            cpuPercent = cpu.first,
            cpuStatus = cpu.second,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            thermalSensors = ThermalReader.readAvailable()
        )
    }

    /** Uses /proc/stat system-wide counters. ADB shell is used as a fallback when app UID access is denied. */
    suspend fun refresh(context: Context): Snapshot {
        val snapshot = read(context)
        if (snapshot.cpuPercent != null || snapshot.cpuStatus == "sampling" || !AdbRuntime.state.value.connected) return snapshot
        val shellRead = AdbRuntime.shell("cat /proc/stat")
        val line = shellRead.getOrNull()?.lineSequence()?.firstOrNull { it.trimStart().startsWith("cpu ") }
        if (line != null) {
            val sample = sampleCpuLine(line)
            if (sample.first != null || sample.second == "sampling") return snapshot.copy(cpuPercent = sample.first, cpuStatus = "${sample.second} (adb shell)")
        }
        val detail = shellRead.exceptionOrNull()?.message
        return snapshot.copy(cpuStatus = detail?.let { "tidak dapat membaca /proc/stat via aplikasi maupun adb: $it" }
            ?: "format /proc/stat tidak dikenali")
    }

    private fun readCpuPercent(): Pair<Float?, String> {
        return try {
            val firstLine = java.io.File("/proc/stat").bufferedReader().use { it.readLine() }
            if (firstLine == null) null to "file /proc/stat kosong" else sampleCpuLine(firstLine)
        } catch (error: SecurityException) {
            null to "akses /proc/stat ditolak: ${error.message ?: "permission denied"}"
        } catch (error: Exception) {
            null to "gagal membaca /proc/stat: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    @Synchronized
    private fun sampleCpuLine(line: String): Pair<Float?, String> {
        val current = CpuStatParser.parse(line)
            ?: return null to "format counter CPU tidak valid atau tidak lengkap"
        val previous = previousCpu
        previousCpu = current
        if (previous == null) return null to "sampling"
        val percent = CpuStatParser.percentage(previous, current)
            ?: return null to "counter CPU belum stabil; menunggu sampel berikutnya"
        return percent to "terbaca"
    }

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
