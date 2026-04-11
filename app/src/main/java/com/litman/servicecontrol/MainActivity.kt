package com.litman.servicecontrol

import android.os.Bundle
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
    var services     by remember { mutableStateOf(manager.getSavedServices()) }
    val runtimes      = remember { mutableStateMapOf<String, ServiceRuntime>() }
    val scope         = rememberCoroutineScope()
    var settings     by remember { mutableStateOf(manager.getWidgetSettings()) }
    var showSettings by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            services = manager.getSavedServices()
            services.forEach { runtimes[it.id] = manager.checkStatus(it) }
        }
    }

    fun pushWidget() {
        scope.launch { ServiceWidget().updateAll(manager.context) }
    }

    fun updateSettings(new: WidgetSettings) {
        settings = new
        manager.saveWidgetSettings(new)
        pushWidget()
    }

    LaunchedEffect(Unit) {
        while (true) { refreshAll(); delay(10_000) }
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
            SettingsPane(settings, onUpdate = ::updateSettings)
        } else {
            ServiceListPane(services, runtimes, manager) { refreshAll(); pushWidget() }
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
fun SettingsPane(settings: WidgetSettings, onUpdate: (WidgetSettings) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Typography ──────────────────────────────────────
        item { SectionLabel("TYPOGRAPHY") }

        item {
            SettingRow(label = "Name Size", value = "${settings.nameSize.toInt()} sp") {
                SettingSlider(settings.nameSize, 8f, 22f) {
                    onUpdate(settings.copy(nameSize = it))
                }
            }
        }
        item {
            SettingRow(label = "Meta Size", value = "${settings.metaSize.toInt()} sp") {
                SettingSlider(settings.metaSize, 6f, 16f) {
                    onUpdate(settings.copy(metaSize = it))
                }
            }
        }

        item { Spacer(Modifier.height(6.dp)); SectionLabel("LAYOUT") }

        item {
            SettingRow(label = "Padding", value = "${settings.padding.toInt()} dp") {
                SettingSlider(settings.padding, 4f, 28f) {
                    onUpdate(settings.copy(padding = it))
                }
            }
        }
        item {
            SettingRow(label = "Corner Radius", value = "${settings.cornerRadius.toInt()} dp") {
                SettingSlider(settings.cornerRadius, 0f, 28f) {
                    onUpdate(settings.copy(cornerRadius = it))
                }
            }
        }
        item {
            SettingRow(label = "Opacity", value = "${(settings.opacity / 255f * 100).toInt()} %") {
                SettingSlider(settings.opacity.toFloat(), 60f, 255f) {
                    onUpdate(settings.copy(opacity = it.toInt()))
                }
            }
        }

        // ── Toggles ──────────────────────────────────────────
        item { Spacer(Modifier.height(6.dp)); SectionLabel("DISPLAY") }

        item {
            SettingToggle(
                label    = "Memory",
                subtitle = "Show memory usage per service",
                value    = settings.showMemory
            ) { onUpdate(settings.copy(showMemory = it)) }
        }
        item {
            SettingToggle(
                label    = "Column Headers",
                subtitle = "Show SERVICE / CTRL labels",
                value    = settings.showColumnHeaders
            ) { onUpdate(settings.copy(showColumnHeaders = it)) }
        }

        // ── Font ────────────────────────────────────────────
        item { Spacer(Modifier.height(6.dp)); SectionLabel("FONT") }
        item {
            FontPicker(settings.fontStyle) { onUpdate(settings.copy(fontStyle = it)) }
        }

        // ── Accent ──────────────────────────────────────────
        item { Spacer(Modifier.height(6.dp)); SectionLabel("ACCENT COLOR") }
        item {
            AccentPicker(settings.accentColor) { onUpdate(settings.copy(accentColor = it)) }
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

@Composable
fun SettingSlider(value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Slider(
        value = value,
        onValueChange = onValueChange,
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
    val accent = if (isSelected) GREEN else Color.Transparent
    val bg     = if (isSelected) Color(0xFF0D2015) else SURF

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

// ── Accent color picker ───────────────────────────────────────────────────────

@Composable
fun AccentPicker(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AccentCard("GREEN", GREEN,  "Terminal",  current, onSelect, Modifier.weight(1f))
        AccentCard("CYAN",  CYAN,   "Tech",      current, onSelect, Modifier.weight(1f))
        AccentCard("AMBER", AMBER,  "Industrial",current, onSelect, Modifier.weight(1f))
    }
}

@Composable
fun AccentCard(
    id: String, color: Color, label: String,
    current: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelected = current == id
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) accentBgOf(id) else SURF)
            .border(1.dp, if (isSelected) color else LINE, RoundedCornerShape(10.dp))
            .clickable { onSelect(id) }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(18.dp)
                .background(color.copy(alpha = if (isSelected) 1f else 0.4f), RoundedCornerShape(9.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 10.sp, color = if (isSelected) TEXT else MUTED,
             fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── Service list ──────────────────────────────────────────────────────────────

@Composable
fun ServiceListPane(
    services: List<ServiceItem>,
    runtimes: Map<String, ServiceRuntime>,
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
                    service  = service,
                    runtime  = runtimes[service.id] ?: ServiceRuntime.UNKNOWN,
                    manager  = manager,
                    onRefresh = onRefresh
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
    manager: ServiceManager,
    onRefresh: () -> Unit
) {
    val isRunning   = runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.ACTIVE
    val isUnknown   = runtime.status == RunStatus.UNKNOWN
    val dotColor    = Color(statusDotColor(runtime))
    val statusText  = statusLabel(runtime)

    val modeStr = when {
        service.checkMode == StatusCheckMode.PORT && service.port != null -> ":${service.port}"
        service.checkMode == StatusCheckMode.PROCESS -> "proc"
        else -> "fork"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(dotColor, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(12.dp))

        // Name + meta
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = service.label, color = TEXT,
                fontWeight = FontWeight.Medium, fontSize = 14.sp
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = "$modeStr  ·  $statusText",
                color = MUTED, fontSize = 11.sp
            )
        }

        // Power button
        val btnBg  = if (isRunning) Color(0xFF162312) else Color(0xFF1E1010)
        val btnFg  = if (isRunning) GREEN else if (isUnknown) MUTED else RED
        val btnLbl = if (isRunning) "STOP" else "START"

        Box(
            modifier = Modifier
                .background(btnBg, RoundedCornerShape(7.dp))
                .border(1.dp, btnFg.copy(alpha = 0.30f), RoundedCornerShape(7.dp))
                .clickable { manager.togglePower(service.id, isRunning); onRefresh() }
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                text = btnLbl, color = btnFg,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp
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
            .clickable { manager.runTermuxScript(action.scriptPath); onRefresh() }
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
