package com.devicemanager.monitor

import java.io.File

object ThermalReader {
    data class Reading(val sensor: String, val celsius: Float)

    /** Reads Linux thermal-zone sysfs where the app UID is allowed to read it. */
    fun readAvailable(): List<Reading> = runCatching {
        val root = File("/sys/class/thermal")
        root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("thermal_zone") }
            ?.mapNotNull { zone ->
                val raw = zone.resolve("temp").takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()
                    ?: return@mapNotNull null
                // Linux thermal sysfs reports millidegrees Celsius. Reject unusual
                // values rather than guessing a vendor-specific unit.
                if (raw !in -100_000L..200_000L || kotlin.math.abs(raw) < 1_000L) return@mapNotNull null
                val name = zone.resolve("type").takeIf(File::isFile)?.readText()?.trim()
                    ?.takeIf(String::isNotBlank) ?: zone.name
                Reading(name, raw / 1000f)
            }
            ?.take(12)
            .orEmpty()
    }.getOrDefault(emptyList())
}
