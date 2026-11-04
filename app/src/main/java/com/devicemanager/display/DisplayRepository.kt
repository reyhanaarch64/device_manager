package com.devicemanager.display

import android.content.Context
import com.devicemanager.adb.AdbRuntime
import com.devicemanager.adb.AdbSessionService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

object DisplayRepository {
    const val ROLLBACK_WINDOW_MS = 15_000L
    private const val ROLLBACK_PREPARATION_GRACE_MS = 120_000L
    private const val PREFS = "display_settings"
    private const val KEY_BASELINE_WIDTH = "baseline_width"
    private const val KEY_BASELINE_HEIGHT = "baseline_height"
    private const val KEY_BASELINE_DENSITY = "baseline_density"
    private const val KEY_BASELINE_DENSITY_OVERRIDE = "baseline_density_override"
    private const val KEY_PENDING_ROLLBACK = "pending_rollback"
    private const val KEY_PENDING_DEADLINE = "pending_rollback_deadline"
    private const val KEY_ROLLBACK_POLICY_VERSION = "rollback_policy_version"
    private const val ROLLBACK_POLICY_VERSION = 2

    data class Baseline(
        val width: Int,
        val height: Int,
        val density: Int,
        /** true when density already had a user override at first capture. */
        val densityWasOverride: Boolean
    )

    data class PendingRollback(val previous: DisplayStatus, val deadlineEpochMs: Long)

    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun safetyMode(context: Context): Boolean = preferences(context).getBoolean("safety_mode", true)

    fun setSafetyMode(context: Context, enabled: Boolean): Boolean =
        preferences(context).edit().putBoolean("safety_mode", enabled).commit()

    /**
     * Before 1.2.0 every display operation created a timed rollback record. In safety mode,
     * those legacy records can only belong to predefined actions, which are now persistent.
     * Preserve legacy pending records in manual mode because they may protect custom values.
     */
    fun migrateLegacyRollbackPolicy(context: Context): Boolean {
        val prefs = preferences(context)
        if (prefs.getInt(KEY_ROLLBACK_POLICY_VERSION, 0) >= ROLLBACK_POLICY_VERSION) return true
        if (safetyMode(context) && pendingRollback(context) != null && !clearPendingRollback(context)) return false
        return prefs.edit().putInt(KEY_ROLLBACK_POLICY_VERSION, ROLLBACK_POLICY_VERSION).commit()
    }

    fun baseline(context: Context): Baseline? {
        val prefs = preferences(context)
        val width = prefs.getInt(KEY_BASELINE_WIDTH, -1)
        val height = prefs.getInt(KEY_BASELINE_HEIGHT, -1)
        val density = prefs.getInt(KEY_BASELINE_DENSITY, -1)
        if (width !in 240..10_000 || height !in 240..20_000 || density !in 72..1_000) return null
        return Baseline(width, height, density, prefs.getBoolean(KEY_BASELINE_DENSITY_OVERRIDE, false))
    }

    /** First capture uses native physical size, but the currently effective density as requested. */
    fun captureBaselineIfMissing(context: Context, status: DisplayStatus): Baseline {
        baseline(context)?.let { return it }
        val captured = Baseline(
            width = status.physicalWidth,
            height = status.physicalHeight,
            density = status.activeDensity,
            densityWasOverride = status.hasDensityOverride
        )
        val saved = preferences(context).edit()
            .putInt(KEY_BASELINE_WIDTH, captured.width)
            .putInt(KEY_BASELINE_HEIGHT, captured.height)
            .putInt(KEY_BASELINE_DENSITY, captured.density)
            .putBoolean(KEY_BASELINE_DENSITY_OVERRIDE, captured.densityWasOverride)
            .commit()
        check(saved) { "baseline gagal disimpan ke penyimpanan lokal" }
        return captured
    }

    /** Explicit re-detection always records physical resolution and current effective logical density. */
    fun replaceBaseline(context: Context, status: DisplayStatus): Baseline {
        val captured = Baseline(status.physicalWidth, status.physicalHeight, status.activeDensity, status.hasDensityOverride)
        val saved = preferences(context).edit()
            .putInt(KEY_BASELINE_WIDTH, captured.width)
            .putInt(KEY_BASELINE_HEIGHT, captured.height)
            .putInt(KEY_BASELINE_DENSITY, captured.density)
            .putBoolean(KEY_BASELINE_DENSITY_OVERRIDE, captured.densityWasOverride)
            .commit()
        check(saved) { "baseline baru gagal disimpan ke penyimpanan lokal" }
        return captured
    }

    suspend fun readStatus(): Result<DisplayStatus> = runCatching {
        val output = AdbRuntime.shell("wm size; wm density").getOrThrow()
        DisplayStatusParser.parse(output)
    }

