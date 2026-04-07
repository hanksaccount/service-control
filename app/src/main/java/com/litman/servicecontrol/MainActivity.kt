package com.litman.servicecontrol

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.litman.servicecontrol.model.ServiceItem
import com.litman.servicecontrol.model.ServiceManager
import kotlinx.coroutines.launch

private const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"

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
    val context = LocalContext.current
    var services by remember { mutableStateOf(serviceManager.getSavedServices()) }
    val scope = rememberCoroutineScope()
    var editingService by remember { mutableStateOf<ServiceItem?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var probeContent by remember { mutableStateOf<String?>(null) }
    var lastCommand by remember { mutableStateOf<String?>(null) }

    // Permission state — räknas om vid rekomposition
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            statusMessage = "Android beviljade inte permission via dialog. Prova ADB:\nadb shell pm grant ${context.packageName} $TERMUX_PERMISSION"
        }
    }

    // Package-info
    val pkgInfo = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
    }

    LazyColumn(modifier = Modifier.padding(16.dp)) {

        // ── Debug-panel ─────────────────────────────────────────
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("DEBUG", fontSize = 10.sp, color = Color(0xFF666666), fontWeight = FontWeight.Bold)

                    // Package info
                    Text(
                        "pkg: ${context.packageName}  v${pkgInfo?.versionName} (${pkgInfo?.versionCode})",
                        fontSize = 10.sp, color = Color(0xFF888888)
                    )

                    // Permission-status
                    val permColor = if (permissionGranted) Color(0xFF00FF88) else Color(0xFFFF4444)
                    val permText = if (permissionGranted) "RUN_COMMAND: GRANTED" else "RUN_COMMAND: NOT GRANTED"
                    Text(permText, fontSize = 11.sp, color = permColor, fontWeight = FontWeight.Bold)

                    if (!permissionGranted) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { permissionLauncher.launch(TERMUX_PERMISSION) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444))
                        ) {
                            Text("Begär permission", fontSize = 12.sp)
                        }
                        Text(
                            "Om ingen dialog visas, kör i Termux:\nadb shell pm grant ${context.packageName} $TERMUX_PERMISSION",
                            fontSize = 10.sp,
                            color = Color(0xFFFF9800)
                        )
                    }

                    if (lastCommand != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("CMD: $lastCommand", fontSize = 9.sp, color = Color(0xFF555555))
                    }
                    if (statusMessage != null) {
                        Text(statusMessage!!, fontSize = 11.sp, color = Color(0xFFFF9800))
                    }
                    if (probeContent != null) {
                        Text("Probe:\n$probeContent", fontSize = 10.sp, color = Color(0xFF00E5FF))
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Knappar ──────────────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Service Control", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        // Uppdatera permission-state direkt vid klick
                        permissionGranted = ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
                        if (!permissionGranted) {
                            statusMessage = "Permission saknas — se debug-panelen ovan"
                            return@Button
                        }
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
                    }
                ) { Text("Scan") }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        permissionGranted = ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
                        if (!permissionGranted) {
                            probeContent = "Permission saknas"
                            return@Button
                        }
                        val result = serviceManager.runProbe()
                        lastCommand = result.command
                        probeContent = "Väntar..."
                        if (result.error != null) {
                            probeContent = "FEL: ${result.error}"
                        } else {
                            scope.launch {
                                kotlinx.coroutines.delay(3000)
                                probeContent = serviceManager.probeFileContent()
                                    ?: "Filen skapades INTE i Downloads"
                            }
                        }
                    }
                ) { Text("Probe") }

                Spacer(modifier = Modifier.width(8.dp))

                Button(onClick = {
                    editingService = ServiceItem(
                        id = System.currentTimeMillis().toString(),
                        name = "Ny tjänst",
                        scriptPath = ""
                    )
                }) { Text("+") }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Tjänster ─────────────────────────────────────────────
        if (services.isEmpty()) {
            item {
                Text(
                    "Inga tjänster tillagda. Tryck Scan eller + för att lägga till.",
                    color = Color.Gray, fontSize = 14.sp
                )
            }
        } else {
            items(services) { service ->
                ServiceCard(service, onEdit = { editingService = service })
                Spacer(modifier = Modifier.height(12.dp))
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
                    text = if (service.port != null) "Port: ${service.port}" else "Ej konfigurerad",
                    color = if (service.port != null) Color.Gray else Color(0xFFFF9800),
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = service.isEnabledOnWidget,
                onCheckedChange = null,
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
        title = { Text("Konfigurera ${service.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                TextField(value = icon, onValueChange = { icon = it }, label = { Text("Ikon (Emoji)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Visa på widget")
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(service.copy(port = port.toIntOrNull(), icon = icon, isEnabledOnWidget = enabled))
            }) { Text("Spara") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Avbryt") }
        }
    )
}

@Composable
fun ServiceControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = Typography(), content = content)
}
