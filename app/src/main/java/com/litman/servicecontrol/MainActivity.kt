package com.litman.servicecontrol

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.litman.servicecontrol.model.*
import com.litman.servicecontrol.widget.WidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ServiceCtrl"
private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

// ── Design tokens ─────────────────────────────────────────────────────────────
private val BG      = Color(0xFF080A0D)
private val SURF    = Color(0xFF10161C)
private val SURF2   = Color(0xFF151D24)
private val LINE    = Color(0xFF22313A)
private val DIM     = Color(0xFF33424C)
private val MUTED   = Color(0xFF6B7884)
private val SUB     = Color(0xFFA8B3BD)
private val TEXT    = Color(0xFFF4F7FA)
private val GREEN   = Color(0xFF49E68A)
private val RED     = Color(0xFFFF5F6D)

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private lateinit var manager: ServiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ServiceManager(this)
        manager.ensureServiceConfig()
        ensureTermuxPermission()
        Log.d(TAG, "MainActivity: onCreate complete")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = BG)) {
                Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                    ServiceControlApp(manager)
                }
            }
        }
    }

    private fun ensureTermuxPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED) return

        requestPermissions(arrayOf(TERMUX_RUN_COMMAND_PERMISSION), 42)
        Toast.makeText(
            this,
            "Grant Termux command permission for start/stop controls.",
            Toast.LENGTH_LONG
        ).show()
    }
}

// ── App root ──────────────────────────────────────────────────────────────────

@Composable
fun ServiceControlApp(manager: ServiceManager) {
    var services        by remember { mutableStateOf(manager.getSavedServices()) }
    val runtimes         = remember { mutableStateMapOf<String, ServiceRuntime>() }
    val serviceStats     = remember { mutableStateMapOf<String, ServiceStats>() }
    val pendingStates    = remember { mutableStateMapOf<String, String>() }
    val scope            = rememberCoroutineScope()
    
    val initialSettings = remember { manager.getWidgetSettings() }
    var draftSettings   by remember { mutableStateOf(initialSettings) }
    
    var showSettings    by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            services = manager.getSavedServices()
            val statuses = manager.checkAllStatuses(services)
            val stats = manager.collectAllStats(services)
            
            services.forEach { s ->
                val runtime = statuses[s.id] ?: ServiceRuntime.UNKNOWN
                runtimes[s.id] = runtime
                serviceStats[s.id] = stats[s.id] ?: ServiceStats.EMPTY
                
                // Clear pending if reality matches target
                val pending = manager.getPendingState(s.id)
                if (pending != null) {
                    val reachedTarget = if (pending == "STOPPING") {
                        runtime.status == RunStatus.STOPPED
                    } else {
                        runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.DEGRADED
                    }
                    
                    if (reachedTarget) {
                        manager.clearPending(s.id)
                        pendingStates.remove(s.id)
                    } else {
                        pendingStates[s.id] = pending
                    }
                } else {
                    pendingStates.remove(s.id)
                }
            }
        }
    }

    fun pushWidget() {
        scope.launch {
            Log.d(TAG, "[ServiceCtrl] pushWidget: pushing to all widget instances")
            WidgetUpdater.refresh(manager.context)
        }
    }

    fun commitSettings(new: WidgetSettings) {
        draftSettings = new
        manager.saveWidgetSettings(new)
        Log.d(TAG, "[ServiceCtrl] commitSettings: saved and forcing widget refresh")
        pushWidget()
    }

    fun updateSettings(new: WidgetSettings, persist: Boolean = false) {
        draftSettings = new
        if (persist) commitSettings(new)
    }

    LaunchedEffect(showSettings, draftSettings) {
        if (showSettings) {
            delay(150)
            manager.saveWidgetSettings(draftSettings)
            WidgetUpdater.refresh(manager.context)
        }
    }

    LaunchedEffect(Unit) {
        while (true) { refreshAll(); delay(15_000) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        // ── Top bar ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SERVICE CONTROL",
                    fontSize = 17.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp, color = TEXT
                )
                Text(
                    text = "TERMUX  /  PM2",
                    fontSize = 9.sp, letterSpacing = 2.sp, color = MUTED
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(SURF, RoundedCornerShape(10.dp))
                    .border(1.dp, LINE, RoundedCornerShape(10.dp))
                    .clickable { showSettings = !showSettings },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (showSettings) "✕" else "⚙",
                    fontSize = 15.sp,
                    color = if (showSettings) TEXT else MUTED
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Spacer(Modifier.height(8.dp))

        if (showSettings) {
            SettingsPane(
                settings = draftSettings,
                onUpdate = ::updateSettings,
                onApply  = ::commitSettings
            )
        } else {
            ServiceListPane(
                services        = services,
                runtimes        = runtimes,
                stats           = serviceStats,
                pendingStates   = pendingStates,
                manager         = manager,
                onRefresh       = ::refreshAll,
                onPushWidget    = ::pushWidget
            )
        }
    }
}

