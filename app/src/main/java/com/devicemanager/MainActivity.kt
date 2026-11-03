package com.devicemanager

import android.Manifest
import android.content.Context
import android.graphics.Typeface
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.devicemanager.adb.AdbRuntime
import com.devicemanager.adb.AdbSessionService
import com.devicemanager.apps.AppRepository
import com.devicemanager.apps.AndroidUserRepository
import com.devicemanager.data.ActivityLogRepository
import com.devicemanager.device.DeviceInfo
import com.devicemanager.display.DisplayRepository
import com.devicemanager.display.DisplayStatus
import com.devicemanager.display.DisplayTarget
import com.devicemanager.display.DisplayScale
import com.devicemanager.terminal.TerminalCommandRisk
import com.devicemanager.monitor.DeviceMonitor
import com.devicemanager.system.SystemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val AccentOptions = listOf(
    "blue" to Color(0xFF8BA7FF),
    "teal" to Color(0xFF60D6C3),
    "violet" to Color(0xFFC1A1FF),
    "amber" to Color(0xFFF2C078),
    "rose" to Color(0xFFFF9FB3)
)
private val LocalAccent = compositionLocalOf { AccentOptions.first().second }
private val LocalFaFont = compositionLocalOf { FontFamily.Default }
private val Good = Color(0xFF45D6A0)
private val Warn = Color(0xFFFFC46B)
private val Danger = Color(0xFFFF7777)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        AdbRuntime.restorePaired(this)
        if (AdbRuntime.sessionEnabled(this) && AdbRuntime.state.value.phase !in setOf("pairing", "connecting") && !AdbRuntime.state.value.connected) {
            AdbSessionService.start(this, AdbSessionService.ACTION_RESTORE)
        }
        setContent { DeviceManagerRoot() }
    }
}

@Composable
private fun DeviceManagerRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
    var accentIndex by remember { mutableIntStateOf(prefs.getInt("accent_index", 0).coerceIn(0, AccentOptions.lastIndex)) }
    val accent = AccentOptions[accentIndex].second
    val faFont = remember(context) { FontFamily(Typeface.createFromAsset(context.assets, "fa-solid-900.ttf")) }
    val colorScheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color(0xFF10182B), secondary = Good,
        background = Color(0xFF10141C), surface = Color(0xFF191F29), surfaceVariant = Color(0xFF252D39),
        onBackground = Color(0xFFE7EBF2), onSurface = Color(0xFFE7EBF2), onSurfaceVariant = Color(0xFFA1ADBC),
        outline = Color(0xFF343F4E), error = Danger
    ) else lightColorScheme(
        primary = accent, secondary = Color(0xFF147C5D), background = Color(0xFFF3F5F9),
        surface = Color.White, surfaceVariant = Color(0xFFE8EDF5), onBackground = Color(0xFF18202C),
        onSurface = Color(0xFF18202C), outline = Color(0xFFD4DCE7), error = Color(0xFFB3261E)
    )
    CompositionLocalProvider(LocalAccent provides accent, LocalFaFont provides faFont) {
        MaterialTheme(colorScheme = colorScheme) {
            MainShell(
                dark = dark,
                onDarkChange = { dark = it; prefs.edit().putBoolean("dark", it).apply() },
                accentIndex = accentIndex,
                onAccentChange = { index ->
                    accentIndex = index.coerceIn(0, AccentOptions.lastIndex)
                    prefs.edit().putInt("accent_index", accentIndex).apply()
                }
            )
        }
    }
}

