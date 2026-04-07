package com.litman.servicecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import com.litman.servicecontrol.model.ServiceItem
import com.litman.servicecontrol.model.ServiceManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var serviceManager: ServiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serviceManager = ServiceManager(this)

        setContent {
            ServiceControlTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    MainScreen(serviceManager)
                }
            }
        }
    }
}

@Composable
fun MainScreen(serviceManager: ServiceManager) {
    var services by remember { mutableStateOf(serviceManager.getSavedServices()) }
    val scope = rememberCoroutineScope()
    var editingService by remember { mutableStateOf<ServiceItem?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var probeContent by remember { mutableStateOf<String?>(null) }
    var lastCommand by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Service Control", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = {
                val result = serviceManager.triggerDiscoveryScan()
                lastCommand = result.command
                if (result.error != null) {
                    statusMessage = "Scan fel: ${result.error}"
                } else {
                    statusMessage = "Scan skickad..."
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        services = serviceManager.syncDiscoveredScripts()
                        statusMessage = "Hittade ${services.size} tjänster"
                    }
                }
            }) {
                Text("Scan")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                val result = serviceManager.runProbe()
                lastCommand = result.command
                probeContent = null
                if (result.error != null) {
                    probeContent = "FEL: ${result.error}"
                } else {
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        val content = serviceManager.probeFileContent()
                        probeContent = content ?: "Filen skapades INTE i Downloads"
                    }
                }
            }) {
                Text("Probe")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                editingService = com.litman.servicecontrol.model.ServiceItem(
                    id = System.currentTimeMillis().toString(),
                    name = "Ny tjänst",
                    scriptPath = ""
                )
            }) {
                Text("+")
            }
        }

        if (lastCommand != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("CMD: $lastCommand", color = Color(0xFF666666), fontSize = 10.sp)
        }
        if (statusMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(statusMessage!!, color = Color(0xFFFF9800), fontSize = 12.sp)
        }
        if (probeContent != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Probe output:\n$probeContent", color = Color(0xFF00E5FF), fontSize = 11.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (services.isEmpty()) {
            Text(
                "Inga tjänster tillagda. Tryck Scan eller + för att lägga till.",
                color = Color.Gray,
                fontSize = 14.sp
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(services) { service ->
                    ServiceCard(service, onEdit = { editingService = service })
                }
            }
        }
    }

    if (editingService != null) {
        ConfigDialog(
            service = editingService!!,
            onDismiss = { editingService = null },
            onSave = { updated ->
                val newList = if (services.any { it.id == updated.id })
                    services.map { if (it.id == updated.id) updated else it }
                else
                    services + updated
                serviceManager.saveServices(newList)
                services = newList
                editingService = null
            }
        )
    }
}

@Composable
fun ServiceCard(service: ServiceItem, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(service.icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(service.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    text = if (service.port != null) "Port: ${service.port}" else "Not configured",
                    color = if (service.port != null) Color.Gray else Color(0xFFFF9800),
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = service.isEnabledOnWidget,
                onCheckedChange = null, // Vi hanterar via edit för att vara säkra
                enabled = service.port != null
            )
            IconButton(onClick = onEdit) {
                Text("⚙️")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigDialog(service: ServiceItem, onDismiss: () -> Unit, onSave: (ServiceItem) -> Unit) {
    var port by remember { mutableStateOf(service.port?.toString() ?: "") }
    var icon by remember { mutableStateOf(service.icon) }
    var enabled by remember { mutableStateOf(service.isEnabledOnWidget) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configure ${service.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                TextField(value = icon, onValueChange = { icon = it }, label = { Text("Icon (Emoji)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show on Widget")
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(service.copy(port = port.toIntOrNull(), icon = icon, isEnabledOnWidget = enabled))
            }) { Text("Save") }
        }
    )
}

@Composable
fun ServiceControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = Typography(), content = content)
}