// ── Reusable ──────────────────────────────────────────────────────────────────

@Composable
private fun HRule() = Box(
    Modifier.fillMaxWidth().height(1.dp).background(LINE)
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 8.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp, color = DIM,
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
    )
}

// ── Settings pane ─────────────────────────────────────────────────────────────

@Composable
fun SettingsPane(
    settings: WidgetSettings,
    onUpdate: (WidgetSettings, Boolean) -> Unit,
    onApply: (WidgetSettings) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Typography ──────────────────────────────────────
        item { SectionLabel("TYPOGRAPHY") }

        item {
            SettingRow(label = "Name Size", value = "${settings.nameSize.toInt()} sp") {
                SettingSlider(
                    value = settings.nameSize, min = 8f, max = 22f,
                    onValueChange = { onUpdate(settings.copy(nameSize = it), false) },
                    onFinished    = { onApply(settings.copy(nameSize = it)) }
                )
            }
        }
        item {
            SettingRow(label = "Meta Size", value = "${settings.metaSize.toInt()} sp") {
                SettingSlider(
                    value = settings.metaSize, min = 6f, max = 16f,
                    onValueChange = { onUpdate(settings.copy(metaSize = it), false) },
                    onFinished    = { onApply(settings.copy(metaSize = it)) }
                )
            }
        }

        item { Spacer(Modifier.height(6.dp)); SectionLabel("LAYOUT") }

        item {
            SettingRow(label = "Padding", value = "${settings.padding.toInt()} dp") {
                SettingSlider(
                    value = settings.padding, min = 4f, max = 28f,
                    onValueChange = { onUpdate(settings.copy(padding = it), false) },
                    onFinished    = { onApply(settings.copy(padding = it)) }
                )
            }
        }
        item {
            SettingRow(label = "Row Gap", value = "${settings.rowSpacing.toInt()} dp") {
                SettingSlider(
                    value = settings.rowSpacing, min = 2f, max = 18f,
                    onValueChange = { onUpdate(settings.copy(rowSpacing = it), false) },
                    onFinished    = { onApply(settings.copy(rowSpacing = it)) }
                )
            }
        }
        item {
            SettingRow(label = "Button Scale", value = "${(settings.actionScale * 100).toInt()} %") {
                SettingSlider(
                    value = settings.actionScale, min = 0.75f, max = 1.35f,
                    onValueChange = { onUpdate(settings.copy(actionScale = it), false) },
                    onFinished    = { onApply(settings.copy(actionScale = it)) }
                )
            }
        }
        item {
            SettingRow(label = "Corner Radius", value = "${settings.cornerRadius.toInt()} dp") {
                SettingSlider(
                    value = settings.cornerRadius, min = 0f, max = 28f,
                    onValueChange = { onUpdate(settings.copy(cornerRadius = it), false) },
                    onFinished    = { onApply(settings.copy(cornerRadius = it)) }
                )
            }
        }
        item {
            SettingRow(label = "Opacity", value = "${(settings.opacity / 255f * 100).toInt()} %") {
                SettingSlider(
                    value = settings.opacity.toFloat(), min = 60f, max = 255f,
                    onValueChange = { onUpdate(settings.copy(opacity = it.toInt()), false) },
                    onFinished    = { onApply(settings.copy(opacity = it.toInt())) }
                )
            }
        }

        // ── Toggles ──────────────────────────────────────────
        item { Spacer(Modifier.height(6.dp)); SectionLabel("DISPLAY") }

        item {
            SettingToggle(
                label    = "Uptime",
                subtitle = "Show service uptime when running",
                value    = settings.showMemory
            ) { onUpdate(settings.copy(showMemory = it), true) }
        }
        item {
            SettingToggle(
                label    = "Column Headers",
                subtitle = "Show SERVICE / CTRL labels",
                value    = settings.showColumnHeaders
            ) { onUpdate(settings.copy(showColumnHeaders = it), true) }
        }

        // ── Font ────────────────────────────────────────────
        item { Spacer(Modifier.height(6.dp)); SectionLabel("FONT") }
        item {
            FontPicker(settings.fontStyle) { onUpdate(settings.copy(fontStyle = it), true) }
        }

        // ── Theme ──────────────────────────────────────────
        item { Spacer(Modifier.height(6.dp)); SectionLabel("THEME") }
        item {
            ThemePicker(settings.theme) { onUpdate(settings.copy(theme = it), true) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun SettingRow(label: String, value: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = SUB, fontSize = 12.sp)
            Text(value, color = TEXT, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        content()
    }
}

// onFinished is called once when the user releases the slider thumb
@Composable
fun SettingSlider(
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    onFinished: ((Float) -> Unit)? = null
) {
    var latestValue by remember(value) { mutableStateOf(value) }

    Slider(
        value = value,
        onValueChange = {
            latestValue = it
            onValueChange(it)
        },
        onValueChangeFinished = { onFinished?.invoke(latestValue) },
        valueRange = min..max,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = GREEN,
            activeTrackColor = GREEN,
            inactiveTrackColor = DIM
        )
    )
}

@Composable
fun SettingToggle(label: String, subtitle: String, value: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = TEXT, fontSize = 13.sp)
            Text(subtitle, color = MUTED, fontSize = 10.sp)
        }
        Switch(
            checked = value,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = GREEN,
                uncheckedThumbColor = MUTED,
                uncheckedTrackColor = DIM,
                uncheckedBorderColor = DIM
            )
        )
    }
    HRule()
}