    suspend fun applyConfiguration(
        context: Context,
        width: Int,
        height: Int,
        density: Int,
        previous: DisplayStatus,
        autoRollbackOnSuccess: Boolean,
        rollbackWindowMs: Long = ROLLBACK_WINDOW_MS,
        resetSize: Boolean = false,
        resetDensity: Boolean = false
    ): Result<DisplayStatus> = withContext(NonCancellable) {
        if (width !in 240..10_000) return@withContext Result.failure(IllegalArgumentException("lebar harus 240–10000 px"))
        if (height !in 240..20_000) return@withContext Result.failure(IllegalArgumentException("tinggi harus 240–20000 px"))
        if (density !in 72..1_000) return@withContext Result.failure(IllegalArgumentException("density harus 72–1000"))
        if (autoRollbackOnSuccess && rollbackWindowMs !in 5_000L..60_000L) {
            return@withContext Result.failure(IllegalArgumentException("jendela rollback harus 5–60 detik"))
        }
        // Only manual configuration needs a timed recovery record. Presets and explicit
        // restore/reset actions are deterministic and must remain applied after verification.
        // For manual changes, persist a provisional recovery record before mutation; replace
        // its grace deadline with the real confirmation deadline after verification.
        if (autoRollbackOnSuccess) {
            val preparationDeadline = System.currentTimeMillis() + ROLLBACK_PREPARATION_GRACE_MS
            if (!savePendingRollback(context, previous, preparationDeadline)) {
                return@withContext Result.failure(IllegalStateException("state rollback gagal disimpan; perubahan display tidak dijalankan"))
            }
        }
        val sizeCommand = if (resetSize) "wm size reset" else "wm size ${width}x${height}"
        val densityCommand = if (resetDensity) "wm density reset" else "wm density $density"
        try {
            // Execute both halves independently. Even when one fails, the other is still
            // attempted; the catch path then restores the complete previous configuration.
            val sizeResult = AdbRuntime.shell(sizeCommand)
            val densityResult = AdbRuntime.shell(densityCommand)
            val commandErrors = buildList {
                sizeResult.exceptionOrNull()?.let { add("wm size: ${it.message ?: "gagal"}") }
                densityResult.exceptionOrNull()?.let { add("wm density: ${it.message ?: "gagal"}") }
            }
            if (commandErrors.isNotEmpty()) throw IllegalStateException(commandErrors.joinToString("\n"))
            val sizeOutput = sizeResult.getOrThrow()
            val densityOutput = densityResult.getOrThrow()
            val verificationOutput = AdbRuntime.shell("wm size; wm density").getOrThrow()
            val after = DisplayStatusParser.parse(verificationOutput)
            if (after.activeWidth != width || after.activeHeight != height || after.activeDensity != density) {
                throw IllegalStateException(
                    buildString {
                        append("verifikasi display tidak cocok dengan target. target: ")
                        append("${width}x${height} @ ${density} dpi; aktual: ")
                        append("${after.activeWidth}x${after.activeHeight} @ ${after.activeDensity} dpi")
                        append("\nkeluaran wm size: ")
                        append(sizeOutput.trim().ifBlank { "(kosong)" })
                        append("\nkeluaran wm density: ")
                        append(densityOutput.trim().ifBlank { "(kosong)" })
                        append("\nstatus verifikasi: ")
                        append(verificationOutput.trim())
                    }
                )
            }
            if (autoRollbackOnSuccess) {
                // Start the full confirmation window after verification, not before the wm calls.
                val confirmedDeadline = System.currentTimeMillis() + rollbackWindowMs
                check(updatePendingRollbackDeadline(context, confirmedDeadline)) {
                    "konfigurasi berubah, tetapi deadline rollback gagal disimpan; pemulihan otomatis tidak dapat dijamin"
                }
                // The service owns the timer independently of this Compose screen's lifecycle.
                AdbSessionService.scheduleDisplayRollback(context)
            }
            Result.success(after)
        } catch (failure: Throwable) {
            val rollback = restore(previous)
            val message = if (rollback.isSuccess) {
                val cleared = !autoRollbackOnSuccess || clearPendingRollback(context)
                if (cleared) {
                    "penerapan gagal; konfigurasi sebelumnya sudah dipulihkan: ${failure.message ?: "perintah wm gagal"}"
                } else {
                    "penerapan gagal dan konfigurasi sebelumnya sudah dipulihkan, tetapi penanda rollback gagal dihapus. periksa status display sebelum perubahan berikutnya."
                }
            } else {
                val saved = savePendingRollback(context, previous, System.currentTimeMillis())
                if (saved) {
                    runCatching { AdbSessionService.scheduleDisplayRollback(context) }
                    "penerapan gagal dan pemulihan otomatis juga gagal. state rollback disimpan untuk dicoba lagi setelah adb tersambung: ${rollback.exceptionOrNull()?.message ?: "koneksi adb terputus"}"
                } else {
                    "penerapan gagal dan rollback juga gagal. state pemulihan tidak dapat disimpan; koneksi adb harus dipulihkan dan konfigurasi display dikembalikan secara manual."
                }
            }
            Result.failure(IllegalStateException(message, failure))
        }
    }

