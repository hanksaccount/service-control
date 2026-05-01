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

import android.content.Intent
import com.litman.servicecontrol.SafeStreamActivity

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
            val updated = manager.checkStatuses(services)
            updated.forEach { (id, runtime) ->
                runtimes[id] = runtime
            }
        }
    }

    LaunchedEffect(Unit) {
        while(true) {
            refreshAll()
            val hasPending = runtimes.values.any { isServicePending(it) }
            delay(if (hasPending) 2000 else 5000)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("SERVICE CONTROL", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
        ImpactHeader(
            services = services,
            runtimes = runtimes,
            backend = manager.backendLabel()
        )
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
fun ImpactHeader(
    services: List<ServiceItem>,
    runtimes: Map<String, ServiceRuntime>,
    backend: String
) {
    val panels = services.filter { it.checkMode != StatusCheckMode.ACTION }
    val active = panels.count { service -> runtimes[service.id]?.let { isServiceActive(it) } == true }
    val pending = panels.count { service -> runtimes[service.id]?.let { isServicePending(it) } == true }
    val failed = panels.count { service -> runtimes[service.id]?.status == RunStatus.FAILED }
    val high = panels.count { service ->
        val signal = runtimes[service.id]?.impact?.signal
        signal == ImpactSignal.HIGH || signal == ImpactSignal.ERROR
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ImpactPill("Aktiva", "$active/${panels.size}", Color(0xFF00FF88), Modifier.weight(1f))
        ImpactPill("Väntar", pending.toString(), Color(0xFFFFC857), Modifier.weight(1f))
        ImpactPill("Risk", (failed + high).toString(), Color(0xFFFF4444), Modifier.weight(1f))
    }
    Text(
        text = backend,
        color = Color(0xFF555555),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
fun ImpactPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFF111111),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, color = Color(0xFF777777), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Black)
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
    val scope = rememberCoroutineScope()
    val isRunning = isServiceActive(runtime)
    val isPending = isServicePending(runtime)
    
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
                Text(
                    "${impactLabel(runtime.impact)} · ${runtime.impact.detail.ifBlank { service.checkMode.name.lowercase() }}",
                    color = Color(0xFF555555),
                    fontSize = 11.sp
                )
            }
            
            IconButton(onClick = { manager.toggleMute(service.id); onRefresh() }) {
                Text(if (service.isMuted) "🔕" else "🔔", fontSize = 18.sp)
            }

            Button(
                onClick = {
                    scope.launch {
                        manager.togglePower(service.id)
                        onRefresh()
                    }
                },
                enabled = !isPending,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isPending -> Color(0xFFFFC857)
                        isRunning -> Color(0xFFFF4444)
                        else -> Color(0xFF00FF88)
                    }
                )
            ) {
                Text(actionLabel(runtime), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ActionRow(action: ServiceItem, manager: ServiceManager, onRefresh: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Row(
            modifier = Modifier.padding(12.dp).clickable {
                if (action.type == ServiceType.SAFE_STREAM) {
                    val intent = Intent(context, SafeStreamActivity::class.java).apply {
                        putExtra("url", action.openUrl)
                    }
                    context.startActivity(intent)
                } else {
                    scope.launch {
                        manager.runAction(action)
                        onRefresh()
                    }
                }
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (action.type == ServiceType.SAFE_STREAM) "🛡️" else "⚡", fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Text(action.label, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val btnText = if (action.type == ServiceType.SAFE_STREAM) "ÖPPNA" else "KÖR NU"
            Text(btnText, color = Color(0xFF00AAFF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