// ── Font picker ───────────────────────────────────────────────────────────────

@Composable
fun FontPicker(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FontCard(
            id = "SANS", label = "Sans-serif", subtitle = "Clean · modern",
            preview = "Aa", fontFamily = FontFamily.Default,
            isSelected = current == "SANS", onSelect = onSelect,
            modifier = Modifier.weight(1f)
        )
        FontCard(
            id = "MONO", label = "Monospace", subtitle = "Terminal · ops",
            preview = "Aa", fontFamily = FontFamily.Monospace,
            isSelected = current == "MONO", onSelect = onSelect,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun FontCard(
    id: String, label: String, subtitle: String, preview: String,
    fontFamily: FontFamily, isSelected: Boolean,
    onSelect: (String) -> Unit, modifier: Modifier = Modifier
) {
    val bg = if (isSelected) Color(0xFF0D2015) else SURF

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, if (isSelected) GREEN else LINE, RoundedCornerShape(10.dp))
            .clickable { onSelect(id) }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(preview, fontSize = 24.sp, fontFamily = fontFamily,
             fontWeight = FontWeight.Bold,
             color = if (isSelected) GREEN else SUB)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
             color = if (isSelected) TEXT else SUB)
        Text(subtitle, fontSize = 9.sp, color = MUTED)
    }
}

// ── Theme picker ─────────────────────────────────────────────────────────────

