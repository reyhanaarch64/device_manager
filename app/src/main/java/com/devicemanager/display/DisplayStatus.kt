package com.devicemanager.display

/** Values reported by the WindowManager shell command for the default display. */
data class DisplayStatus(
    val physicalWidth: Int,
    val physicalHeight: Int,
    val overrideWidth: Int?,
    val overrideHeight: Int?,
    val physicalDensity: Int,
    val overrideDensity: Int?,
    val rawOutput: String
) {
    val activeWidth: Int get() = overrideWidth ?: physicalWidth
    val activeHeight: Int get() = overrideHeight ?: physicalHeight
    val activeDensity: Int get() = overrideDensity ?: physicalDensity
    val hasSizeOverride: Boolean get() = overrideWidth != null && overrideHeight != null
    val hasDensityOverride: Boolean get() = overrideDensity != null
}

object DisplayStatusParser {
    private val sizePhysical = Regex("(?im)^\\s*Physical\\s+size\\s*:\\s*(\\d+)\\s*[x×]\\s*(\\d+)\\s*$")
    private val sizeOverride = Regex("(?im)^\\s*Override\\s+size\\s*:\\s*(\\d+)\\s*[x×]\\s*(\\d+)\\s*$")
    private val densityPhysical = Regex("(?im)^\\s*Physical\\s+density\\s*:\\s*(\\d+)\\s*$")
    private val densityOverride = Regex("(?im)^\\s*Override\\s+density\\s*:\\s*(\\d+)\\s*$")

    fun parse(output: String): DisplayStatus {
        val physicalSize = sizePhysical.find(output)
            ?: error("wm size tidak mengembalikan Physical size; baseline resolusi tidak dapat dipastikan")
        val physicalDpi = densityPhysical.find(output)
            ?: error("wm density tidak mengembalikan Physical density; baseline density tidak dapat dipastikan")
        val overrideSize = sizeOverride.find(output)
        val overrideDpi = densityOverride.find(output)

        val physicalWidth = physicalSize.groupValues[1].toInt()
        val physicalHeight = physicalSize.groupValues[2].toInt()
        val overrideWidth = overrideSize?.groupValues?.get(1)?.toInt()
        val overrideHeight = overrideSize?.groupValues?.get(2)?.toInt()
        val physicalDensity = physicalDpi.groupValues[1].toInt()
        val overrideDensityValue = overrideDpi?.groupValues?.get(1)?.toInt()

        require(physicalWidth in 240..10_000 && physicalHeight in 240..20_000) {
            "ukuran fisik display di luar rentang valid"
        }
        require(physicalDensity in 72..1_000) { "physical density di luar rentang valid" }
        require((overrideWidth == null) == (overrideHeight == null)) {
            "data override resolusi tidak lengkap"
        }
        require(overrideWidth == null || (overrideWidth in 240..10_000 && overrideHeight!! in 240..20_000)) {
            "override resolusi di luar rentang valid"
        }
        require(overrideDensityValue == null || overrideDensityValue in 72..1_000) {
            "override density di luar rentang valid"
        }
        return DisplayStatus(
            physicalWidth, physicalHeight, overrideWidth, overrideHeight,
            physicalDensity, overrideDensityValue, output
        )
    }
}

/** Deterministic scale calculation: both dimensions and logical density use the same factor. */
data class DisplayTarget(val width: Int, val height: Int, val density: Int, val scale: Float)

object DisplayScale {
    val presets = listOf(1.00f, .90f, .80f, .75f, .67f, .50f)

    fun target(baselineWidth: Int, baselineHeight: Int, baselineDensity: Int, scale: Float): DisplayTarget {
        require(scale in .5f..1f && presets.any { it == scale }) { "preset skala tidak valid" }
        val width = ((baselineWidth * scale) + .5f).toInt()
        val height = ((baselineHeight * scale) + .5f).toInt()
        val density = ((baselineDensity * scale) + .5f).toInt()
        require(width in 240..baselineWidth && height in 240..baselineHeight) {
            "target resolusi terlalu kecil atau melebihi baseline"
        }
        require(density in 72..baselineDensity) { "target density di luar rentang aman" }
        return DisplayTarget(width, height, density, scale)
    }
}
