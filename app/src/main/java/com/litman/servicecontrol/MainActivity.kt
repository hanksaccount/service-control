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
import com.litman.servicecontrol.model.*
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

    fun refreshAll() {
        scope.launch {
            services = manager.getSavedServices()
            services.forEach { service ->
                runtimes[service.id] = manager.checkStatus(service)
            }
        }
    }

    LaunchedEffect(Unit) {
        while(true) {
            refreshAll()
            delay(10000)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("SERVICE CONTROL", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val panels = services.filter { it.checkMode != StatusCheckMode.ACTION }
            val actions = services.filter { it.checkMode == StatusCheckMode.ACTION }

            if (panels.isNotEmpty()) {
                item { SectionHeader("PANELER") }
                items(panels) { service ->
                    ServiceRow(service, runtimes[service.id] ?: ServiceRuntime.UNKNOWN, manager) { refreshAll() }
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
