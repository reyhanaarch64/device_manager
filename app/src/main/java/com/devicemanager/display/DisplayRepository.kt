package com.devicemanager.display

import com.devicemanager.adb.AdbRuntime

object DisplayRepository {
    suspend fun readStatus(): Result<String> = AdbRuntime.shell("wm size; wm density")

    suspend fun setResolution(width: Int, height: Int): Result<String> = runCatching {
        require(width in 240..10_000) { "lebar harus 240–10000 px" }
        require(height in 240..20_000) { "tinggi harus 240–20000 px" }
        AdbRuntime.shell("wm size ${width}x${height}").getOrThrow()
    }

    suspend fun setDensity(dpi: Int): Result<String> = runCatching {
        require(dpi in 80..1000) { "density harus 80–1000 dpi" }
        AdbRuntime.shell("wm density $dpi").getOrThrow()
    }

    suspend fun resetOverrides(): Result<String> = AdbRuntime.shell("wm size reset; wm density reset")
}
