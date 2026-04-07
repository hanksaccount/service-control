package com.litman.servicecontrol

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.litman.servicecontrol.model.*
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(serviceManager: ServiceManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var services by remember { mutableStateOf(serviceManager.getSavedServices()) }
    var statuses by remember { mutableStateOf(mapOf<String, ServiceRuntime>()) }
    var editingService by remember { mutableStateOf<ServiceItem?>(null) }
    var detailService by remember { mutableStateOf<ServiceItem?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var probeContent by remember { mutableStateOf<String?>(null) }
    var lastCommand by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Alla", "Kör", "Stoppade", "Ej konf.")

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

    // Status polling
    LaunchedEffect(services) {
        while(true) {
            val newStatuses = services.associate { service ->
                val runtime = if (service.port != null) {
                    serviceManager.checkStatusWithLoad(service.port!!)
                } else {
                    ServiceRuntime.NO_PORT
                }
                service.id to runtime
            }
            statuses = newStatuses
            delay(10000) // Poll var 10:e sekund
        }
    }

    // Registrera BroadcastReceivers
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
                    statusMessage = "Hittade ${parsed.size} tjänster"
                } catch (e: Exception) {
                    statusMessage = "Parse krasch: ${e.javaClass.simpleName}"
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

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Tabs ────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF121212),
            contentColor = Color.White,
            divider = {}
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 12.sp) }
                )
            }
        }

        LazyColumn(modifier = Modifier.padding(16.dp)) {
            // ── Debug-panel (endast om permission saknas eller fel finns) ─────
            if (!permissionGranted || statusMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("STATUS & DEBUG", fontSize = 10.sp, color = Color(0xFF666666), fontWeight = FontWeight.Bold)
                            if (!permissionGranted) {
                                Text("RUN_COMMAND: NOT GRANTED", fontSize = 11.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                                Button(onClick = { permissionLauncher.launch(TERMUX_PERMISSION) }) { Text("Begär permission") }
                            }
                            if (statusMessage != null)
                                Text(statusMessage!!, fontSize = 11.sp, color = Color(0xFFFF9800))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // ── Header ──────────────────────────────────────────────
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Service Control", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val pi = makePendingIntent(context, ACTION_SCAN, 2)
                        serviceManager.triggerDiscoveryScan(pi)
                        statusMessage = "Scanning..."
                    }) { Text("🔄", fontSize = 20.sp) }
                    IconButton(onClick = {
                        editingService = ServiceItem(id = System.currentTimeMillis().toString(), name = "Ny tjänst", scriptPath = "")
                    }) { Text("➕", fontSize = 20.sp) }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Tjänster ─────────────────────────────────────────────
            val filteredServices = when (selectedTab) {
                1 -> services.filter { statuses[it.id]?.status == RunStatus.RUNNING }
                2 -> services.filter { statuses[it.id]?.status == RunStatus.STOPPED }
                3 -> services.filter { it.port == null }
                else -> services
            }

            if (filteredServices.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Inga tjänster hittades.", color = Color.Gray)
                    }
                }
            } else {
                items(filteredServices) { service ->
                    ServiceCard(
                        service = service,
                        runtime = statuses[service.id] ?: ServiceRuntime.UNKNOWN,
                        onEdit = { editingService = service },
                        onClick = { detailService = service }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
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

    if (detailService != null) {
        DetailBottomSheet(
            service = detailService!!,
            runtime = statuses[detailService!!.id] ?: ServiceRuntime.UNKNOWN,
            onDismiss = { detailService = null },
            serviceManager = serviceManager
        )
    }
}

@Composable
fun ServiceCard(service: ServiceItem, runtime: ServiceRuntime, onEdit: () -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(service.icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(service.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).padding(1.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(statusDotColor(runtime)), modifier = Modifier.fillMaxSize()) {}
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        statusLabel(runtime),
                        color = if (runtime.status == RunStatus.RUNNING) Color(0xFF00FF88) else Color.Gray,
                        fontSize = 12.sp
                    )
                    if (service.port != null) {
                        Text(" · Port ${service.port}", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
            IconButton(onClick = onEdit) { Text("⚙️", fontSize = 18.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigDialog(service: ServiceItem, onDismiss: () -> Unit, onSave: (ServiceItem) -> Unit) {
    var name by remember { mutableStateOf(service.name) }
    var displayName by remember { mutableStateOf(service.displayName) }
    var port by remember { mutableStateOf(service.port?.toString() ?: "") }
    var icon by remember { mutableStateOf(service.icon) }
    var enabled by remember { mutableStateOf(service.isEnabledOnWidget) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konfigurera") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = name, onValueChange = { name = it }, label = { Text("Systemnamn") }, readOnly = true)
                TextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Visningsnamn (Alias)") })
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port (för statuskontroll)") })
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
                onSave(service.copy(name = name, displayName = displayName, port = port.toIntOrNull(), icon = icon, isEnabledOnWidget = enabled))
            }) { Text("Spara") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailBottomSheet(service: ServiceItem, runtime: ServiceRuntime, onDismiss: () -> Unit, serviceManager: ServiceManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E1E), contentColor = Color.White) {
        Column(modifier = Modifier.padding(24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(service.icon, fontSize = 40.sp)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(service.label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(service.scriptPath, fontSize = 12.sp, color = Color.Gray)
                }
            }

            Divider(color = Color.DarkGray)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("STATUS", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(statusLabel(runtime), color = Color(statusDotColor(runtime)), fontWeight = FontWeight.Bold)
                }
                if (service.port != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("PORT", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text(service.port.toString(), fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (runtime.responseMs != null) {
                Text("Respons: ${runtime.responseMs}ms", fontSize = 11.sp, color = Color.Gray)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { serviceManager.runTermuxScript(service.scriptPath) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88), contentColor = Color.Black)
                ) { Text("Start") }

                if (service.port != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                serviceManager.stopService(service.port!!)
                                delay(1000)
                                serviceManager.runTermuxScript(service.scriptPath)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E2E))
                    ) { Text("Restart") }

                    Button(
                        onClick = { serviceManager.stopService(service.port!!) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444))
                    ) { Text("Stopp") }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (service.port != null) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${service.port}"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Öppna") }
                }
                OutlinedButton(
                    onClick = { /* Logs placeholder */ },
                    modifier = Modifier.weight(1f)
                ) { Text("Loggar") }
            }
        }
    }
}

@Composable
fun ServiceControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = Typography(), content = content)
}
