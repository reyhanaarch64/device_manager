package com.devicemanager.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object AppRepository {
    data class InstalledApp(
        val packageName: String,
        val label: String,
        val version: String,
        val system: Boolean,
        val enabled: Boolean,
        val uid: Int,
        val apkBytes: Long,
        val metadataAvailable: Boolean = true
    )

    @Suppress("DEPRECATION")
    fun installed(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { info ->
                val version = runCatching {
                    pm.getPackageInfo(info.packageName, 0).versionName ?: "unknown"
                }.getOrDefault("unknown")
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                    version = version,
                    system = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    enabled = info.enabled,
                    uid = info.uid,
                    apkBytes = (listOfNotNull(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())).sumOf { path -> runCatching { java.io.File(path).length() }.getOrDefault(0L) }
                )
            }
            .sortedWith(compareBy<InstalledApp> { it.system }.thenBy { it.label.lowercase() })
    }

    fun remotePlaceholder(packageName: String): InstalledApp = InstalledApp(
        packageName = packageName,
        label = packageName,
        version = "metadata tidak tersedia pada profil aktif",
        system = false,
        enabled = false,
        uid = -1,
        apkBytes = 0L,
        metadataAvailable = false
    )
}
