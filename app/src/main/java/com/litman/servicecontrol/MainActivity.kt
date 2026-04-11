package com.litman.servicecontrol

import android.os.Bundle
import android.util.Log
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
import androidx.glance.appwidget.updateAll
import com.litman.servicecontrol.model.*
import com.litman.servicecontrol.widget.ServiceWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ServiceCtrl"

// ── Design tokens ─────────────────────────────────────────────────────────────
private val BG      = Color(0xFF0D0D11)
private val SURF    = Color(0xFF131318)
private val SURF2   = Color(0xFF18181F)
private val LINE    = Color(0xFF1C1C28)
private val DIM     = Color(0xFF2E2E3C)
private val MUTED   = Color(0xFF565666)
private val SUB     = Color(0xFF8888A0)
private val TEXT    = Color(0xFFEEEEF4)
private val GREEN   = Color(0xFF00D966)
private val CYAN    = Color(0xFF00C8DD)
private val AMBER   = Color(0xFFFFB300)
private val RED     = Color(0xFFCC3333)

private fun accentBgOf(style: String) = when (style) {
    "CYAN"  -> Color(0xFF0D2025)
    "AMBER" -> Color(0xFF201900)
    else    -> Color(0xFF0D2015)
}

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private lateinit var manager: ServiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ServiceManager(this)
        manager.ensureServiceConfig()
        Log.d(TAG, "MainActivity: onCreate complete")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = BG)) {
                Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                    ServiceControlApp(manager)
                }
            }
        }
    }
}

// ── App root ──────────────────────────────────────────────────────────────────

