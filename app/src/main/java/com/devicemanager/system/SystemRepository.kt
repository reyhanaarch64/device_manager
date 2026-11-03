package com.devicemanager.system

import com.devicemanager.adb.AdbRuntime

object SystemRepository {
    data class AnimationSettings(val window: String, val transition: String, val animator: String)

    suspend fun readAnimations(): Result<AnimationSettings> = runCatching {
        val values = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .map { key -> AdbRuntime.shell("settings get global $key").getOrThrow().trim().ifBlank { "1" } }
        AnimationSettings(values[0], values[1], values[2])
    }

    suspend fun setAnimations(scale: String): Result<Unit> = runCatching {
        require(scale in setOf("0", "0.5", "1", "1.5", "2")) { "nilai animasi tidak valid" }
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").forEach { key ->
            AdbRuntime.shell("settings put global $key $scale").getOrThrow()
        }
    }

    suspend fun properties(): Result<String> = AdbRuntime.shell(
        "getprop | grep -E '\\[(ro\\.build\\.(version\\.release|version\\.sdk|fingerprint)|ro\\.product\\.(manufacturer|model|device)|ro\\.hardware|ro\\.soc\\.model)\\]'"
    )

    suspend fun animationStatus(): Result<String> = AdbRuntime.shell(
        "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale"
    )

    suspend fun processes(): Result<String> = AdbRuntime.shell(
        "ps -A -o USER,PID,PPID,NAME | head -n 100"
    )

    suspend fun globalConfiguration(): Result<String> = AdbRuntime.shell(
        "settings list global | head -n 120"
    )
}