@Composable
fun ThemePicker(current: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rows = Themes.ALL.chunked(3)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { theme ->
                    ThemeCard(theme, current == theme.id, onSelect, Modifier.weight(1f))
                }
                // Fill up empty space if last row is incomplete
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun ThemeCard(
    theme: AppTheme,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val color = Color(theme.accent)
    val bg    = if (isSelected) Color(theme.accentBg) else SURF

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, if (isSelected) color else LINE, RoundedCornerShape(10.dp))
            .clickable { onSelect(theme.id) }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(16.dp)
                .background(color.copy(alpha = if (isSelected) 1f else 0.4f), RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(theme.name, fontSize = 9.sp, color = if (isSelected) TEXT else MUTED,
             fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── Service list ──────────────────────────────────────────────────────────────

@Composable
fun ServiceListPane(
    services: List<ServiceItem>,
    runtimes: Map<String, ServiceRuntime>,
    stats: Map<String, ServiceStats>,
    pendingStates: Map<String, String>,
    manager: ServiceManager,
    onRefresh: () -> Unit,
    onPushWidget: () -> Unit
) {
    val panels  = services.filter { it.checkMode != StatusCheckMode.ACTION }
    val actions = services.filter { it.checkMode == StatusCheckMode.ACTION }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            DiagnosticsStrip(
                hasTermuxPermission = manager.hasRunCommandPermission(),
                serviceCount = panels.size,
                runningCount = runtimes.values.count {
                    it.status == RunStatus.RUNNING || it.status == RunStatus.DEGRADED
                }
            )
        }
        if (panels.isNotEmpty()) {
            item { SectionLabel("PROCESSES") }
            items(panels) { service ->
                ServiceRow(
                    service      = service,
                    runtime      = runtimes[service.id] ?: ServiceRuntime.UNKNOWN,
                    stats        = stats[service.id] ?: ServiceStats.EMPTY,
                    isPending    = pendingStates.containsKey(service.id),
                    manager      = manager,
                    onRefresh    = onRefresh,
                    onPushWidget = onPushWidget
                )
            }
        }
        if (actions.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)); SectionLabel("ACTIONS") }
            items(actions) { action ->
                ActionRow(action, manager, onRefresh)
            }
        }
    }
}