@Composable
fun ServiceControlApp(manager: ServiceManager) {
    var services        by remember { mutableStateOf(manager.getSavedServices()) }
    val runtimes         = remember { mutableStateMapOf<String, ServiceRuntime>() }
    val pendingStates    = remember { mutableStateMapOf<String, String>() }
    val scope            = rememberCoroutineScope()
    
    // The "real" settings synced with disk
    var settings        by remember { mutableStateOf(manager.getWidgetSettings()) }
    // The "draft" settings used for live UI updates during slider drag
    var draftSettings   by remember { mutableStateOf(settings) }
    
    var showSettings    by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            services = manager.getSavedServices()
            val statuses = manager.checkAllStatuses(services)
            statuses.forEach { (id, rt) -> runtimes[id] = rt }
            
            // Clean up local pending states if they match reality
            services.forEach { s ->
                val p = manager.getPendingState(s.id)
                if (p != null) pendingStates[s.id] = p else pendingStates.remove(s.id)
            }
        }
    }

    fun pushWidget() {
        scope.launch {
            Log.d(TAG, "[ServiceCtrl] pushWidget: pushing to all widget instances")
            ServiceWidget().updateAll(manager.context)
        }
    }

    fun commitSettings(new: WidgetSettings) {
        settings = new
        manager.saveWidgetSettings(new)
        Log.d(TAG, "[ServiceCtrl] commitSettings: saved and pushing widget update")
        pushWidget()
    }

    LaunchedEffect(Unit) {
        while (true) { refreshAll(); delay(15_000) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .padding(horizontal = 18.dp, vertical = 22.dp)
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
        HRule()
        Spacer(Modifier.height(14.dp))

        if (showSettings) {
            SettingsPane(
                settings = draftSettings,
                onUpdate = { draftSettings = it },
                onApply  = { commitSettings(draftSettings) }
            )
        } else {
            ServiceListPane(
                services        = services,
                runtimes        = runtimes,
                pendingServices = pendingServices,
                manager         = manager,
                onRefresh       = { refreshAll(); pushWidget() }
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
    onUpdate: (WidgetSettings) -> Unit,
    onApply: () -> Unit
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
                    onValueChange = { onUpdate(settings.copy(nameSize = it)) },
                    onFinished    = onApply
                )
            }
        }
        item {
            SettingRow(label = "Meta Size", value = "${settings.metaSize.toInt()} sp") {
                SettingSlider(
                    value = settings.metaSize, min = 6f, max = 16f,
                    onValueChange = { onUpdate(settings.copy(metaSize = it)) },
                    onFinished    = onApply
                )
            }
        }

        item { Spacer(Modifier.height(6.dp)); SectionLabel("LAYOUT") }

        item {
            SettingRow(label = "Padding", value = "${settings.padding.toInt()} dp") {
                SettingSlider(
                    value = settings.padding, min = 4f, max = 28f,
                    onValueChange = { onUpdate(settings.copy(padding = it)) },
                    onFinished    = onApply
                )
            }
        }
        item {
            SettingRow(label = "Corner Radius", value = "${settings.cornerRadius.toInt()} dp") {
                SettingSlider(
                    value = settings.cornerRadius, min = 0f, max = 28f,
                    onValueChange = { onUpdate(settings.copy(cornerRadius = it)) },
                    onFinished    = onApply
                )
            }
        }
        item {
            SettingRow(label = "Opacity", value = "${(settings.opacity / 255f * 100).toInt()} %") {
                SettingSlider(
                    value = settings.opacity.toFloat(), min = 60f, max = 255f,
                    onValueChange = { onUpdate(settings.copy(opacity = it.toInt())) },
                    onFinished    = onApply
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
            ) { onUpdate(settings.copy(showMemory = it)); onApply() }
        }
        item {
            SettingToggle(
                label    = "Column Headers",
                subtitle = "Show SERVICE / CTRL labels",
                value    = settings.showColumnHeaders
            ) { onUpdate(settings.copy(showColumnHeaders = it)); onApply() }
        }

        // ── Font ────────────────────────────────────────────
        item { Spacer(Modifier.height(6.dp)); SectionLabel("FONT") }
        item {
            FontPicker(settings.fontStyle) { onUpdate(settings.copy(fontStyle = it)); onApply() }
        }

        // ── Theme ──────────────────────────────────────────
        item { Spacer(Modifier.height(6.dp)); SectionLabel("THEME") }
        item {
            ThemePicker(settings.theme) { onUpdate(settings.copy(theme = it)); onApply() }
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
    onFinished: (() -> Unit)? = null
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onFinished,
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
    pendingServices: MutableMap<String, Boolean>,
    manager: ServiceManager,
    onRefresh: () -> Unit
) {
    val panels  = services.filter { it.checkMode != StatusCheckMode.ACTION }
    val actions = services.filter { it.checkMode == StatusCheckMode.ACTION }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (panels.isNotEmpty()) {
            item { SectionLabel("PROCESSES") }
            items(panels) { service ->
                ServiceRow(
                    service    = service,
                    runtime    = runtimes[service.id] ?: ServiceRuntime.UNKNOWN,
                    isPending  = pendingServices[service.id] == true,
                    manager    = manager,
                    onPending  = { id, v -> pendingServices[id] = v },
                    onRefresh  = onRefresh
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
fun ServiceRow(
    service: ServiceItem,
    runtime: ServiceRuntime,
    isPending: Boolean,
    manager: ServiceManager,
    onPending: (String, Boolean) -> Unit,
    onRefresh: () -> Unit
) {
    val scope      = rememberCoroutineScope()
    val isRunning  = runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.DEGRADED
    val isUnknown  = runtime.status == RunStatus.UNKNOWN
    
    // Use current theme colors
    val settings   = remember { manager.getWidgetSettings() }
    val theme      = Themes.find(settings.theme)
    val accent     = Color(theme.accent)
    val accentBg   = Color(theme.accentBg)

    val modeStr = when {
        service.checkMode == StatusCheckMode.PORT && service.port != null -> ":${service.port}"
        service.checkMode == StatusCheckMode.PROCESS -> "proc"
        else -> "fork"
    }

    // Visual state derived from pending / runtime
    val nameColor  = if (isPending) MUTED else TEXT
    val statusText = if (isPending) "···" else statusLabel(runtime)
    val statusColor = if (isPending) DIM else if (isRunning) accent else RED
    
    val btnBg = when {
        isPending  -> Color(0xFF151520)
        isRunning  -> accentBg
        else       -> Color(0xFF1E1010)
    }
    val btnFg = when {
        isPending  -> DIM
        isRunning  -> accent
        isUnknown  -> MUTED
        else       -> RED
    }
    val btnLabel = when {
        isPending  -> "···"
        isRunning  -> "STOP"
        else       -> "START"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    statusColor,
                    RoundedCornerShape(3.dp)
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
                text = if (isPending) statusText else "$modeStr  ·  $statusText",
                color = MUTED, fontSize = 11.sp
            )
        }

        // Power button — disabled while pending
        Box(
            modifier = Modifier
                .background(btnBg, RoundedCornerShape(7.dp))
                .border(1.dp, btnFg.copy(alpha = 0.25f), RoundedCornerShape(7.dp))
                .then(
                    if (!isPending) Modifier.clickable {
                        scope.launch {
                            Log.d(TAG, "[ServiceCtrl] ServiceRow TAP: id=${service.id} action=${if (isRunning) "STOP" else "START"}")
                            manager.togglePower(service.id, isRunning)
                            
                            // Visual feedback loop: Wait for Termux + verify
                            delay(1500)
                            refreshAll()
                            pushWidget()
                        }
                    } else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                text = btnLabel, color = btnFg,
                fontSize = 10.sp,
                fontWeight = if (isPending) FontWeight.Normal else FontWeight.Bold,
                letterSpacing = if (isPending) 0.sp else 0.8.sp
            )
        }
    }
    HRule()
}

@Composable
fun ActionRow(action: ServiceItem, manager: ServiceManager, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚡", fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
        Text(action.label, color = TEXT, fontWeight = FontWeight.Medium,
             fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("RUN", color = Color(0xFF0088CC),
             fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
    HRule()
}
