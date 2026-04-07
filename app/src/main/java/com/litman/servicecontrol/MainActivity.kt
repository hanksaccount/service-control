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
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Alla", "Kör", "Stoppade")

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

    LaunchedEffect(services) {
        while(true) {
            val newStatuses = services.associate { service ->
                service.id to serviceManager.checkStatusWithLoad(service)
            }
            statuses = newStatuses
            delay(10000)
        }
    }

    DisposableEffect(Unit) {
        val scanReceiver = TermuxResultReceiver { stdout, stderr, exitCode ->
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

        val scanFilter  = IntentFilter(ACTION_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scanReceiver,  scanFilter,  android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scanReceiver,  scanFilter)
        }
        onDispose { context.unregisterReceiver(scanReceiver) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Service Control", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val pi = makePendingIntent(context, ACTION_SCAN, 2)
                        serviceManager.triggerDiscoveryScan(pi)
                        statusMessage = "Scanning..."
                    }) { Text("🔄", fontSize = 20.sp) }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            val panels = services.filter { it.group == ServiceGroup.PANELS || it.group == ServiceGroup.UNKNOWN }
            val actions = services.filter { it.group == ServiceGroup.ACTIONS }

            val filteredPanels = when (selectedTab) {
                1 -> panels.filter { statuses[it.id]?.status == RunStatus.RUNNING || statuses[it.id]?.status == RunStatus.ACTIVE }
                2 -> panels.filter { statuses[it.id]?.status == RunStatus.STOPPED }
                else -> panels
            }

            if (filteredPanels.isNotEmpty()) {
                item {
                    Text("PANELER", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(filteredPanels) { service ->
                    ServiceCard(
                        service = service,
                        runtime = statuses[service.id] ?: ServiceRuntime.UNKNOWN,
                        onEdit = { editingService = service },
                        onClick = { detailService = service }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            if (actions.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("ACTIONS", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(actions) { action ->
                    ActionCard(action = action, onClick = { detailService = action })
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
                val newList = services.map { if (it.id == updated.id) updated else it }
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
                    Text(statusLabel(runtime), color = Color.Gray, fontSize = 12.sp)
                    if (service.port != null) {
                        Text(" · Port ${service.port}", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
            IconButton(onClick = onEdit) { Text("⚙️", fontSize = 18.sp) }
        }
    }
}

@Composable
fun ActionCard(action: ServiceItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E2E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(action.icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Text(action.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text("Kör", color = Color(0xFF00FF88), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigDialog(service: ServiceItem, onDismiss: () -> Unit, onSave: (ServiceItem) -> Unit) {
    var displayName by remember { mutableStateOf(service.displayName) }
    var port by remember { mutableStateOf(service.port?.toString() ?: "") }
    var enabled by remember { mutableStateOf(service.isEnabledOnWidget) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konfigurera ${service.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Visningsnamn") })
                TextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Visa på widget")
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(service.copy(displayName = displayName, port = port.toIntOrNull(), isEnabledOnWidget = enabled))
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
                    Text(service.type.name, fontSize = 11.sp, color = Color.Gray)
                }
            }

            Divider(color = Color.DarkGray)

            if (service.type != ServiceType.ACTION_SCRIPT) {
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
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (service.canStart) {
                    Button(
                        onClick = { serviceManager.runTermuxScript(service.scriptPath) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF88), contentColor = Color.Black)
                    ) { Text(if (service.type == ServiceType.ACTION_SCRIPT) "Kör nu" else "Starta") }
                }

                if (service.canStop && service.port != null) {
                    Button(
                        onClick = { serviceManager.stopService(service.port!!) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444))
                    ) { Text("Stoppa") }
                }
            }

            if (service.canOpen) {
                val url = service.openUrl ?: if (service.port != null) "http://127.0.0.1:${service.port}" else null
                if (url != null) {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Öppna Panel") }
                }
            }
        }
    }
}

@Composable
fun ServiceControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(typography = Typography(), content = content)
}