@Composable
fun DiagnosticsStrip(
    hasTermuxPermission: Boolean,
    serviceCount: Int,
    runningCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SURF2)
            .border(1.dp, LINE, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("APP DIAGNOSTICS", color = SUB, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text("$runningCount/$serviceCount services responding", color = MUTED, fontSize = 11.sp)
        }
        Text(
            text = if (hasTermuxPermission) "TERMUX OK" else "TERMUX BLOCKED",
            color = if (hasTermuxPermission) GREEN else RED,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
fun ServiceRow(
    service: ServiceItem,
    runtime: ServiceRuntime,
    stats: ServiceStats,
    isPending: Boolean,
    manager: ServiceManager,
    onRefresh: () -> Unit,
    onPushWidget: () -> Unit
) {
    val scope      = rememberCoroutineScope()
    val isRunning  = runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.DEGRADED
    val isUnknown  = runtime.status == RunStatus.UNKNOWN
    val canToggle  = !isPending && ((isRunning && service.canStop) || (!isRunning && service.canStart))
    
    // Use current theme colors
    val settings   = manager.getWidgetSettings()
    val theme      = Themes.find(settings.theme)
    val accent     = Color(theme.accent)
    val accentBg   = Color(theme.accentBg)

    val modeStr = serviceDiagnosticMode(service)
    val runtimeDetail = when {
        isPending -> "pending ${manager.getPendingState(service.id)?.lowercase() ?: "action"}"
        runtime.detail.isNotBlank() -> runtime.detail
        else -> "waiting for first status check"
    }
    val statsText = serviceStatsText(stats)

    // Visual state derived from pending / runtime
    val nameColor  = if (isPending) MUTED else TEXT
    val statusText = if (isPending) "···" else statusLabel(runtime)
    val statusColor = if (isPending) DIM else if (isRunning) accent else RED
    
    val btnBg = when {
        isPending  -> Color(0xFF151520)
        !canToggle -> Color(0xFF15151B)
        isRunning  -> accentBg
        else       -> Color(0xFF1E1010)
    }
    val btnFg = when {
        isPending  -> DIM
        !canToggle -> MUTED
        isRunning  -> accent
        isUnknown  -> MUTED
        else       -> RED
    }
    val btnLabel = when {
        isPending  -> "···"
        !canToggle -> "LOCK"
        isRunning  -> "STOP"
        else       -> "START"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SURF)
            .border(1.dp, LINE, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .background(
                    statusColor,
                    RoundedCornerShape(4.dp)
                )
        )
        Spacer(Modifier.width(12.dp))

        // Name + meta
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = service.label, color = nameColor,
                fontWeight = FontWeight.Medium, fontSize = 14.sp
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = if (isPending) "$statusText  ·  $runtimeDetail" else "$modeStr  ·  $statusText",
                color = MUTED, fontSize = 11.sp
            )
            Text(
                text = runtimeDetail,
                color = DIM,
                fontSize = 10.sp
            )
            if (statsText.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statsText,
                    color = resourceImpactColor(stats.impact),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Power button — disabled while pending
        Box(
            modifier = Modifier
                .background(btnBg, RoundedCornerShape(9.dp))
                .border(1.dp, btnFg.copy(alpha = 0.30f), RoundedCornerShape(9.dp))
                .then(
                    if (canToggle) Modifier.clickable {
                        scope.launch {
                            Log.d(TAG, "[ServiceCtrl] ServiceRow TAP: id=${service.id} action=${if (isRunning) "STOP" else "START"}")
                            val commandStarted = manager.togglePower(service.id, isRunning)
                            onRefresh()
                            onPushWidget()
                            if (!commandStarted) return@launch

                            scope.launch {
                                manager.waitForToggleCompletion(service.id, isRunning)
                                onRefresh()
                                onPushWidget()
                            }
                        }
                    } else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = btnLabel, color = btnFg,
                fontSize = 10.sp,
                fontWeight = if (isPending) FontWeight.Normal else FontWeight.Bold,
                letterSpacing = if (isPending) 0.sp else 0.8.sp
            )
        }
    }
}

private fun serviceDiagnosticMode(service: ServiceItem): String = when (service.checkMode) {
    StatusCheckMode.PORT -> if (service.ports.isEmpty()) "ports: none" else "ports: ${service.ports.joinToString(",")}"
    StatusCheckMode.PROCESS -> "process: ${service.processMatch ?: "unset"}"
    StatusCheckMode.ACTION -> "action"
}

private fun serviceStatsText(stats: ServiceStats): String {
    if (stats.processCount == 0) return stats.detail
    return "${stats.processCount} proc  ·  ${formatOneDecimal(stats.cpuPercent)}% cpu  ·  ${formatOneDecimal(stats.memoryMb)} MB  ·  ${stats.impact.name.lowercase()} impact"
}

private fun resourceImpactColor(impact: ResourceImpact): Color = when (impact) {
    ResourceImpact.LOW -> GREEN
    ResourceImpact.MEDIUM -> Color(0xFFFFB84D)
    ResourceImpact.HIGH -> RED
    ResourceImpact.UNKNOWN -> DIM
}

private fun formatOneDecimal(value: Float): String {
    val scaled = kotlin.math.round(value * 10f).toInt()
    return "${scaled / 10}.${kotlin.math.abs(scaled % 10)}"
}

@Composable
fun ActionRow(action: ServiceItem, manager: ServiceManager, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SURF2)
            .border(1.dp, LINE, RoundedCornerShape(12.dp))
            .clickable {
                Log.d(TAG, "ActionRow: running ${action.label}")
                if (action.id == "runfull") {
                    manager.startAllEligible()
                } else if (action.id == "stopall") {
                    manager.stopAll()
                } else {
                    manager.runTermuxScript(action.scriptPath)
                }
                onRefresh()
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚡", fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
        Text(action.label, color = TEXT, fontWeight = FontWeight.Medium,
             fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("RUN", color = Color(0xFF0088CC),
             fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}
