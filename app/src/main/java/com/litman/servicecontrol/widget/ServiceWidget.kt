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
import com.litman.servicecontrol.model.*

class ServiceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val manager = ServiceManager(context)
        val allSaved = manager.getSavedServices()
        val panels = allSaved.filter { it.group == ServiceGroup.PANELS || it.group == ServiceGroup.UNKNOWN }
        val allWidgetEnabled = panels.filter { it.isEnabledOnWidget && (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID) }
        
        val servicesToShow = allWidgetEnabled.take(3)

        val runtimes: Map<String, ServiceRuntime> = servicesToShow.associate { s ->
            s.id to manager.checkStatusWithLoad(s)
        }

        val activeCount = runtimes.values.count { it.status == RunStatus.RUNNING || it.status == RunStatus.ACTIVE }
        val sysLoad    = systemLoad(runtimes.values)
        val sysColorInt = systemLoadColor(sysLoad)

        provideContent {
            WidgetRoot(
                services = servicesToShow,
                runtimes = runtimes,
                activeCount = activeCount,
                totalCount = panels.size,
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
            .background(ColorProvider(Color(0xFF0A0A0A)))
            .padding(10.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "SERVICE CONTROL",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFBBBBBB)),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "$sysLoad · $activeCount/$totalCount aktiva",
                    style = TextStyle(
                        color = ColorProvider(Color(sysColor)),
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
                    fontSize = 18.sp
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
                    text = "Inga tjänster i widgeten",
                    style = TextStyle(color = ColorProvider(Color(0xFF333333)), fontSize = 11.sp)
                )
            }
        } else {
            services.forEach { service ->
                val runtime = runtimes[service.id] ?: ServiceRuntime.UNKNOWN
                ServiceWidgetRow(service, runtime)
                Spacer(GlanceModifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ServiceWidgetRow(service: ServiceItem, runtime: ServiceRuntime) {
    val dotColorInt = statusDotColor(runtime)
    val label = statusLabel(runtime)
    val name = service.label

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(ColorProvider(Color(0xFF151515)))
            .cornerRadius(4.dp)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .background(ColorProvider(Color(dotColorInt)))
                .cornerRadius(3.dp)
        ) {}
        
        Spacer(GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = name,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        val info = if (service.port != null) "$label · :${service.port}" else label
        Text(
            text = info,
            style = TextStyle(
                color = ColorProvider(Color(0xFF888888)),
                fontSize = 10.sp
            )
        )
    }
}

class RefreshActionWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceWidget().update(context, glanceId)
    }
}
