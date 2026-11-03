package com.devicemanager.adb

import android.content.Context
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbInteractiveSession
import com.devicemanager.data.ActivityLogRepository
import com.devicemanager.security.AndroidKeystoreAdbKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** State and lifetime owner for the one authenticated ADB connection. */
data class AdbUiState(
    val phase: String = "disconnected",
    val message: String = "belum terhubung",
    val endpoint: String? = null,
    val connectedSince: Long? = null,
    val paired: Boolean = false
) {
    val connected: Boolean get() = phase == "connected"
}

object AdbRuntime {
    private val _state = MutableStateFlow(AdbUiState())
    val state: StateFlow<AdbUiState> = _state.asStateFlow()

    @Volatile private var client: AdbClient? = null
    @Volatile private var terminalSession: AdbInteractiveSession? = null
    @Volatile private var provider: AndroidKeystoreAdbKeyProvider? = null
    private val busy = AtomicBoolean(false)

    private fun keyProvider(context: Context): AndroidKeystoreAdbKeyProvider {
        provider?.let { return it }
        return synchronized(this) {
            provider ?: AndroidKeystoreAdbKeyProvider(context.applicationContext).also { provider = it }
        }
    }

    fun setSessionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit()
            .putBoolean("session_enabled", enabled)
            .apply()
    }

    fun sessionEnabled(context: Context): Boolean =
        context.getSharedPreferences("adb_session", Context.MODE_PRIVATE)
            .getBoolean("session_enabled", false)

    fun restorePaired(context: Context) {
        val liveClient = client
        if (liveClient != null && !liveClient.isClosed) return
        val currentPhase = _state.value.phase
        if (currentPhase in setOf("pairing", "connecting", "paired")) return
        val paired = paired(context)
        _state.value = AdbUiState(
            phase = "disconnected",
            message = if (paired) "pairing tersimpan; belum ada sesi koneksi aktif" else "belum terhubung",
            paired = paired
        )
    }

    suspend fun pair(context: Context, endpoint: AdbEndpoint, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) return@withContext Result.failure(IllegalStateException("operasi adb lain sedang berjalan"))
        _state.value = _state.value.copy(phase = "pairing", message = "mengautentikasi pairing…")
        try {
            require(code.matches(Regex("\\d{6}"))) { "kode pairing harus 6 digit" }
            val result = withTimeout(30_000) {
                AdbClient.pairTls(endpoint.host, endpoint.port, code, keyProvider(context))
            }
            result.fold(
                onSuccess = {
                    context.getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit()
                        .putBoolean("paired", true)
                        .putString("pair_host", endpoint.host)
                        .putInt("pair_port", endpoint.port)
                        .putLong("paired_at", System.currentTimeMillis())
                        .apply()
                    _state.value = _state.value.copy(
                        phase = "paired",
                        message = "pairing berhasil; masukkan port koneksi",
                        paired = true
                    )
                    record(context, "pair", endpoint.asText(), true, "wireless debugging paired")
                    Result.success(Unit)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(phase = "pair_failed", message = error.message ?: "pairing gagal")
                    record(context, "pair", endpoint.asText(), false, error.message ?: "pairing gagal")
                    Result.failure(error)
                }
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(phase = "pair_failed", message = t.message ?: "pairing gagal")
            record(context, "pair", endpoint.asText(), false, t.message ?: "pairing gagal")
            Result.failure(t)
        } finally {
            busy.set(false)
        }
    }

    suspend fun connect(context: Context, endpoint: AdbEndpoint): Result<Unit> = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) return@withContext Result.failure(IllegalStateException("operasi adb lain sedang berjalan"))
        _state.value = _state.value.copy(phase = "connecting", message = "menghubungkan ke perangkat…", endpoint = endpoint.asText())
        try {
            closeTerminalInternal()
            client?.closeQuietly()
            client = null
            val connection = withTimeout(40_000) {
                AdbClient.connectTls(endpoint.host, endpoint.port, keyProvider(context))
            }
            client = connection
            val now = System.currentTimeMillis()
            context.getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit()
                .putString("last_endpoint", endpoint.asText())
                .putString("last_host", endpoint.host)
                .putInt("last_port", endpoint.port)
                .putLong("connected_at", now)
                .apply()
            record(context, "connect", endpoint.asText(), true, "koneksi berhasil")
            _state.value = AdbUiState("connected", "terhubung", endpoint.asText(), now, true)
            Result.success(Unit)
        } catch (t: Throwable) {
            record(context, "connect", endpoint.asText(), false, t.message ?: "koneksi gagal")
            _state.value = _state.value.copy(
                phase = "connect_failed",
                message = t.message ?: "koneksi gagal",
                endpoint = endpoint.asText(),
                connectedSince = null
            )
            Result.failure(t)
        } finally {
            busy.set(false)
        }
    }

    suspend fun shell(command: String): Result<String> = withContext(Dispatchers.IO) {
        val active = client ?: return@withContext Result.failure(IllegalStateException("belum ada koneksi adb aktif"))
        try {
            require(command.isNotBlank()) { "perintah kosong" }
            require(command.length <= 16_384) { "perintah terlalu panjang (maksimum 16 KB)" }
            val result = withTimeout(60_000) { active.shell(command) }
            if (result.isSuccess) Result.success(result.stdoutText)
            else Result.failure(IllegalStateException(result.stderrText.ifBlank { "perintah gagal (exit ${result.exitCode})" }))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Opens a persistent PTY Shell v2 session. Its output Flow is single-consumer. */
    suspend fun openInteractiveTerminal(): Result<Flow<String>> = withContext(Dispatchers.IO) {
        val active = client ?: return@withContext Result.failure(IllegalStateException("belum ada koneksi adb aktif"))
        try {
            closeTerminalInternal()
            val session = withTimeout(20_000) { active.openInteractiveShell(terminalType = "xterm-256color", rows = 32, cols = 100) }
            terminalSession = session
            Result.success(session.textFlow)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun writeTerminal(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        val active = terminalSession ?: return@withContext Result.failure(IllegalStateException("terminal belum dibuka"))
        try {
            withTimeout(10_000) { active.writeUtf8(text) }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun writeTerminalLine(text: String): Result<Unit> = writeTerminal("$text\n")

    fun closeTerminal() {
        closeTerminalInternal()
    }

    private fun closeTerminalInternal() {
        terminalSession?.let { runCatching { it.close() } }
        terminalSession = null
    }

    fun disconnect(context: Context) {
        closeTerminalInternal()
        client?.closeQuietly()
        client = null
        context.getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit().remove("connected_at").apply()
        _state.value = _state.value.copy(
            phase = "disconnected",
            message = "koneksi diputus",
            endpoint = null,
            connectedSince = null
        )
        record(context, "disconnect", "", true, "sesi diputus")
    }

    fun forgetPairing(context: Context) {
        setSessionEnabled(context, false)
        disconnect(context)
        context.stopService(android.content.Intent(context, AdbSessionService::class.java))
        context.getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit()
            .putBoolean("paired", false)
            .remove("pair_host")
            .remove("pair_port")
            .remove("paired_at")
            .remove("last_endpoint")
            .remove("last_host")
            .remove("last_port")
            .apply()
        synchronized(this) {
            (provider ?: AndroidKeystoreAdbKeyProvider(context.applicationContext)).clear()
            provider = null
        }
        _state.value = AdbUiState(message = "kredensial lokal dihapus", paired = false)
        record(context, "reset credentials", "", true, "kunci ADB lokal dan endpoint tersimpan dihapus")
    }

    fun savedEndpoint(context: Context): AdbEndpoint? {
        val prefs = context.getSharedPreferences("adb_session", Context.MODE_PRIVATE)
        val host = prefs.getString("last_host", null) ?: return null
        val port = prefs.getInt("last_port", -1)
        return if (port in 1..65535) AdbEndpoint(host, port) else null
    }

    fun removeSavedEndpoint(context: Context) {
        context.getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit()
            .remove("last_endpoint").remove("last_host").remove("last_port").apply()
    }

    fun paired(context: Context): Boolean =
        context.getSharedPreferences("adb_session", Context.MODE_PRIVATE).getBoolean("paired", false)

    private fun Closeable.closeQuietly() { runCatching { close() } }

    private fun record(context: Context, type: String, endpoint: String, success: Boolean, message: String) =
        ActivityLogRepository.record(context, type, endpoint, success, message)
}
