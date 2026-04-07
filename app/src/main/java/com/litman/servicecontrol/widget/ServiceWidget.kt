package com.litman.servicecontrol.widget

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.litman.servicecontrol.model.RunStatus
import com.litman.servicecontrol.model.ServiceItem
import com.litman.servicecontrol.model.ServiceManager
import com.litman.servicecontrol.model.ServiceRuntime
import com.litman.servicecontrol.model.statusDotColor
import com.litman.servicecontrol.model.statusLabel
import com.litman.servicecontrol.model.systemLoad
import com.litman.servicecontrol.model.systemLoadColor

class ServiceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val manager = ServiceManager(context)
        val services = manager.getSavedServices().filter { it.isEnabledOnWidget }

        val runtimes: Map<String, ServiceRuntime> = services.associate { s ->
            s.id to (s.port?.let { manager.checkStatusWithLoad(it) } ?: ServiceRuntime.NO_PORT)
        }

        val activeCount = runtimes.values.count { it.status == RunStatus.RUNNING }
        val sysLoad    = systemLoad(runtimes.values)
        val sysColor   = systemLoadColor(sysLoad)

        provideContent {
            WidgetRoot(context, services, runtimes, activeCount, sysLoad, sysColor)
        }
    }
}

@Composable
private fun WidgetRoot(
    context: Context,
    services: List<ServiceItem>,
    runtimes: Map<String, ServiceRuntime>,
    activeCount: Int,
    sysLoad: String,
    sysColor: Int
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(10.dp)
    ) {
        // ── Header ──────────────────────────────────────────────
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "SERVICE CONTROL",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF00E5FF)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(GlanceModifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = Color(sysColor), size = 6.dp)
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = "System: $sysLoad  ·  $activeCount/${services.size} aktiva",
                        style = TextStyle(
                            color = ColorProvider(Color(sysColor)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            // Refresh-knapp
            Text(
                text = "↺",
                modifier = GlanceModifier
                    .clickable(actionRunCallback<RefreshAction>())
                    .padding(4.dp),
                style = TextStyle(color = ColorProvider(Color(0xFF444444)), fontSize = 16.sp)
            )
        }

        Spacer(GlanceModifier.height(6.dp))

        // Separator
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF1E1E1E))
        ) {}

        Spacer(GlanceModifier.height(6.dp))

        // ── Tjänster ────────────────────────────────────────────
        if (services.isEmpty()) {
            Text(
                text = "Inga tjänster markerade för widget",
                style = TextStyle(color = ColorProvider(Color(0xFF555555)), fontSize = 11.sp)
            )
        } else {
            services.forEach { service ->
                val runtime = runtimes[service.id] ?: ServiceRuntime.UNKNOWN
                ServiceRow(service, runtime)
                Spacer(GlanceModifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ServiceRow(service: ServiceItem, runtime: ServiceRuntime) {
    val dotColor  = Color(statusDotColor(runtime))
    val label     = statusLabel(runtime)
    val isRunning = runtime.status == RunStatus.RUNNING

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(Color(0xFF141414))
            .cornerRadius(8.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status-prick
        StatusDot(color = dotColor, size = 8.dp)
        Spacer(GlanceModifier.width(6.dp))

        // Namn + status-text
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = service.name,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = if (service.port != null) "$label · :${service.port}" else label,
                style = TextStyle(color = ColorProvider(dotColor), fontSize = 10.sp)
            )
        }

        // START / STOPP — bara om port är konfigurerad
        if (service.port != null) {
            Spacer(GlanceModifier.width(6.dp))
            val btnText  = if (isRunning) "STOPP" else "STARTA"
            val btnColor = if (isRunning) Color(0xFF3A1A1A) else Color(0xFF1A3A1A)
            val btnTxtColor = if (isRunning) Color(0xFFFF4444) else Color(0xFF00FF88)

            Box(
                modifier = GlanceModifier
                    .background(btnColor)
                    .cornerRadius(6.dp)
                    .clickable(
                        actionRunCallback<ToggleAction>(
                            actionParametersOf(
                                ActionParameters.Key<String>("service_id")   to service.id,
                                ActionParameters.Key<Boolean>("is_active")   to isRunning,
                                ActionParameters.Key<String>("script_path")  to service.scriptPath,
                                ActionParameters.Key<Int>("port")            to service.port
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = btnText,
                    style = TextStyle(color = ColorProvider(btnTxtColor), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = GlanceModifier
            .width(size)
            .height(size)
            .background(color)
            .cornerRadius(size / 2)
    ) {}
}

// ── Actions ──────────────────────────────────────────────────────────────────

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceWidget().update(context, glanceId)
    }
}

class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val isActive   = parameters[ActionParameters.Key<Boolean>("is_active")]  ?: false
        val scriptPath = parameters[ActionParameters.Key<String>("script_path")] ?: ""
        val port       = parameters[ActionParameters.Key<Int>("port")]           ?: 0
        val manager    = ServiceManager(context)

        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))

        if (isActive) manager.stopService(port)
        else          manager.runTermuxScript(scriptPath)

        kotlinx.coroutines.delay(1200)
        ServiceWidget().update(context, glanceId)
    }
}
