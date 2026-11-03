package com.devicemanager.device

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object DeviceInfo {
    data class Snapshot(
        val manufacturer: String,
        val model: String,
        val device: String,
        val brand: String,
        val product: String,
        val android: String,
        val api: Int,
        val soc: String,
        val hardware: String,
        val abis: String,
        val resolution: String,
        val densityDpi: Int
    )

    @Suppress("DEPRECATION")
    fun read(context: Context): Snapshot {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(WindowManager::class.java)
        runCatching { wm.defaultDisplay.getRealMetrics(metrics) }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) context.resources.displayMetrics.let {
            metrics.setTo(it)
        }
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: "tidak tersedia" else "tidak diekspos api"
        return Snapshot(
            manufacturer = Build.MANUFACTURER.orUnknown(),
            model = Build.MODEL.orUnknown(),
            device = Build.DEVICE.orUnknown(),
            brand = Build.BRAND.orUnknown(),
            product = Build.PRODUCT.orUnknown(),
            android = Build.VERSION.RELEASE.orUnknown(),
            api = Build.VERSION.SDK_INT,
            soc = soc,
            hardware = Build.HARDWARE.orUnknown(),
            abis = Build.SUPPORTED_ABIS.joinToString().ifBlank { "tidak tersedia" },
            resolution = "${metrics.widthPixels} × ${metrics.heightPixels}",
            densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else context.resources.displayMetrics.densityDpi
        )
    }

    private fun String?.orUnknown() = this?.takeIf { it.isNotBlank() } ?: "tidak diketahui"
}
