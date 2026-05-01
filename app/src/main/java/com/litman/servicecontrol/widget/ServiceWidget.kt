package com.litman.servicecontrol.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
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
import com.litman.servicecontrol.model.*

class ServiceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val manager = ServiceManager(context)
        val allSaved = manager.getSavedServices()
        
        // Filter: only WEB_PANEL and HYBRID, and only widget-enabled
        val servicesToShow = allSaved.filter { 
            it.isEnabledOnWidget && (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID) 
        }

        val runtimes: Map<String, ServiceRuntime> = servicesToShow.associate { s ->
            s.id to manager.checkStatus(s)
        }

        val activeCount = runtimes.values.count { isServiceActive(it) }
        val pendingCount = runtimes.values.count { isServicePending(it) }

        provideContent {
            WidgetRoot(
                services = servicesToShow,
                runtimes = runtimes,
                activeCount = activeCount,
                pendingCount = pendingCount,
                totalCount = allSaved.count { it.group == ServiceGroup.PANELS }
            )
        }
    }
}

@Composable
private fun WidgetRoot(
    services: List<ServiceItem>,
    runtimes: Map<String, ServiceRuntime>,
    activeCount: Int,
    pendingCount: Int,
    totalCount: Int
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF050505)))
            .padding(8.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "SERVICE CONTROL",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF666666)),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = if (pendingCount > 0) "$activeCount/$totalCount AKTIVA  $pendingCount VÄNTAR" else "$activeCount/$totalCount AKTIVA",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            
            Text(
                text = "↺",
                modifier = GlanceModifier
                    .padding(4.dp)
                    .clickable(actionRunCallback<RefreshActionWidget>()),
                style = TextStyle(
                    color = ColorProvider(Color(0xFF444444)),
                    fontSize = 16.sp
                )
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        if (services.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Inga tjänster",
                    style = TextStyle(color = ColorProvider(Color(0xFF222222)), fontSize = 10.sp)
                )
            }
        } else {
            services.forEach { service ->
                val runtime = runtimes[service.id] ?: ServiceRuntime.UNKNOWN
                ServiceWidgetRow(service, runtime)
                Spacer(GlanceModifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun ServiceWidgetRow(service: ServiceItem, runtime: ServiceRuntime) {
    val dotColorInt = statusDotColor(runtime)
    val name = service.label
    val isRunning = isServiceActive(runtime)
    val isPending = isServicePending(runtime)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(5.dp)
                .background(ColorProvider(Color(dotColorInt)))
                .cornerRadius(2.5.dp)
        ) {}
        
        Spacer(GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = TextStyle(
                        color = ColorProvider(if (isRunning) Color.White else Color(0xFF888888)),
                        fontSize = 12.sp,
                        fontWeight = if (isRunning) FontWeight.Medium else FontWeight.Normal
                    )
                )
                if (service.port != null) {
                    Text(
                        text = " :${service.port}",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF444444)),
                            fontSize = 10.sp
                        )
                    )
                }
            }
            Text(
                text = "${statusLabel(runtime)} · ${impactLabel(runtime.impact)}",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF555555)),
                    fontSize = 9.sp
                )
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val notisIcon = if (service.isMuted) "🔕" else "🔔"
            Text(
                text = notisIcon,
                modifier = GlanceModifier
                    .padding(horizontal = 6.dp)
                    .clickable(actionRunCallback<ToggleMuteAction>(
                        actionParametersOf(serviceIdKey to service.id)
                    )),
                style = TextStyle(
                    color = ColorProvider(if (service.isMuted) Color(0xFF444444) else Color(0xFF888888)),
                    fontSize = 14.sp
                )
            )

            val powerColor = when {
                isPending -> Color(0xFFFFC857)
                isRunning -> Color(0xFF00FF88)
                else -> Color(0xFFFF4444)
            }
            Text(
                text = if (isPending) "…" else "⏻",
                modifier = GlanceModifier
                    .padding(start = 6.dp)
                    .clickable(actionRunCallback<TogglePowerAction>(
                        actionParametersOf(serviceIdKey to service.id)
                    )),
                style = TextStyle(
                    color = ColorProvider(powerColor),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

private val serviceIdKey = ActionParameters.Key<String>("serviceId")

class RefreshActionWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceWidget().update(context, glanceId)
    }
}

class ToggleMuteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serviceId = parameters[serviceIdKey] ?: return
        ServiceManager(context).toggleMute(serviceId)
        ServiceWidget().update(context, glanceId)
    }
}

class TogglePowerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serviceId = parameters[serviceIdKey] ?: return
        ServiceManager(context).togglePower(serviceId)
        ServiceWidget().update(context, glanceId)
    }
}