@Composable
private fun MainShell(dark: Boolean, onDarkChange: (Boolean) -> Unit, accentIndex: Int, onAccentChange: (Int) -> Unit) {
    val context = LocalContext.current
    val adb by AdbRuntime.state.collectAsState()
    var route by remember { mutableStateOf("home") }
    var device by remember { mutableStateOf(DeviceInfo.read(context)) }
    var monitor by remember { mutableStateOf(DeviceMonitor.read(context)) }
    var safetyMode by remember { mutableStateOf(DisplayRepository.safetyMode(context)) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(route) {
        device = DeviceInfo.read(context)
        if (route == "home" || route == "monitor") {
            while (true) {
                monitor = withContext(Dispatchers.IO) { DeviceMonitor.refresh(context) }
                delay(2500)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FaIcon(0xf10b, color = LocalAccent.current, size = 18)
                            Text("device manager", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("${device.manufacturer} ${device.model}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        Modifier.background(if (adb.connected) Good.copy(alpha = .13f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FaIcon(0xf0c1, color = if (adb.connected) Good else Warn, size = 12)
                        Spacer(Modifier.width(7.dp))
                        Text(if (adb.connected) "adb aktif" else "adb offline", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                val items = listOf(Triple("home", "beranda", 0xf015), Triple("display", "display", 0xf108), Triple("apps", "aplikasi", 0xf00a), Triple("adb", "adb", 0xf0c1), Triple("more", "lainnya", 0xf141))
                items.forEach { (key, label, glyph) ->
                    NavigationBarItem(
                        selected = route == key || (key == "more" && route in setOf("monitor", "system", "terminal", "settings", "activity")),
                        onClick = { route = key },
                        icon = { FaIcon(glyph, size = 19) },
                        label = { Text(label, fontSize = 10.sp) },
                        alwaysShowLabel = true
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = remember { SnackbarHostState() }) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                "home" -> HomeScreen(device, monitor, adb.connected) { route = it }
                "display" -> DisplayScreen(safetyMode, adb.connected) { msg -> toast = msg }
                "apps" -> AppsScreen { msg -> toast = msg }
                "adb" -> AdbScreen { msg -> toast = msg }
                "monitor" -> MonitorScreen(monitor)
                "system" -> SystemScreen { msg -> toast = msg }
                "terminal" -> TerminalScreen { msg -> toast = msg }
                "settings" -> SettingsScreen(dark, onDarkChange, accentIndex, onAccentChange, safetyMode, { enabled ->
                    if (DisplayRepository.setSafetyMode(context, enabled)) safetyMode = enabled
                    else toast = "status safety mode gagal disimpan"
                }) { msg -> toast = msg }
                "activity" -> ActivityScreen()
                else -> MoreScreen { route = it }
            }
            toast?.let { message ->
                LaunchedEffect(message) { delay(2800); toast = null }
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp
                ) { Text(message, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            iconForText(title)?.let { FaIcon(it, color = LocalAccent.current, size = 21) }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Panel(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp), border = CardDefaults.outlinedCardBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    iconForText(title)?.let { FaIcon(it, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 13) }
                    Text(title.uppercase(Locale.ROOT), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            iconForText(label)?.let { FaIcon(it, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 12) }
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1.2f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun MetricTile(title: String, value: String, detail: String, fraction: Float? = null, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                iconForText(title)?.let { FaIcon(it, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 12) }
                Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = LocalAccent.current, trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

private fun iconForText(value: String): Int? {
    val text = value.lowercase(Locale.ROOT)
    return when {
        "beranda" in text || "dashboard" in text || "home" in text || "ringkasan" in text -> 0xf015
        "perangkat" in text || "device" in text -> 0xf10b
        "display" in text || "resolusi" in text -> 0xf108
        "density" in text || "dpi" in text || "skala" in text -> 0xf1de
        "cpu" in text || "soc" in text || "chip" in text -> 0xf2db
        "ram" in text || "memori" in text -> 0xf538
        "storage" in text || "penyimpanan" in text -> 0xf0a0
        "baterai" in text || "battery" in text -> 0xf240
        "suhu" in text || "thermal" in text || "temperature" in text -> 0xf2c9
        "aplikasi" in text || "package" in text || "app manager" in text -> 0xf00a
        "adb" in text || "pairing" in text || "koneksi" in text || "connect" in text -> 0xf0c1
        "terminal" in text || "shell" in text || "command" in text -> 0xf120
        "monitor" in text || "usage" in text -> 0xf201
        "system" in text || "sistem" in text || "pengaturan" in text || "settings" in text -> 0xf013
        "activity" in text || "riwayat" in text || "log" in text -> 0xf1da
        "safety" in text || "aman" in text || "perlindungan" in text -> 0xf3ed
        "reset" in text || "pulihkan" in text || "pemulihan" in text || "rollback" in text -> 0xf2f1
        "manual" in text || "konfigurasi" in text -> 0xf0ad
        "warning" in text || "peringatan" in text || "bahaya" in text -> 0xf071
        "hapus" in text || "delete" in text || "clear" in text -> 0xf1f8
        "baca" in text || "refresh" in text || "ambil" in text -> 0xf021
        "search" in text || "cari" in text -> 0xf002
        "tema" in text || "warna" in text || "color" in text -> 0xf53f
        "informasi" in text || "detail" in text || "status" in text -> 0xf05a
        "user" in text || "profil" in text -> 0xf007
        "proses" in text || "process" in text -> 0xf013
        "uptime" in text || "waktu" in text || "time" in text -> 0xf017
        "tegangan" in text || "voltage" in text -> 0xf0e7
        "hardware" in text || "arsitektur" in text -> 0xf2db
        "versi" in text || "version" in text || "release" in text -> 0xf02b
        "ukuran" in text || "size" in text -> 0xf065
        "manufacturer" in text || "produsen" in text || "model" in text -> 0xf1b2
        "animasi" in text || "animation" in text -> 0xf0d0
        "izin" in text || "permission" in text -> 0xf2bb
        "pertahankan" in text || "simpan" in text -> 0xf00c
        "batal" in text || "cancel" in text -> 0xf00d
        "default" in text -> 0xf0e2
        "preset" in text -> 0xf0b0
        "akses cepat" in text -> 0xf0e7
        "lainnya" in text || "more" in text -> 0xf141
        "device" in text || "perangkat" in text -> 0xf10b
        else -> null
    }
}

@Composable
private fun FaIcon(codePoint: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, size: Int = 16) {
    Text(
        text = String(Character.toChars(codePoint)),
        modifier = modifier,
        color = color,
        fontFamily = LocalFaFont.current,
        fontSize = size.sp,
        lineHeight = (size + 2).sp
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FaIcon(iconForText(text) ?: 0xf00c, size = 14)
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) Danger else MaterialTheme.colorScheme.onSurface)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FaIcon(iconForText(text) ?: 0xf0c7, size = 13)
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HomeScreen(device: DeviceInfo.Snapshot, monitor: DeviceMonitor.Snapshot, adbConnected: Boolean, navigate: (String) -> Unit) {
    PageColumn {
        PageTitle("ringkasan perangkat", "informasi dibaca dari perangkat yang sedang menjalankan aplikasi")
        Panel("perangkat") {
            Text("${device.manufacturer} ${device.model}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("android ${device.android} · api ${device.api}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
            InfoRow("soc", device.soc)
            InfoRow("hardware", device.hardware)
            InfoRow("resolusi", device.resolution)
            InfoRow("density", "${device.densityDpi} dpi")
            InfoRow("arsitektur", device.abis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("ram terpakai", DeviceMonitor.formatBytes(monitor.ramUsed), "dari ${DeviceMonitor.formatBytes(monitor.ramTotal)}", monitor.ramUsed.toFloat() / monitor.ramTotal.coerceAtLeast(1), Modifier.weight(1f))
            MetricTile("penyimpanan", DeviceMonitor.formatBytes(monitor.storageUsed), "tersedia ${DeviceMonitor.formatBytes(monitor.storageAvailable)}", monitor.storageUsed.toFloat() / monitor.storageTotal.coerceAtLeast(1), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("baterai", monitor.batteryPercent?.let { "$it%" } ?: "n/a", when (monitor.charging) { true -> "sedang mengisi"; false -> "tidak mengisi"; null -> "status tidak tersedia" }, modifier = Modifier.weight(1f))
            MetricTile("cpu", monitor.cpuPercent?.let { "${it.toInt()}%" } ?: "—", if (monitor.cpuPercent == null) monitor.cpuStatus.take(24) else "${monitor.cpuCores} logical cores", modifier = Modifier.weight(1f))
        }
        Panel("status adb", trailing = { Text(if (adbConnected) "terhubung" else "offline", color = if (adbConnected) Good else Warn, fontSize = 12.sp, fontWeight = FontWeight.Bold) }) {
            Text("adb dibutuhkan untuk operasi display, kontrol aplikasi, pengaturan sistem, dan terminal shell.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton("buka pengelola adb", { navigate("adb") }, modifier = Modifier.fillMaxWidth())
        }
        Panel("akses cepat") {
            listOf("display" to "resolusi dan density", "apps" to "paket aplikasi", "monitor" to "monitor sistem", "terminal" to "adb shell terminal").forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().clickable { navigate(key) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), fontSize = 14.sp)
                    FaIcon(0xf054, color = LocalAccent.current, size = 13)
                }
            }
        }
    }
}

@Composable
private fun DisplayScreen(safetyMode: Boolean, adbConnected: Boolean, notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<DisplayStatus?>(null) }
    var baseline by remember { mutableStateOf(DisplayRepository.baseline(context)) }
    var selectedScale by remember { mutableFloatStateOf(1f) }
    var widthInput by remember { mutableStateOf("") }
    var heightInput by remember { mutableStateOf("") }
    var densityInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var queuedTarget by remember { mutableStateOf<DisplayTarget?>(null) }
    var queuedLabel by remember { mutableStateOf("preset") }
    var queuedResetSize by remember { mutableStateOf(false) }
    var queuedResetDensity by remember { mutableStateOf(false) }
    var pendingDeadline by remember { mutableLongStateOf(DisplayRepository.pendingRollback(context)?.deadlineEpochMs ?: 0L) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var confirmBaselineRedetect by remember { mutableStateOf(false) }

    suspend fun reloadStatus() {
        if (!adbConnected) return
        busy = true
        val result = DisplayRepository.readStatus()
        result.onSuccess { fresh ->
            status = fresh
            runCatching { DisplayRepository.captureBaselineIfMissing(context, fresh) }
                .onSuccess { baseline = it }
                .onFailure { notify(it.message ?: "baseline gagal disimpan; preset tetap dikunci") }
            if (widthInput.isBlank()) widthInput = fresh.activeWidth.toString()
            if (heightInput.isBlank()) heightInput = fresh.activeHeight.toString()
            if (densityInput.isBlank()) densityInput = fresh.activeDensity.toString()
        }.onFailure { notify(it.message ?: "gagal membaca status display") }
        busy = false
    }

    LaunchedEffect(adbConnected) {
        if (adbConnected) {
            reloadStatus()
            if (DisplayRepository.pendingRollback(context) != null) {
                AdbSessionService.scheduleDisplayRollback(context)
            }
        } else status = null
    }

    LaunchedEffect(pendingDeadline) {
        if (pendingDeadline <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < pendingDeadline) {
            secondsLeft = ((pendingDeadline - System.currentTimeMillis() + 999L) / 1000L).toInt().coerceAtLeast(0)
            delay(250)
        }
        secondsLeft = 0
        delay(900)
        val pending = DisplayRepository.pendingRollback(context)
        if (pending == null) {
            pendingDeadline = 0L
            reloadStatus()
            notify("countdown selesai; status pemulihan diperbarui")
        } else {
            notify("waktu rollback habis, tetapi pemulihan belum terverifikasi; periksa koneksi adb")
        }
    }

    fun queueTarget(target: DisplayTarget, label: String, resetSize: Boolean = false, resetDensity: Boolean = false) {
        queuedTarget = target
        queuedLabel = label
        queuedResetSize = resetSize
        queuedResetDensity = resetDensity
    }

    fun applyQueuedTarget() {
        val target = queuedTarget ?: return
        val before = status ?: run { notify("status display belum terbaca"); return }
        queuedTarget = null
        busy = true
        scope.launch {
            // Repository writes rollback state durably and schedules the service-owned timer
            // before this composable scope can be cancelled by navigation.
            val deadline = System.currentTimeMillis() + 15_000L
            pendingDeadline = deadline
            val result = DisplayRepository.applyConfiguration(
                context, target.width, target.height, target.density, before, deadline,
                resetSize = queuedResetSize, resetDensity = queuedResetDensity
            )
            if (result.isSuccess) {
                status = result.getOrNull()
                widthInput = target.width.toString()
                heightInput = target.height.toString()
                densityInput = target.density.toString()
                ActivityLogRepository.record(context, "display apply", "${target.width}x${target.height} · ${target.density} dpi", true, "$queuedLabel; rollback otomatis dalam 15 detik")
                notify("konfigurasi diterapkan sementara; pertahankan dalam 15 detik")
            } else {
                ActivityLogRepository.record(context, "display apply", "${target.width}x${target.height} · ${target.density} dpi", false, result.exceptionOrNull()?.message ?: "penerapan gagal")
                notify(result.exceptionOrNull()?.message ?: "penerapan display gagal")
                val pending = DisplayRepository.pendingRollback(context)
                pendingDeadline = pending?.deadlineEpochMs ?: 0L
                if (pending != null && adbConnected) AdbSessionService.scheduleDisplayRollback(context)
                reloadStatus()
            }
            busy = false
        }
    }

    val selectedTarget = baseline?.let { runCatching { DisplayScale.target(it.width, it.height, it.density, selectedScale) }.getOrNull() }

    PageColumn {
        PageTitle("display manager", "resolusi dan logical density dikelola sebagai satu konfigurasi")
        if (!adbConnected) {
            Panel("koneksi adb diperlukan") {
                Text("sambungkan wireless debugging untuk membaca baseline, melihat override, dan mengubah konfigurasi display.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Panel("status display", trailing = {
            Text(if (status != null) "terbaca" else "belum tersedia", fontSize = 11.sp, color = if (status != null) Good else Warn, fontWeight = FontWeight.Bold)
        }) {
            status?.let { current ->
                InfoRow("resolusi aktif", "${current.activeWidth} × ${current.activeHeight} px")
                InfoRow("resolusi fisik", "${current.physicalWidth} × ${current.physicalHeight} px")
                InfoRow("override resolusi", if (current.hasSizeOverride) "aktif" else "tidak ada")
                InfoRow("logical density aktif", "${current.activeDensity} dpi")
                InfoRow("physical density", "${current.physicalDensity} dpi")
                InfoRow("override density", if (current.hasDensityOverride) "aktif" else "tidak ada")
            } ?: Text("status belum dibaca dari wm size dan wm density.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            baseline?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
                InfoRow("baseline tersimpan", "${it.width} × ${it.height} px")
                InfoRow("density baseline", "${it.density} dpi")
            } ?: Text("baseline belum dapat dipastikan. preset dikunci sampai data fisik berhasil dibaca.", fontSize = 12.sp, color = Warn)
            SecondaryButton("baca ulang status wm", { scope.launch { reloadStatus() } }, modifier = Modifier.fillMaxWidth(), enabled = adbConnected && !busy)
            SecondaryButton("deteksi ulang baseline", { confirmBaselineRedetect = true }, modifier = Modifier.fillMaxWidth(), enabled = adbConnected && status != null && !busy && pendingDeadline == 0L)
        }
        Panel("safety mode", trailing = {
            Text(if (safetyMode) "aktif" else "nonaktif", color = if (safetyMode) Good else Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }) {
            Text(
                if (safetyMode) "mode aman membatasi perubahan ke preset yang dihitung dari baseline tersimpan. density manual dan resolusi manual dikunci."
                else "mode manual aktif. lu dapat memasukkan resolusi dan density sendiri, tetapi validasi, konfirmasi, dan rollback tetap berlaku.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("density di sini adalah logical density Android untuk skala dp, bukan DPI fisik panel.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel("preset skala") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                DisplayScale.presets.forEach { scale ->
                    FilterChip(
                        selected = selectedScale == scale,
                        onClick = { selectedScale = scale },
                        enabled = baseline != null && !busy,
                        label = { Text("${(scale * 100).toInt()}%") }
                    )
                }
            }
            selectedTarget?.let { target ->
                InfoRow("resolusi target", "${target.width} × ${target.height} px")
                InfoRow("density target", "${target.density} dpi")
                Text("target dihitung dari baseline tersimpan, bukan dari override aktif. lebar dan tinggi memakai faktor skala sama; pembulatan ke piksel terdekat.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton(
                    "terapkan preset ${ (selectedScale * 100).toInt() }%",
                    enabled = adbConnected && !busy && status != null && pendingDeadline == 0L,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val base = baseline
                        val current = status
                        if (base != null && current != null) {
                            val resetSize = selectedScale == 1f
                            val resetDensity = selectedScale == 1f && !base.densityWasOverride
                            queueTarget(target, "preset ${(selectedScale * 100).toInt()}%", resetSize, resetDensity)
                        }
                    }
                )
            } ?: Text("preset ini belum dapat digunakan: baseline belum valid atau target berada di luar rentang aman perangkat.", fontSize = 12.sp, color = Warn)
        }
        if (!safetyMode) {
            Panel("konfigurasi manual") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(widthInput, { widthInput = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("lebar px") }, singleLine = true)
                    OutlinedTextField(heightInput, { heightInput = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("tinggi px") }, singleLine = true)
                }
                OutlinedTextField(densityInput, { densityInput = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text("logical density") }, singleLine = true)
                PrimaryButton(
                    "terapkan resolusi + density manual",
                    enabled = adbConnected && !busy && pendingDeadline == 0L && widthInput.toIntOrNull() in 240..10_000 && heightInput.toIntOrNull() in 240..20_000 && densityInput.toIntOrNull() in 72..1_000,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val w = widthInput.toIntOrNull() ?: return@PrimaryButton
                        val h = heightInput.toIntOrNull() ?: return@PrimaryButton
                        val d = densityInput.toIntOrNull() ?: return@PrimaryButton
                        queueTarget(DisplayTarget(w, h, d, 1f), "konfigurasi manual")
                    }
                )
            }
        }
        Panel("pemulihan") {
            val base = baseline
            PrimaryButton(
                "pulihkan baseline tersimpan",
                enabled = adbConnected && !busy && status != null && base != null && pendingDeadline == 0L,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val b = baseline
                    if (b != null) {
                        queueTarget(
                            DisplayTarget(b.width, b.height, b.density, 1f), "pulihkan baseline",
                            resetSize = true,
                            resetDensity = !b.densityWasOverride
                        )
                    }
                }
            )
            SecondaryButton(
                "reset ke default sistem",
                enabled = adbConnected && !busy && status != null && pendingDeadline == 0L,
                modifier = Modifier.fillMaxWidth(), danger = true,
                onClick = {
                    status?.let { current ->
                        queueTarget(DisplayTarget(current.physicalWidth, current.physicalHeight, current.physicalDensity, 1f), "reset default sistem", true, true)
                    }
                }
            )
            Text("pulihkan baseline mengembalikan resolusi fisik dan logical density yang terdeteksi saat baseline pertama disimpan. reset default sistem menghapus kedua override Android.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pendingDeadline > 0L) {
            Panel("konfirmasi perubahan sementara", trailing = {
                Text(if (secondsLeft > 0) "${secondsLeft}s" else "menunggu rollback", color = if (secondsLeft > 0) Warn else Danger, fontWeight = FontWeight.Bold)
            }) {
                Text("konfigurasi baru akan dipertahankan hanya setelah lu memilih tombol pertahankan. jika tidak, service ADB mencoba mengembalikan resolusi dan density sebelumnya.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton("pertahankan perubahan", enabled = adbConnected && pendingDeadline > 0L && secondsLeft > 0, modifier = Modifier.fillMaxWidth(), onClick = {
                    AdbSessionService.keepDisplayConfiguration(context)
                    pendingDeadline = 0L
                    secondsLeft = 0
                    notify("permintaan mempertahankan konfigurasi dikirim ke service adb")
                })
                SecondaryButton("pulihkan sekarang", enabled = adbConnected && !busy, modifier = Modifier.fillMaxWidth(), danger = true, onClick = {
                    val pending = DisplayRepository.pendingRollback(context)
                    if (pending != null) scope.launch {
                        busy = true
                        val result = DisplayRepository.restore(pending.previous)
                        if (result.isSuccess) {
                            if (DisplayRepository.clearPendingRollback(context)) {
                                pendingDeadline = 0L
                                secondsLeft = 0
                                reloadStatus()
                                notify("konfigurasi sebelumnya dipulihkan")
                            } else {
                                notify("konfigurasi berhasil dipulihkan, tetapi state rollback belum berhasil dihapus dari penyimpanan")
                            }
                        } else notify(result.exceptionOrNull()?.message ?: "pemulihan gagal")
                        busy = false
                    }
                })
            }
        }
        Text("rollback 15 detik dijalankan oleh service sesi ADB dan statusnya disimpan. pemulihan tetap tidak bisa dijamin bila perangkat mati, proses/service dihentikan, atau koneksi wireless debugging putus. sambungkan kembali untuk melanjutkan rollback yang tertunda.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmBaselineRedetect) AlertDialog(
        onDismissRequest = { confirmBaselineRedetect = false },
        title = { Text("deteksi ulang baseline?") },
        text = { Text("resolusi baseline akan diambil dari Physical size, sedangkan density baseline memakai logical density yang sedang aktif. lakukan saat konfigurasi display berada pada kondisi yang ingin dijadikan acuan; bila density override sedang aktif, nilai itulah yang akan disimpan.") },
        confirmButton = { TextButton(onClick = {
            confirmBaselineRedetect = false
            scope.launch {
                busy = true
                val result = DisplayRepository.readStatus()
                result.onSuccess { fresh ->
                    runCatching { DisplayRepository.replaceBaseline(context, fresh) }
                        .onSuccess {
                            baseline = it
                            status = fresh
                            selectedScale = 1f
                            widthInput = fresh.activeWidth.toString()
                            heightInput = fresh.activeHeight.toString()
                            densityInput = fresh.activeDensity.toString()
                            notify("baseline baru berhasil disimpan")
                        }
                        .onFailure { notify(it.message ?: "baseline gagal disimpan") }
                }.onFailure { notify(it.message ?: "baseline tidak dapat dideteksi") }
                busy = false
            }
        }) { Text("simpan baseline") } },
        dismissButton = { TextButton(onClick = { confirmBaselineRedetect = false }) { Text("batal") } }
    )

    queuedTarget?.let { target ->
        val before = status
        AlertDialog(
            onDismissRequest = { if (!busy) queuedTarget = null },
            title = { Text("konfirmasi konfigurasi display") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${queuedLabel}: perubahan resolusi dan logical density diterapkan bersama.")
                    before?.let {
                        Text("sekarang: ${it.activeWidth} × ${it.activeHeight} px · ${it.activeDensity} dpi", fontSize = 12.sp)
                    }
                    Text("target: ${target.width} × ${target.height} px · ${target.density} dpi", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("setelah diterapkan, lu punya 15 detik untuk mempertahankan perubahan. bila tidak, aplikasi mencoba rollback ke konfigurasi sebelumnya.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { applyQueuedTarget() }) { Text("terapkan", color = Danger) } },
            dismissButton = { TextButton(onClick = { queuedTarget = null }) { Text("batal") } }
        )
    }
}

@Composable
private fun AppsScreen(notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUser = remember { AndroidUserRepository.currentUserId() }
    var apps by remember { mutableStateOf(emptyList<AppRepository.InstalledApp>()) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<AppRepository.InstalledApp?>(null) }
    var userId by remember { mutableStateOf(currentUser.toString()) }
    var profiles by remember { mutableStateOf(listOf(AndroidUserRepository.UserProfile(currentUser, "profil aktif"))) }
    var usersExpanded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var usersOutput by remember { mutableStateOf("profil android belum dimuat") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppRepository.installed(context) }
        loading = false
    }

    val filtered = apps.filter {
        (showSystem || !it.system || !it.metadataAvailable) &&
            (it.label.contains(query, true) || it.packageName.contains(query, true))
    }

    suspend fun reloadSelectedUser(uid: Int) {
        if (uid == currentUser) {
            apps = withContext(Dispatchers.IO) { AppRepository.installed(context) }
            usersOutput = "menampilkan paket pada profil aktif (user $uid)"
        } else {
            val result = AdbRuntime.shell("pm list packages --user $uid")
            result.fold(
                onSuccess = { output ->
                    apps = AndroidUserRepository.parsePackages(output).map(AppRepository::remotePlaceholder)
                    usersOutput = "${apps.size} paket ditemukan pada user $uid; metadata lokal hanya tersedia untuk profil aktif"
                },
                onFailure = { error ->
                    usersOutput = error.message ?: "gagal membaca paket user $uid"
                    notify(usersOutput)
                }
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
        PageTitle("pengelola aplikasi", "${apps.size} paket pada ${profiles.firstOrNull { it.id == userId.toIntOrNull() }?.name ?: "user $userId"}")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("cari nama atau package") }, singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { usersExpanded = true }) { Text("user $userId") }
                DropdownMenu(expanded = usersExpanded, onDismissRequest = { usersExpanded = false }) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.toString()) },
                            onClick = {
                                usersExpanded = false
                                userId = profile.id.toString()
                                scope.launch {
                                    busy = true
                                    reloadSelectedUser(profile.id)
                                    busy = false
                                }
                            }
                        )
                    }
                }
            }
            SecondaryButton("muat profil", {
                scope.launch {
                    busy = true
                    val result = AdbRuntime.shell("pm list users")
                    result.fold(
                        onSuccess = { output ->
                            profiles = AndroidUserRepository.parseUsers(output, currentUser)
                            usersOutput = output.ifBlank { "android tidak mengembalikan daftar user" }
                            val uid = userId.toIntOrNull() ?: currentUser
                            reloadSelectedUser(uid)
                        },
                        onFailure = { error ->
                            usersOutput = error.message ?: "gagal membaca profil"
                            notify(usersOutput)
                        }
                    )
                    busy = false
                }
            }, modifier = Modifier.weight(1f))
        }
        Text(usersOutput, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = showSystem, onClick = { showSystem = true }, label = { Text("semua") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = !showSystem, onClick = { showSystem = false }, label = { Text("aplikasi pengguna") })
            Spacer(Modifier.weight(1f))
            Text("${filtered.size} hasil", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading || busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
            items(filtered, key = { "${userId}:${it.packageName}" }) { app ->
                Card(Modifier.fillMaxWidth().clickable { selected = app }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(15.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            FaIcon(0xf00a, color = LocalAccent.current, size = 18)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (app.metadataAvailable) "${if (app.system) "sistem" else "pengguna"} · ${if (app.enabled) "aktif" else "nonaktif"} · uid ${app.uid}"
                                else "paket pada user $userId · metadata belum tersedia",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FaIcon(0xf054, color = LocalAccent.current, size = 15)
                    }
                }
            }
        }
    }

    selected?.let { app ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(app.label) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("package", app.packageName, true)
                InfoRow("target user", userId)
                if (app.metadataAvailable) {
                    InfoRow("version", app.version)
                    InfoRow("uid", app.uid.toString())
                    InfoRow("ukuran apk", DeviceMonitor.formatBytes(app.apkBytes))
                    InfoRow("jenis", if (app.system) "sistem" else "pengguna")
                } else {
                    Text("package ditemukan melalui pm list packages. label, version, uid, dan ukuran tidak ditebak karena metadata profil ini tidak dapat dibaca PackageManager aplikasi.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(userId, { userId = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("user id android") }, singleLine = true)
                Text("operasi kontrol memakai user id di atas dan memerlukan koneksi adb aktif.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TextButton(onClick = { pendingAction = "force-stop" }) { Text("force stop") }
                    TextButton(onClick = { pendingAction = "install-existing" }) { Text("install existing") }
                    if (app.metadataAvailable) {
                        TextButton(onClick = { pendingAction = if (app.enabled) "disable-user" else "enable" }) { Text(if (app.enabled) "disable" else "enable") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TextButton(onClick = { pendingAction = "uninstall" }, colors = ButtonDefaults.textButtonColors(contentColor = Danger)) { Text("uninstall") }
                    TextButton(onClick = { selected = null }) { Text("tutup") }
                }
            }
        })
    }

    pendingAction?.let { action ->
        AlertDialog(onDismissRequest = { pendingAction = null }, title = { Text("konfirmasi $action") },
            text = { Text("jalankan $action untuk ${selected?.packageName} pada user ${userId.ifBlank { currentUser.toString() }}? perintah dieksekusi melalui adb shell dan perubahan dapat memengaruhi profil tersebut.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val app = selected ?: return@TextButton
                    val uid = userId.toIntOrNull()
                    if (uid == null || profiles.none { it.id == uid }) { notify("user id tidak ditemukan; muat daftar profil android terlebih dahulu"); pendingAction = null; return@TextButton }
                    if (!app.packageName.matches(Regex("[A-Za-z0-9_.]+"))) { notify("package tidak valid"); pendingAction = null; return@TextButton }
                    val command = when (action) {
                        "force-stop" -> "am force-stop --user $uid ${app.packageName}"
                        "disable-user" -> "pm disable-user --user $uid ${app.packageName}"
                        "enable" -> "pm enable --user $uid ${app.packageName}"
                        "install-existing" -> "cmd package install-existing --user $uid ${app.packageName}"
                        else -> "pm uninstall --user $uid ${app.packageName}"
                    }
                    busy = true
                    scope.launch {
                        val result = AdbRuntime.shell(command)
                        val output = result.fold({ it.ifBlank { "perintah selesai" }.take(160) }, { it.message ?: "perintah gagal" })
                        ActivityLogRepository.record(context, "app $action", app.packageName, result.isSuccess, "user $uid · $output")
                        notify(output)
                        reloadSelectedUser(uid)
                        busy = false
                        pendingAction = null
                        selected = null
                    }
                }) { Text("jalankan") }
            }, dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("batal") } })
    }
}

@Composable
private fun AdbScreen(notify: (String) -> Unit) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AdbSessionService.start(context, AdbSessionService.ACTION_BEGIN_PAIR)
        else notify("izin notifikasi diperlukan untuk input pairing dari notifikasi")
    }
    val adb by AdbRuntime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showForget by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("adb manager", "pairing, koneksi wireless debugging, dan riwayat sesi")
        Panel("status sesi", trailing = { Text(if (adb.connected) "online" else adb.phase, color = if (adb.connected) Good else Warn, fontWeight = FontWeight.Bold, fontSize = 12.sp) }) {
            Text(adb.message, fontSize = 14.sp)
            InfoRow("endpoint", adb.endpoint ?: AdbRuntime.savedEndpoint(context)?.asText() ?: "belum ada", true)
            InfoRow("pairing tersimpan", if (adb.paired || AdbRuntime.paired(context)) "ya" else "belum")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("mulai pairing", {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else AdbSessionService.start(context, AdbSessionService.ACTION_BEGIN_PAIR)
                }, modifier = Modifier.weight(1f), enabled = !adb.connected)
                SecondaryButton("reconnect", { AdbSessionService.start(context, AdbSessionService.ACTION_RECONNECT) }, modifier = Modifier.weight(1f), danger = false)
            }
            if (adb.connected) SecondaryButton("putuskan sesi", { AdbSessionService.start(context, AdbSessionService.ACTION_DISCONNECT) }, modifier = Modifier.fillMaxWidth(), danger = true)
        }
        Panel("alur pairing") {
            Text("1. tekan mulai pairing. service akan memasang notifikasi ongoing.", fontSize = 12.sp)
            Text("2. buka pengaturan developer dan aktifkan wireless debugging.", fontSize = 12.sp)
            Text("3. pilih pair device with pairing code. jangan tutup dialog kode.", fontSize = 12.sp)
            Text("4. dari notifikasi, kirim ip:port pairing, lalu masukkan kode 6 digit.", fontSize = 12.sp)
            Text("5. setelah berhasil, kirim ip:port koneksi. port koneksi berbeda dari port pairing.", fontSize = 12.sp)
            SecondaryButton("buka pengaturan developer", {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { notify("pengaturan developer tidak bisa dibuka langsung") }
            }, modifier = Modifier.fillMaxWidth())
        }
        Panel("manajemen kredensial") {
            Text("private key RSA disimpan terenkripsi AES-GCM; kunci AES berada di Android Keystore. menghapus kredensial lokal tidak otomatis menghapus perangkat dari daftar paired pada pengaturan android.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton("hapus kredensial pairing lokal", { showForget = true }, modifier = Modifier.fillMaxWidth(), danger = true)
            SecondaryButton("hapus alamat koneksi tersimpan", {
                AdbRuntime.removeSavedEndpoint(context)
                ActivityLogRepository.record(context, "remove endpoint", "", true, "alamat koneksi lokal dihapus; pairing key tetap tersimpan")
                notify("alamat koneksi lokal dihapus")
            }, modifier = Modifier.fillMaxWidth())
        }
        Panel("riwayat koneksi") {
            val logs = (context.getSharedPreferences("activity_log", Context.MODE_PRIVATE).getString("entries", "") ?: "")
                .lines().filter { it.isNotBlank() }.take(8)
            if (logs.isEmpty()) Text("belum ada riwayat", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            logs.forEach { raw ->
                val p = raw.split('|')
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${p.getOrNull(1) ?: "event"} · ${p.getOrNull(2).orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(p.getOrNull(4).orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (showForget) AlertDialog(onDismissRequest = { showForget = false }, title = { Text("hapus kredensial adb?") },
        text = { Text("koneksi aktif akan diputus dan kunci lokal dihapus. perangkat mungkin perlu dipair ulang. status paired di pengaturan android tidak otomatis dihapus oleh aplikasi.") },
        confirmButton = { TextButton(onClick = { AdbRuntime.forgetPairing(context); showForget = false; notify("kredensial lokal dihapus") }) { Text("hapus", color = Danger) } },
        dismissButton = { TextButton(onClick = { showForget = false }) { Text("batal") } })
}

@Composable
private fun MonitorScreen(snapshot: DeviceMonitor.Snapshot) {
    PageColumn {
        PageTitle("system monitor", "pembaruan otomatis saat halaman ini terbuka")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("cpu", snapshot.cpuPercent?.let { "${it.toInt()}%" } ?: "—", if (snapshot.cpuPercent == null) snapshot.cpuStatus else "${snapshot.cpuCores} logical cores", modifier = Modifier.weight(1f))
            MetricTile("ram", "${(snapshot.ramUsed * 100 / snapshot.ramTotal.coerceAtLeast(1))}%", "${DeviceMonitor.formatBytes(snapshot.ramUsed)} terpakai", snapshot.ramUsed.toFloat() / snapshot.ramTotal.coerceAtLeast(1), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("baterai", snapshot.batteryPercent?.let { "$it%" } ?: "n/a", when (snapshot.charging) { true -> "charging"; false -> "discharging"; else -> "unknown" }, modifier = Modifier.weight(1f))
            MetricTile("storage", "${(snapshot.storageUsed * 100 / snapshot.storageTotal.coerceAtLeast(1))}%", "${DeviceMonitor.formatBytes(snapshot.storageAvailable)} free", snapshot.storageUsed.toFloat() / snapshot.storageTotal.coerceAtLeast(1), Modifier.weight(1f))
        }
        Panel("detail") {
            InfoRow("status cpu", snapshot.cpuStatus)
            InfoRow("ram total", DeviceMonitor.formatBytes(snapshot.ramTotal))
            InfoRow("ram tersedia", DeviceMonitor.formatBytes(snapshot.ramAvailable))
            InfoRow("penyimpanan total", DeviceMonitor.formatBytes(snapshot.storageTotal))
            InfoRow("penyimpanan tersedia", DeviceMonitor.formatBytes(snapshot.storageAvailable))
            InfoRow("tegangan baterai", snapshot.batteryVoltageMv?.let { "${it} mV" } ?: "tidak tersedia")
            InfoRow("suhu baterai", snapshot.batteryTempC?.let { "${"%.1f".format(it)} °c" } ?: "tidak tersedia")
            InfoRow("uptime", DeviceMonitor.formatUptime(snapshot.uptimeMs))
        }
        Panel("sensor thermal yang dapat dibaca") {
            if (snapshot.thermalSensors.isEmpty()) {
                Text("tidak ada thermal zone yang dapat dibaca oleh UID aplikasi. ini bukan berarti perangkat tidak memiliki sensor suhu.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                snapshot.thermalSensors.forEach { sensor ->
                    InfoRow(sensor.sensor, "${"%.1f".format(sensor.celsius)} °c")
                }
            }
        }
        Text("persentase cpu dihitung dari delta counter /proc/stat; saat akses aplikasi ditolak, monitor mencoba membaca melalui sesi adb shell. sensor suhu bergantung pada thermal sysfs yang diekspos kernel.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SystemScreen(notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var animations by remember { mutableStateOf("belum dibaca") }
    var properties by remember { mutableStateOf("belum dibaca") }
    var processes by remember { mutableStateOf("belum dibaca") }
    var configuration by remember { mutableStateOf("belum dibaca") }
    var busy by remember { mutableStateOf(false) }
    var confirmScale by remember { mutableStateOf<String?>(null) }

    fun applyScale(scale: String) {
        busy = true
        scope.launch {
            val result = SystemRepository.setAnimations(scale)
            ActivityLogRepository.record(context, "animation scale", scale, result.isSuccess, result.exceptionOrNull()?.message ?: "skala animasi diperbarui")
            notify(result.fold({ "skala animasi diubah ke $scale" }, { it.message ?: "gagal" }))
            animations = SystemRepository.animationStatus().getOrDefault("gagal membaca")
            busy = false
        }
    }

    PageColumn {
        PageTitle("system manager", "pengaturan global, proses, dan properti melalui sesi adb")
        Panel("skala animasi") {
            Text("window · transition · animator", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(animations, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("0", "0.5", "1", "1.5", "2").forEach { value ->
                    FilterChip(selected = false, onClick = { confirmScale = value }, enabled = !busy, label = { Text(value) })
                }
            }
            SecondaryButton("baca skala saat ini", {
                busy = true
                scope.launch { animations = SystemRepository.animationStatus().getOrDefault("gagal membaca"); busy = false }
            }, modifier = Modifier.fillMaxWidth())
        }
        Panel("properti sistem") {
            Text(properties, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp)
            PrimaryButton("ambil getprop", {
                busy = true
                scope.launch { properties = SystemRepository.properties().getOrDefault("gagal membaca properti"); busy = false }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
        Panel("proses aktif") {
            Text(processes, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp)
            SecondaryButton("ambil daftar proses", {
                busy = true
                scope.launch { processes = SystemRepository.processes().getOrDefault("gagal membaca proses"); busy = false }
            }, modifier = Modifier.fillMaxWidth())
        }
        Panel("global settings") {
            Text(configuration, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 15.sp)
            SecondaryButton("baca konfigurasi global", {
                busy = true
                scope.launch { configuration = SystemRepository.globalConfiguration().getOrDefault("gagal membaca konfigurasi"); busy = false }
            }, modifier = Modifier.fillMaxWidth())
        }
        Text("perintah sistem dijalankan sebagai adb shell, bukan root. beberapa key, proses, atau properti dapat disembunyikan atau ditolak android/vendor.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    confirmScale?.let { scale ->
        AlertDialog(
            onDismissRequest = { confirmScale = null },
            title = { Text("ubah global animation scale?") },
            text = { Text("ketiga animation scale (window, transition, animator) akan diatur ke $scale. nilai ini berlaku untuk seluruh sistem dan bisa diubah kembali.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { confirmScale = null; applyScale(scale) }) { Text("terapkan") } },
            dismissButton = { TextButton(onClick = { confirmScale = null }) { Text("batal") } }
        )
    }
}

@Composable
private fun TerminalScreen(notify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("terminal belum dibuka. hubungkan adb, lalu tekan mulai terminal.") }
    var terminalOpen by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var confirmCommand by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { AdbRuntime.closeTerminal() }
    }
    LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }

    fun appendOutput(value: String) {
        output = (output + value).takeLast(32_000)
    }

    fun sendCommand(value: String) {
        if (value.isBlank() || !terminalOpen) return
        scope.launch {
            val result = AdbRuntime.writeTerminalLine(value)
            result.onFailure { notify(it.message ?: "gagal menulis ke terminal") }
            if (result.isSuccess) {
                ActivityLogRepository.record(context, "terminal", "adb shell", true, "perintah dikirim ke PTY; isi perintah tidak disimpan")
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("adb shell", "terminal PTY interaktif berbasis ADB Shell v2")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                if (starting) "membuka…" else if (terminalOpen) "terminal aktif" else "mulai terminal",
                onClick = {
                    if (!terminalOpen && !starting) {
                        starting = true
                        scope.launch {
                            val result = AdbRuntime.openInteractiveTerminal()
                            result.fold(
                                onSuccess = { flow ->
                                    terminalOpen = true
                                    appendOutput("\n[terminal terhubung]\n")
                                    scope.launch {
                                        runCatching { flow.collect { chunk -> appendOutput(chunk) } }
                                            .onFailure { appendOutput("\n[error: ${it.message}]\n") }
                                        terminalOpen = false
                                        appendOutput("\n[terminal ditutup]\n")
                                    }
                                },
                                onFailure = { notify(it.message ?: "gagal membuka terminal") }
                            )
                            starting = false
                        }
                    }
                },
                enabled = !terminalOpen && !starting,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton("tutup", {
                AdbRuntime.closeTerminal()
                terminalOpen = false
                notify("terminal ditutup")
            }, danger = true)
            SecondaryButton("bersihkan", { output = "" })
        }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it.take(4096) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("perintah shell") },
            singleLine = true,
            enabled = terminalOpen
        )
        PrimaryButton("kirim perintah", {
            val value = command.trim()
            if (value.isNotBlank()) {
                val risky = TerminalCommandRisk.requiresConfirmation(value)
                if (risky) confirmCommand = value else sendCommand(value)
                command = ""
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = terminalOpen && command.isNotBlank())
        Surface(Modifier.fillMaxWidth().weight(1f), color = Color(0xFF0B1017), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(13.dp)) {
                Text(output, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = Color(0xFFD2E4DA))
            }
        }
        Text("terminal memakai PTY Shell v2 dan hak akses adbd (shell), bukan root. operasi yang mengubah sistem tetap memerlukan izin yang sesuai.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    confirmCommand?.let { cmd ->
        AlertDialog(onDismissRequest = { confirmCommand = null }, title = { Text("perintah berisiko") }, text = {
            Text("perintah ini dapat menghapus data atau mengubah konfigurasi sistem:\n\n$cmd\n\nlanjutkan hanya bila lu memahami dampaknya.")
        }, confirmButton = {
            TextButton(onClick = { confirmCommand = null; sendCommand(cmd) }) { Text("tetap jalankan", color = Danger) }
        }, dismissButton = { TextButton(onClick = { confirmCommand = null }) { Text("batal") } })
    }
}

@Composable
private fun SettingsScreen(dark: Boolean, onDarkChange: (Boolean) -> Unit, accentIndex: Int, onAccentChange: (Int) -> Unit, safetyMode: Boolean, onSafetyModeChange: (Boolean) -> Unit, notify: (String) -> Unit) {
    val context = LocalContext.current
    var confirmDisableSafety by remember { mutableStateOf(false) }
    val power = context.getSystemService(PowerManager::class.java)
    var ignoring by remember { mutableStateOf(Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(context.packageName)) }
    LaunchedEffect(Unit) {
        while (true) {
            ignoring = Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(context.packageName)
            delay(1500)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notify(if (granted) "izin notifikasi diberikan" else "izin notifikasi ditolak") }
    PageColumn {
        PageTitle("pengaturan aplikasi", "preferensi antarmuka dan izin android")
        Panel("tampilan") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("tema gelap", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("gunakan tema gelap atau terang", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(dark, onDarkChange)
            }
        }
        Panel("perlindungan display") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("safety mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("batasi resolusi ke preset baseline dan kunci density manual", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = safetyMode, onCheckedChange = { enabled -> if (enabled) onSafetyModeChange(true) else confirmDisableSafety = true })
            }
            Text("safety mode aktif secara default. mode manual tetap memakai validasi, konfirmasi, dan countdown rollback.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel("warna aksen") {
            Text("pilih warna untuk tombol, indikator, dan elemen aktif", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AccentOptions.forEachIndexed { index, option ->
                    FilterChip(
                        selected = accentIndex == index,
                        onClick = { onAccentChange(index) },
                        label = { Text(option.first) },
                        leadingIcon = {
                            Box(Modifier.size(10.dp).background(option.second, CircleShape))
                        }
                    )
                }
            }
        }
        Panel("aktivitas background") {
            InfoRow("optimasi baterai", if (ignoring) "diabaikan" else "aktif")
            Text("sesi ADB memakai foreground service bertipe connectedDevice, notifikasi ongoing, stopWithTask=false, dan START_STICKY. itu membantu kesinambungan, tetapi bukan jaminan anti-kill.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("beberapa sistem punya pengaturan autostart atau pembatasan background milik vendor tanpa API android yang seragam. cek pengaturan aplikasi secara manual bila service sering dihentikan; aplikasi tidak memanggil activity vendor yang belum tentu tersedia.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton("kelola optimasi baterai", {
                if (Build.VERSION.SDK_INT >= 23) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                        .onFailure { notify("halaman optimasi baterai tidak tersedia") }
                }
            }, modifier = Modifier.fillMaxWidth())
            SecondaryButton("buka info aplikasi", {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth())
        }
        Panel("notifikasi") {
            Text("notifikasi digunakan untuk menampilkan status pairing dan menerima input ip:port serta kode pairing.", fontSize = 12.sp)
            SecondaryButton("minta izin notifikasi", {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else notify("izin runtime notifikasi tidak diperlukan di versi ini")
            }, modifier = Modifier.fillMaxWidth())
        }
        Panel("informasi") {
            InfoRow("aplikasi", "device manager")
            InfoRow("versi", "1.1.0")
            InfoRow("minimum android", "8.0 · api 26")
            InfoRow("adb engine", "adb-kt · pure kotlin")
        }
    }
    if (confirmDisableSafety) AlertDialog(
        onDismissRequest = { confirmDisableSafety = false },
        title = { Text("nonaktifkan safety mode?") },
        text = { Text("mode manual mengizinkan resolusi dan logical density diubah bebas dalam rentang valid. nilai yang keliru dapat membuat antarmuka terlalu kecil, terlalu besar, atau sulit dioperasikan. setiap perubahan tetap memerlukan konfirmasi dan rollback 15 detik, tetapi pemulihan tidak dijamin bila perangkat atau sesi ADB terputus.") },
        confirmButton = { TextButton(onClick = { onSafetyModeChange(false); confirmDisableSafety = false }) { Text("nonaktifkan", color = Danger) } },
        dismissButton = { TextButton(onClick = { confirmDisableSafety = false }) { Text("tetap aktif") } }
    )
}

@Composable
private fun ActivityScreen() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(ActivityLogRepository.read(context)) }
    PageColumn {
        PageTitle("activity log", "catatan sesi dan tindakan adb lokal")
        Panel("riwayat", trailing = { TextButton(onClick = { ActivityLogRepository.clear(context); entries = emptyList() }) { Text("hapus") } }) {
            if (entries.isEmpty()) Text("belum ada aktivitas", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            entries.forEach { raw ->
                val parts = raw.split('|')
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(parts.getOrNull(1)?.uppercase().orEmpty(), Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(if (parts.getOrNull(3) == "true") "ok" else "gagal", fontSize = 10.sp, color = if (parts.getOrNull(3) == "true") Good else Danger)
                    }
                    Text(parts.getOrNull(2).orEmpty(), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(parts.getOrNull(4).orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(parts.firstOrNull()?.toLongOrNull()?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                }
            }
        }
    }
}

@Composable
private fun MoreScreen(navigate: (String) -> Unit) {
    PageColumn {
        PageTitle("fitur lainnya", "alat sistem yang tersedia")
        listOf(
            "monitor" to ("system monitor" to "cpu, ram, baterai, storage, uptime"),
            "system" to ("system manager" to "animasi dan properti sistem"),
            "terminal" to ("adb shell" to "terminal perintah interaktif"),
            "activity" to ("activity log" to "riwayat tindakan dan koneksi"),
            "settings" to ("pengaturan" to "tema, notifikasi, dan background")
        ).forEach { (route, content) ->
            Card(Modifier.fillMaxWidth().clickable { navigate(route) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(17.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(content.first, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(content.second, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FaIcon(0xf054, color = LocalAccent.current, size = 15)
                }
            }
        }
    }
}
