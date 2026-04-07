package com.litman.servicecontrol.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
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
        val allSaved = manager.getSavedServices()
        val allWidgetEnabled = allSaved.filter { it.isEnabledOnWidget }
        
        // Max 3 tjänster för widget stabilitet
        val servicesToShow = allWidgetEnabled.take(3)

        val runtimes: Map<String, ServiceRuntime> = servicesToShow.associate { s ->
            s.id to manager.checkStatusWithLoad(s.port)
        }

        val activeCount = runtimes.values.count { it.status == RunStatus.RUNNING }
        val sysLoad    = systemLoad(runtimes.values)
        val sysColorInt = systemLoadColor(sysLoad)

        provideContent {
            WidgetRoot(
                services = servicesToShow,
                runtimes = runtimes,
                activeCount = activeCount,
                totalCount = allSaved.size,
                sysLoad = sysLoad,
                sysColor = sysColorInt
            )
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
            .background(ColorProvider(Color(0xFF0D0D0D)))
            .padding(8.dp)
    ) {
        // --- Header ---
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
                Text(
                    text = "System: " + sysLoad + "  ·  " + activeCount + "/" + totalCount + " aktiva",
                    style = TextStyle(
                        color = ColorProvider(Color(sysColor)),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            
            // Refresh
            Text(
                text = "↺",
                modifier = GlanceModifier
                    .padding(4.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
                style = TextStyle(
                    color = ColorProvider(Color(0xFF888888)),
                    fontSize = 18.sp
                )
            )
        }

        Spacer(GlanceModifier.height(8.dp))

        // --- Services ---
        if (services.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
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
    val label = statusLabel(runtime)
    val name = service.label

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(ColorProvider(Color(0xFF1A1A1A)))
            .cornerRadius(8.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Enkel fyrkant som status-indikator istället för custom dot
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .background(ColorProvider(Color(dotColorInt)))
                .cornerRadius(4.dp)
        ) {}
        
        Spacer(GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = name,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            val info = if (service.port != null) label + " · :" + service.port else label
            Text(
                text = info,
                style = TextStyle(
                    color = ColorProvider(Color(dotColorInt)),
                    fontSize = 10.sp
                )
            )
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceWidget().update(context, glanceId)
    }
}
