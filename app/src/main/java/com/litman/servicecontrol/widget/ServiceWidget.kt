package com.litman.servicecontrol.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
        val allServices = manager.getSavedServices().filter { it.isEnabledOnWidget }
        // Visa max 3 tjänster för att hålla det enkelt och stabilt
        val services = allServices.take(3)

        val runtimes: Map<String, ServiceRuntime> = services.associate { s ->
            s.id to (s.port?.let { manager.checkStatusWithLoad(it) } ?: ServiceRuntime.NO_PORT)
        }

        val activeCount = runtimes.values.count { it.status == RunStatus.RUNNING }
        val sysLoad    = systemLoad(runtimes.values)
        val sysColor   = systemLoadColor(sysLoad)

        provideContent {
            WidgetRoot(services, runtimes, activeCount, allServices.size, sysLoad, sysColor)
        }
    }
}

@Composable
private fun WidgetRoot(
    services: List<ServiceItem>,
    runtimes: Map<String, ServiceRuntime>,
    activeCount: Int,
    totalCount: Int,
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
                        text = "System: $sysLoad  ·  $activeCount/$totalCount aktiva",
                        style = TextStyle(
                            color = ColorProvider(Color(sysColor)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            // Enkel Refresh-knapp
            Text(
                text = "↺",
                modifier = GlanceModifier
                    .clickable(actionRunCallback<RefreshAction>())
                    .padding(4.dp),
                style = TextStyle(color = ColorProvider(Color(0xFF888888)), fontSize = 16.sp)
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        // ── Tjänster ────────────────────────────────────────────
        if (services.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Inga tjänster i widgeten",
                    style = TextStyle(color = ColorProvider(Color(0xFF555555)), fontSize = 11.sp)
                )
            }
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
    val dotColorInt = statusDotColor(runtime)
    val dotColor = Color(dotColorInt)
    val label = statusLabel(runtime)

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .cornerRadius(8.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color = dotColor, size = 8.dp)
        Spacer(GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = service.label,
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

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceWidget().update(context, glanceId)
    }
}
