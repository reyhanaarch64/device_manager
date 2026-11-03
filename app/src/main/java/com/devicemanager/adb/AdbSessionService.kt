package com.devicemanager.adb

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.devicemanager.MainActivity
import com.devicemanager.R
import com.devicemanager.notifications.NotificationChannels
import com.devicemanager.display.DisplayRepository
import com.devicemanager.data.ActivityLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Foreground owner for the user-initiated Wireless Debugging pairing/ADB session. */
class AdbSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var step = STEP_NONE
    private var pairEndpoint: AdbEndpoint? = null
    private var statusText = "menunggu input"
    private var displayRollbackJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_BEGIN_PAIR || action == ACTION_RECONNECT || action == ACTION_RESTORE) {
            AdbRuntime.setSessionEnabled(this, true)
        }
        if (intent == null && !AdbRuntime.sessionEnabled(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A stale notification action must not resurrect a session after explicit disconnect.
        if (action == ACTION_INPUT && !AdbRuntime.sessionEnabled(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureForeground()
        if (action == ACTION_INPUT && step == STEP_NONE) restoreAfterProcessRestart()
        when (action) {
            ACTION_BEGIN_PAIR -> beginPairing()
            ACTION_RECONNECT -> reconnect()
            ACTION_RESTORE -> restoreAfterProcessRestart()
            ACTION_DISCONNECT -> disconnect()
            ACTION_INPUT -> consumeRemoteInput(intent!!)
            ACTION_SCHEDULE_DISPLAY_ROLLBACK -> schedulePendingDisplayRollback()
            ACTION_KEEP_DISPLAY -> keepDisplayConfiguration()
            null -> restoreAfterProcessRestart()
        }
        return if (AdbRuntime.sessionEnabled(this)) START_STICKY else START_NOT_STICKY
    }

    private fun restoreAfterProcessRestart() {
        AdbRuntime.restorePaired(this)
        val prefs = getSharedPreferences("adb_session", Context.MODE_PRIVATE)
        val saved = AdbRuntime.savedEndpoint(this)
        val savedStep = prefs.getInt(KEY_STEP, STEP_NONE)
        val pairHost = prefs.getString(KEY_PAIR_HOST, null)
        val pairPort = prefs.getInt(KEY_PAIR_PORT, -1)
        val storedPairEndpoint = if (!pairHost.isNullOrBlank() && pairPort in 1..65535) AdbEndpoint(pairHost, pairPort) else null
        when {
            savedStep == STEP_PAIR_ENDPOINT -> {
                step = STEP_PAIR_ENDPOINT
                showNotification("siapkan wireless debugging", "masukkan ip:port pairing dari dialog Pair device with pairing code")
            }
            savedStep == STEP_PAIR_CODE && storedPairEndpoint != null -> {
                pairEndpoint = storedPairEndpoint
                step = STEP_PAIR_CODE
                showNotification("masukkan kode pairing", "isi ulang kode enam digit dari dialog Pair device with pairing code")
            }
            savedStep == STEP_PAIRING && AdbRuntime.paired(this) -> {
                step = STEP_CONNECT_ENDPOINT
                showNotification("pairing berhasil", "masukkan ip:port koneksi Wireless Debugging. pairing tersimpan sebelum proses terhenti")
            }
            savedStep == STEP_PAIRING && storedPairEndpoint != null -> {
                pairEndpoint = storedPairEndpoint
                step = STEP_PAIR_CODE
                showNotification("masukkan ulang kode pairing", "proses pairing sebelumnya terhenti; buka dialog pairing dan kirim kode enam digit lagi")
            }
            savedStep == STEP_CONNECT_ENDPOINT || savedStep == STEP_CONNECTING -> {
                step = STEP_CONNECT_ENDPOINT
                showNotification("pairing masih tersimpan", "masukkan ulang ip:port koneksi Wireless Debugging yang sedang tampil di pengaturan")
            }
            saved != null -> reconnect()
            AdbRuntime.paired(this) -> {
                step = STEP_CONNECT_ENDPOINT
                showNotification("pairing masih tersimpan", "masukkan ip:port koneksi Wireless Debugging yang sedang tampil di pengaturan")
            }
            else -> {
                step = STEP_PAIR_ENDPOINT
                showNotification("service adb aktif", "masukkan ip:port pairing dari dialog Pair device with pairing code")
            }
        }
    }

    private fun beginPairing() {
        pairEndpoint = null
        step = STEP_PAIR_ENDPOINT
        statusText = "menunggu alamat pairing"
        showNotification(
            "siapkan wireless debugging",
            "aktifkan Wireless debugging di Developer options, lalu pilih Pair device with pairing code"
        )
    }

    private fun reconnect() {
        val endpoint = AdbRuntime.savedEndpoint(this)
        if (endpoint == null) {
            step = if (AdbRuntime.paired(this)) STEP_CONNECT_ENDPOINT else STEP_PAIR_ENDPOINT
            showNotification(
                "alamat koneksi belum tersimpan",
                if (AdbRuntime.paired(this)) "masukkan ip:port koneksi dari halaman Wireless Debugging" else "mulai pairing dari aplikasi terlebih dahulu"
            )
            return
        }
        step = STEP_CONNECTING
        statusText = "mencoba koneksi terakhir"
        showNotification("menghubungkan ulang", endpoint.asText())
        scope.launch {
            val result = AdbRuntime.connect(this@AdbSessionService, endpoint)
            if (result.isSuccess) {
                step = STEP_CONNECTED
                statusText = "terhubung"
                showNotification("adb tersambung", endpoint.asText())
                schedulePendingDisplayRollback()
            } else {
                step = STEP_CONNECT_ENDPOINT
                statusText = result.exceptionOrNull()?.message ?: "koneksi gagal"
                showNotification("koneksi terakhir gagal", "${statusText}. masukkan ip:port koneksi yang baru")
            }
        }
    }

    private fun consumeRemoteInput(intent: Intent) {
        val input = RemoteInput.getResultsFromIntent(intent) ?: return
        val value = input.getCharSequence(KEY_INPUT)?.toString()?.trim().orEmpty()
        when (step) {
            STEP_PAIR_ENDPOINT -> {
                runCatching { AdbEndpoint.parse(value) }.onSuccess {
                    pairEndpoint = it
                    step = STEP_PAIR_CODE
                    showNotification("masukkan kode pairing", "isi kode enam digit yang terlihat pada dialog pairing di layar perangkat")
                }.onFailure {
                    statusText = it.message ?: "alamat tidak valid"
                    showNotification("format alamat tidak valid", "gunakan format ip:port atau [ipv6]:port, lalu kirim ulang")
                }
            }
            STEP_PAIR_CODE -> {
                val endpoint = pairEndpoint
                if (endpoint == null || !value.matches(Regex("\\d{6}"))) {
                    showNotification("kode tidak valid", "kode pairing harus tepat enam digit")
                    return
                }
                step = STEP_PAIRING
                showNotification("pairing berjalan", endpoint.asText())
                scope.launch {
                    val result = AdbRuntime.pair(this@AdbSessionService, endpoint, value)
                    if (result.isSuccess) {
                        pairEndpoint = null
                        step = STEP_CONNECT_ENDPOINT
                        showNotification("pairing berhasil", "masukkan ip:port koneksi. port ini berbeda dari port pairing")
                    } else {
                        step = STEP_PAIR_ENDPOINT
                        pairEndpoint = null
                        showNotification("pairing gagal", result.exceptionOrNull()?.message ?: "periksa alamat dan kode, lalu ulangi")
                    }
                }
            }
            STEP_CONNECT_ENDPOINT -> {
                runCatching { AdbEndpoint.parse(value) }.onSuccess { endpoint ->
                    step = STEP_CONNECTING
                    showNotification("menghubungkan", endpoint.asText())
                    scope.launch {
                        val result = AdbRuntime.connect(this@AdbSessionService, endpoint)
                        if (result.isSuccess) {
                            step = STEP_CONNECTED
                            statusText = "terhubung"
                            showNotification("adb tersambung", endpoint.asText())
                            schedulePendingDisplayRollback()
                        } else {
                            step = STEP_CONNECT_ENDPOINT
                            val reason = result.exceptionOrNull()?.message ?: "koneksi gagal"
                            showNotification("koneksi gagal", "$reason. cek port Wireless Debugging lalu kirim alamat yang baru")
                        }
                    }
                }.onFailure {
                    showNotification("format alamat tidak valid", "gunakan format ip:port atau [ipv6]:port, lalu kirim ulang")
                }
            }
            else -> showNotification("tidak ada input yang diminta", statusText)
        }
    }

    private fun schedulePendingDisplayRollback() {
        val pending = DisplayRepository.pendingRollback(this) ?: return
        displayRollbackJob?.cancel()
        displayRollbackJob = scope.launch {
            val remaining = (pending.deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining > 0L) kotlinx.coroutines.delay(remaining)
            val latest = DisplayRepository.pendingRollback(this@AdbSessionService) ?: return@launch
            if (!AdbRuntime.state.value.connected) {
                showNotification("pemulihan display menunggu koneksi", "rollback tersimpan tetapi sesi adb terputus. sambungkan kembali untuk memulihkan konfigurasi sebelumnya")
                return@launch
            }
            var result = DisplayRepository.restore(latest.previous)
            if (result.isFailure && AdbRuntime.state.value.connected) {
                kotlinx.coroutines.delay(1_000L)
                result = DisplayRepository.restore(latest.previous)
            }
            if (result.isSuccess && DisplayRepository.clearPendingRollback(this@AdbSessionService)) {
                ActivityLogRepository.record(this@AdbSessionService, "display rollback", "${latest.previous.activeWidth}x${latest.previous.activeHeight} · ${latest.previous.activeDensity} dpi", true, "konfigurasi sebelumnya dipulihkan otomatis")
                showNotification("display dipulihkan", "konfigurasi sebelumnya berhasil dikembalikan")
            } else {
                val reason = result.exceptionOrNull()?.message ?: "state rollback belum berhasil dihapus dari penyimpanan"
                ActivityLogRepository.record(this@AdbSessionService, "display rollback", "", false, reason)
                showNotification("rollback display belum tuntas", "$reason. state pemulihan tetap disimpan; sambungkan ulang atau pulihkan secara manual")
            }
        }
    }

    private fun keepDisplayConfiguration() {
        val pending = DisplayRepository.pendingRollback(this)
        if (pending != null && System.currentTimeMillis() >= pending.deadlineEpochMs) {
            schedulePendingDisplayRollback()
            showNotification("waktu konfirmasi display habis", "rollback sudah melewati batas waktu. konfigurasi sebelumnya akan dipulihkan, bukan dipertahankan")
            return
        }
        displayRollbackJob?.cancel()
        displayRollbackJob = null
        if (!DisplayRepository.clearPendingRollback(this)) {
            pending?.let { DisplayRepository.savePendingRollback(this, it.previous, it.deadlineEpochMs) }
            schedulePendingDisplayRollback()
            showNotification("konfirmasi display belum tersimpan", "penyimpanan keputusan pertahankan gagal. rollback tetap dijadwalkan; jangan putuskan adb")
            return
        }
        if (AdbRuntime.sessionEnabled(this)) {
            showNotification("perubahan display dipertahankan", "konfigurasi baru tidak akan di-rollback otomatis")
        } else {
            stopSelf()
        }
    }

    private fun disconnect() {
        val pending = DisplayRepository.pendingRollback(this)
        if (pending != null) {
            if (!AdbRuntime.state.value.connected) {
                // Keep the foreground session eligible for reconnect. Explicitly disabling
                // it here would strand a persisted display rollback until manual relaunch.
                AdbRuntime.setSessionEnabled(this, true)
                step = STEP_RECONNECT
                showNotification("pemutusan adb ditunda", "rollback display masih tertunda tetapi koneksi sedang terputus. sambungkan ulang agar konfigurasi sebelumnya dipulihkan; sesi tetap aktif")
                return
            }
            displayRollbackJob?.cancel()
            displayRollbackJob = scope.launch {
                val restored = DisplayRepository.restore(pending.previous)
                if (restored.isSuccess && DisplayRepository.clearPendingRollback(this@AdbSessionService)) {
                    ActivityLogRepository.record(this@AdbSessionService, "display rollback", "${pending.previous.activeWidth}x${pending.previous.activeHeight} · ${pending.previous.activeDensity} dpi", true, "dipulihkan sebelum sesi adb diputus")
                    displayRollbackJob = null
                    finishDisconnect()
                } else {
                    val reason = restored.exceptionOrNull()?.message ?: "state rollback belum dapat dihapus dari penyimpanan"
                    showNotification("pemutusan adb ditunda", "masih ada perubahan display yang belum dipastikan pulih: $reason. sesi adb tetap aktif agar pemulihan bisa dicoba lagi")
                }
            }
            return
        }
        finishDisconnect()
    }

    private fun finishDisconnect() {
        displayRollbackJob?.cancel()
        displayRollbackJob = null
        AdbRuntime.setSessionEnabled(this, false)
        if (AdbRuntime.state.value.connected) AdbRuntime.disconnect(this)
        step = STEP_NONE
        pairEndpoint = null
        getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit()
            .putInt(KEY_STEP, STEP_NONE).remove(KEY_PAIR_HOST).remove(KEY_PAIR_PORT).apply()
        statusText = "koneksi diputus"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureForeground() {
        val notification = buildNotification("menyiapkan sesi adb", "layanan sesi aktif")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
    }

    private fun createChannel() = NotificationChannels.ensureCreated(this)

    private fun showNotification(title: String, body: String) {
        statusText = body
        getSharedPreferences("adb_session", Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_STEP, step)
            if (pairEndpoint != null) {
                putString(KEY_PAIR_HOST, pairEndpoint!!.host)
                putInt(KEY_PAIR_PORT, pairEndpoint!!.port)
            } else {
                remove(KEY_PAIR_HOST)
                remove(KEY_PAIR_PORT)
            }
        }.apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, body))
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_device_manager)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when (step) {
            STEP_PAIR_ENDPOINT -> {
                addInput(builder, "alamat pairing ip:port", 21)
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "hentikan sesi", servicePending(ACTION_DISCONNECT, 26))
            }
            STEP_PAIR_CODE -> {
                addInput(builder, "kode pairing 6 digit", 22)
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "hentikan sesi", servicePending(ACTION_DISCONNECT, 26))
            }
            STEP_CONNECT_ENDPOINT -> {
                addInput(builder, "alamat koneksi ip:port", 23)
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "hentikan sesi", servicePending(ACTION_DISCONNECT, 26))
            }
            STEP_CONNECTED -> builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "putuskan",
                servicePending(ACTION_DISCONNECT, 24)
            )
            STEP_RECONNECT -> {
                builder.addAction(android.R.drawable.ic_popup_sync, "sambungkan ulang", servicePending(ACTION_RECONNECT, 25))
                if (DisplayRepository.pendingRollback(this) == null) {
                    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "hentikan", servicePending(ACTION_DISCONNECT, 26))
                }
            }
            STEP_PAIRING, STEP_CONNECTING -> builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "batalkan",
                servicePending(ACTION_DISCONNECT, 26)
            )
        }
        if (step == STEP_NONE || step == STEP_CONNECTED) {
            builder.addAction(android.R.drawable.ic_menu_view, "buka aplikasi", activityPending(openIntent))
        }
        return builder.build()
    }

    private fun addInput(builder: NotificationCompat.Builder, label: String, requestCode: Int) {
        val remoteInput = RemoteInput.Builder(KEY_INPUT).setLabel(label).build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "kirim",
            servicePending(ACTION_INPUT, requestCode)
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(false).build()
        builder.addAction(action)
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AdbSessionService::class.java).setAction(action)
        val mutability = when {
            action != ACTION_INPUT -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    private fun activityPending(base: PendingIntent) = base

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (AdbRuntime.state.value.connected) AdbRuntime.disconnect(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_BEGIN_PAIR = "com.devicemanager.adb.BEGIN_PAIR"
        const val ACTION_RECONNECT = "com.devicemanager.adb.RECONNECT"
        const val ACTION_RESTORE = "com.devicemanager.adb.RESTORE"
        const val ACTION_DISCONNECT = "com.devicemanager.adb.DISCONNECT"
        const val ACTION_INPUT = "com.devicemanager.adb.INPUT"
        const val ACTION_SCHEDULE_DISPLAY_ROLLBACK = "com.devicemanager.adb.SCHEDULE_DISPLAY_ROLLBACK"
        const val ACTION_KEEP_DISPLAY = "com.devicemanager.adb.KEEP_DISPLAY"
        const val KEY_INPUT = "adb_remote_input"
        private const val CHANNEL_ID = NotificationChannels.ADB_SESSION_ID
        private const val NOTIFICATION_ID = 4102
        private const val KEY_STEP = "service_step"
        private const val KEY_PAIR_HOST = "service_pair_host"
        private const val KEY_PAIR_PORT = "service_pair_port"
        private const val STEP_NONE = 0
        private const val STEP_PAIR_ENDPOINT = 1
        private const val STEP_PAIR_CODE = 2
        private const val STEP_PAIRING = 3
        private const val STEP_CONNECT_ENDPOINT = 4
        private const val STEP_CONNECTING = 5
        private const val STEP_CONNECTED = 6
        private const val STEP_RECONNECT = 7

        fun start(context: Context, action: String) {
            val intent = Intent(context, AdbSessionService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }

        fun scheduleDisplayRollback(context: Context) = start(context, ACTION_SCHEDULE_DISPLAY_ROLLBACK)
        fun keepDisplayConfiguration(context: Context) = start(context, ACTION_KEEP_DISPLAY)
    }
}
