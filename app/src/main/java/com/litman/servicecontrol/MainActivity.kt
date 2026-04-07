package com.litman.servicecontrol

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
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
private const val ACTION_PROBE  = "com.litman.servicecontrol.PROBE_RESULT"
private const val ACTION_SCAN   = "com.litman.servicecontrol.SCAN_RESULT"

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

fun makePendingIntent(context: android.content.Context, action: String, requestCode: Int): PendingIntent {
    val intent = Intent(action).apply { setPackage(context.packageName) }
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    else
        PendingIntent.FLAG_UPDATE_CURRENT
    return PendingIntent.getBroadcast(context, requestCode, intent, flags)
}

@Composable
fun MainScreen(serviceManager: ServiceManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var services by remember { mutableStateOf(serviceManager.getSavedServices()) }
    var editingService by remember { mutableStateOf<ServiceItem?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var probeContent by remember { mutableStateOf<String?>(null) }
    var lastCommand by remember { mutableStateOf<String?>(null) }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) statusMessage = "Android beviljade inte permission.\nKör i Termux:\npm grant ${context.packageName} $TERMUX_PERMISSION"
    }

    val pkgInfo = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
    }

    // Registrera BroadcastReceivers för probe- och scan-resultat
    DisposableEffect(Unit) {
        val probeReceiver = TermuxResultReceiver { stdout, stderr, exitCode ->
            serviceManager.debugLog("probe result: exit=$exitCode stdout=[$stdout] stderr=[$stderr]")
            probeContent = if (stdout.isNotBlank()) stdout
                           else if (stderr.isNotBlank()) "stderr: $stderr"
                           else "(tom output, exit=$exitCode)"
        }
        val scanReceiver = TermuxResultReceiver { stdout, stderr, exitCode ->
            serviceManager.debugLog("scan result: exit=$exitCode stdout=[$stdout] stderr=[$stderr]")
            if (stdout.isBlank() && stderr.isNotBlank()) {
                statusMessage = "Scan stderr: $stderr"
            } else {
                try {
                    val parsed = serviceManager.parseScanResult(stdout)
                    services = parsed
                    statusMessage = "Hittade ${parsed.size} tjänster\nstdout: $stdout"
                } catch (e: Exception) {
                    statusMessage = "Parse krasch: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }

        val probeFilter = IntentFilter(ACTION_PROBE)
        val scanFilter  = IntentFilter(ACTION_SCAN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(probeReceiver, probeFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(scanReceiver,  scanFilter,  android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(probeReceiver, probeFilter)
            context.registerReceiver(scanReceiver,  scanFilter)
        }

        onDispose {
            context.unregisterReceiver(probeReceiver)
            context.unregisterReceiver(scanReceiver)
        }
    }

    LazyColumn(modifier = Modifier.padding(16.dp)) {

        // ── Debug-panel ──────────────────────────────────────────
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("DEBUG", fontSize = 10.sp, color = Color(0xFF666666), fontWeight = FontWeight.Bold)
                    Text(
                        "pkg: ${context.packageName}  v${pkgInfo?.versionName} (${pkgInfo?.versionCode})",
                        fontSize = 10.sp, color = Color(0xFF888888)
                    )
                    val permColor = if (permissionGranted) Color(0xFF00FF88) else Color(0xFFFF4444)
                    Text(
                        if (permissionGranted) "RUN_COMMAND: GRANTED" else "RUN_COMMAND: NOT GRANTED",
                        fontSize = 11.sp, color = permColor, fontWeight = FontWeight.Bold
                    )
                    if (!permissionGranted) {
                        Button(
                            onClick = { permissionLauncher.launch(TERMUX_PERMISSION) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444))
                        ) { Text("Begär permission", fontSize = 12.sp) }
                        Text(
                            "Om ingen dialog: kör i Termux:\npm grant ${context.packageName} $TERMUX_PERMISSION",
                            fontSize = 10.sp, color = Color(0xFFFF9800)
                        )
                    }
                    if (lastCommand != null)
                        Text("CMD: $lastCommand", fontSize = 9.sp, color = Color(0xFF555555))
                    if (statusMessage != null)
                        Text(statusMessage!!, fontSize = 11.sp, color = Color(0xFFFF9800))
                    if (probeContent != null)
                        Text("Probe:\n$probeContent", fontSize = 10.sp, color = Color(0xFF00E5FF))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Knappar ──────────────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Service Control", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.weight(1f))

                Button(onClick = {
                    permissionGranted = ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
                    if (!permissionGranted) { statusMessage = "Permission saknas"; return@Button }
                    serviceManager.clearDebugLog()
                    val pi = makePendingIntent(context, ACTION_SCAN, 2)
                    val result = serviceManager.triggerDiscoveryScan(pi)
                    lastCommand = result.command
                    statusMessage = if (result.error != null) "FEL: ${result.error}" else "Scan skickad, väntar på svar..."
                }) { Text("Scan") }

                Spacer(modifier = Modifier.width(8.dp))

                Button(onClick = {
                    permissionGranted = ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED
                    if (!permissionGranted) { probeContent = "Permission saknas"; return@Button }
                    serviceManager.clearDebugLog()
                    val pi = makePendingIntent(context, ACTION_PROBE, 1)
                    val result = serviceManager.runProbe(pi)
                    lastCommand = result.command
                    probeContent = if (result.error != null) "FEL: ${result.error}" else "Skickad, väntar på svar..."
                }) { Text("Probe") }

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
            Switch(checked = service.isEnabledOnWidget, onCheckedChange = null, enabled = service.port != null)
            IconButton(onClick = onEdit) { Text("⚙️") }
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } }
    )
}

@Composable
fun ServiceControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = Typography(), content = content)
}
