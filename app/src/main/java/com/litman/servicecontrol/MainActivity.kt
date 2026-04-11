package com.litman.servicecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import com.litman.servicecontrol.model.*
import com.litman.servicecontrol.widget.ServiceWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var manager: ServiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ServiceManager(this)
        
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF050505))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF050505)) {
                    ServiceControlApp(manager)
                }
            }
        }
    }
}

@Composable
fun ServiceControlApp(manager: ServiceManager) {
    var services by remember { mutableStateOf(manager.getSavedServices()) }
    val runtimes = remember { mutableStateMapOf<String, ServiceRuntime>() }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(manager.getWidgetSettings()) }
    var showSettings by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            services = manager.getSavedServices()
            services.forEach { service ->
                runtimes[service.id] = manager.checkStatus(service)
            }
        }
    }

    fun updateWidget() {
        scope.launch {
            ServiceWidget().updateAll(manager.context)
        }
    }

    fun updateSettings(newSettings: WidgetSettings) {
        settings = newSettings
        manager.saveWidgetSettings(newSettings)
        updateWidget()
    }

    LaunchedEffect(Unit) {
        while(true) {
            refreshAll()
            delay(10000)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SERVICE CONTROL", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
            IconButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "✕" else "⚙", fontSize = 24.sp, color = Color(0xFF888888))
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (showSettings) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { SectionHeader("WIDGET SETTINGS") }
                
                item {
                    SettingSlider("Name Size (${settings.nameSize.toInt()})", settings.nameSize, 8f, 24f) { v -> 
                        updateSettings(settings.copy(nameSize = v)) 
                    }
                }
                item {
                    SettingSlider("Meta Size (${settings.metaSize.toInt()})", settings.metaSize, 6f, 18f) { v -> 
                        updateSettings(settings.copy(metaSize = v)) 
                    }
                }
                item {
                    SettingSlider("Padding (${settings.padding.toInt()})", settings.padding, 0f, 32f) { v -> 
                        updateSettings(settings.copy(padding = v)) 
                    }
                }
                item {
                    SettingSlider("Corner Radius (${settings.cornerRadius.toInt()})", settings.cornerRadius, 0f, 32f) { v -> 
                        updateSettings(settings.copy(cornerRadius = v)) 
                    }
                }
                item {
                    SettingSlider("Opacity (${settings.opacity})", settings.opacity.toFloat(), 0f, 255f) { v -> 
                        updateSettings(settings.copy(opacity = v.toInt())) 
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                val panels = services.filter { it.checkMode != StatusCheckMode.ACTION }
                val actions = services.filter { it.checkMode == StatusCheckMode.ACTION }

                if (panels.isNotEmpty()) {
                    item { SectionHeader("PANELER") }
                    items(panels) { service ->
                        ServiceRow(service, runtimes[service.id] ?: ServiceRuntime.UNKNOWN, manager) { 
                            refreshAll()
                            updateWidget()
                        }
                    }
                }

                if (actions.isNotEmpty()) {
                    item { Spacer(Modifier.height(24.dp)) }
                    item { SectionHeader("ACTIONS") }
                    items(actions) { action ->
                        ActionRow(action, manager) { refreshAll() }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingSlider(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00E676),
                activeTrackColor = Color(0xFF00E676),
                inactiveTrackColor = Color(0xFF333333)
            )
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF666666),
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun ServiceRow(service: ServiceItem, runtime: ServiceRuntime, manager: ServiceManager, onRefresh: () -> Unit) {
    val isRunning = runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.ACTIVE
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).background(Color(statusDotColor(runtime)), shape = MaterialTheme.shapes.small))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(service.label, color = Color.White, fontWeight = FontWeight.Bold)
                val statusText = if (service.checkMode == StatusCheckMode.PROCESS) "Process: ${statusLabel(runtime)}" else statusLabel(runtime)
                Text(statusText, color = Color(0xFF888888), fontSize = 12.sp)
            }
            
            IconButton(onClick = { manager.toggleMute(service.id); onRefresh() }) {
                Text(if (service.isMuted) "🔕" else "🔔", fontSize = 18.sp)
            }

            Button(
                onClick = { manager.togglePower(service.id, isRunning); onRefresh() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFFF4444) else Color(0xFF00FF88)
                )
            ) {
                Text(if (isRunning) "Stoppa" else "Starta", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ActionRow(action: ServiceItem, manager: ServiceManager, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Row(
            modifier = Modifier.padding(12.dp).clickable { manager.runTermuxScript(action.scriptPath); onRefresh() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚡", fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Text(action.label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("KÖR NU", color = Color(0xFF00AAFF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