    /** Restores the exact pre-change override state, not merely the same numeric values. */
    suspend fun restore(previous: DisplayStatus): Result<Unit> = runCatching {
        val sizeCommand = if (previous.hasSizeOverride) {
            "wm size ${previous.activeWidth}x${previous.activeHeight}"
        } else "wm size reset"
        val densityCommand = if (previous.hasDensityOverride) {
            "wm density ${previous.activeDensity}"
        } else "wm density reset"
        val sizeResult = AdbRuntime.shell(sizeCommand)
        val densityResult = AdbRuntime.shell(densityCommand)
        sizeResult.getOrThrow()
        densityResult.getOrThrow()
        val restored = DisplayStatusParser.parse(AdbRuntime.shell("wm size; wm density").getOrThrow())
        check(restored.activeWidth == previous.activeWidth && restored.activeHeight == previous.activeHeight && restored.activeDensity == previous.activeDensity) {
            "verifikasi pemulihan display gagal"
        }
    }

    suspend fun resetToSystemDefault(): Result<Unit> = runCatching {
        val size = AdbRuntime.shell("wm size reset")
        val density = AdbRuntime.shell("wm density reset")
        size.getOrThrow()
        density.getOrThrow()
    }

    fun savePendingRollback(context: Context, previous: DisplayStatus, deadlineEpochMs: Long): Boolean {
        val json = JSONObject().apply {
            put("physicalWidth", previous.physicalWidth)
            put("physicalHeight", previous.physicalHeight)
            if (previous.hasSizeOverride) {
                put("overrideWidth", previous.activeWidth)
                put("overrideHeight", previous.activeHeight)
            }
            put("physicalDensity", previous.physicalDensity)
            if (previous.hasDensityOverride) put("overrideDensity", previous.activeDensity)
        }
        return preferences(context).edit()
            .putString(KEY_PENDING_ROLLBACK, json.toString())
            .putLong(KEY_PENDING_DEADLINE, deadlineEpochMs)
            .commit()
    }

    fun pendingRollback(context: Context): PendingRollback? = runCatching {
        val prefs = preferences(context)
        val raw = prefs.getString(KEY_PENDING_ROLLBACK, null) ?: return null
        val json = JSONObject(raw)
        val physicalWidth = json.getInt("physicalWidth")
        val physicalHeight = json.getInt("physicalHeight")
        val overrideWidth = if (json.has("overrideWidth")) json.getInt("overrideWidth") else null
        val overrideHeight = if (json.has("overrideHeight")) json.getInt("overrideHeight") else null
        val physicalDensity = json.getInt("physicalDensity")
        val overrideDensity = if (json.has("overrideDensity")) json.getInt("overrideDensity") else null
        require(physicalWidth in 240..10_000 && physicalHeight in 240..20_000)
        require(physicalDensity in 72..1_000)
        require((overrideWidth == null) == (overrideHeight == null))
        require(overrideWidth == null || (overrideWidth in 240..10_000 && overrideHeight!! in 240..20_000))
        require(overrideDensity == null || overrideDensity in 72..1_000)
        val deadline = prefs.getLong(KEY_PENDING_DEADLINE, 0L)
        require(deadline > 0L)
        val status = DisplayStatus(
            physicalWidth, physicalHeight, overrideWidth, overrideHeight,
            physicalDensity, overrideDensity, "saved rollback state"
        )
        PendingRollback(status, deadline)
    }.getOrNull()

    fun updatePendingRollbackDeadline(context: Context, deadlineEpochMs: Long): Boolean {
        val prefs = preferences(context)
        if (prefs.getString(KEY_PENDING_ROLLBACK, null) == null || deadlineEpochMs <= 0L) return false
        return prefs.edit().putLong(KEY_PENDING_DEADLINE, deadlineEpochMs).commit()
    }

    fun clearPendingRollback(context: Context): Boolean = preferences(context).edit()
        .remove(KEY_PENDING_ROLLBACK)
        .remove(KEY_PENDING_DEADLINE)
        .commit()

    suspend fun restorePendingRollback(context: Context): Result<Boolean> {
        val pending = pendingRollback(context) ?: return Result.success(false)
        if (System.currentTimeMillis() < pending.deadlineEpochMs) return Result.success(false)
        val restored = restore(pending.previous)
        if (restored.isFailure) return Result.failure(restored.exceptionOrNull()!!)
        return if (clearPendingRollback(context)) Result.success(true)
        else Result.failure(IllegalStateException("konfigurasi pulih, tetapi state rollback belum berhasil dihapus dari penyimpanan"))
    }
}
