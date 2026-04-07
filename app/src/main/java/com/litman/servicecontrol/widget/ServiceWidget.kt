package com.litman.servicecontrol.widget

import android.content.Context
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.action.clickable
import androidx.glance.Button
import androidx.glance.text.FontWeight
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import com.litman.servicecontrol.model.ServiceManager

class ServiceWidget : GlanceAppWidget() {
    
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val serviceManager = ServiceManager(context)
        val services = serviceManager.getSavedServices()

        // Hämtar status för alla tjänster live vid varje uppdatering
        val statuses = services.associate { service ->
            service.id to (service.port?.let { port -> serviceManager.checkStatus(port) } ?: false)
        }

        provideContent {
            ServiceWidgetContent(context, statuses)
        }
    }

    @Composable
    private fun ServiceWidgetContent(context: Context, statuses: Map<String, Boolean>) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.7f))
                .cornerRadius(16.dp)
        ) {
            Header()
            Spacer(modifier = GlanceModifier.height(8.dp))
            
            val serviceManager = ServiceManager(context)
            val services = serviceManager.getSavedServices()
            if (services.isEmpty()) {
                Text(
                    text = "Öppna appen och lägg till tjänster",
                    style = TextStyle(color = ColorProvider(Color.Gray), fontSize = 12.sp)
                )
            } else {
                services.forEach { service ->
                    val isActive = statuses[service.id] ?: false
                    ServiceRow(service.name, service.port, service.icon, isActive, service.id, service.scriptPath)
                }
            }
        }
    }

    @Composable
    private fun Header() {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SERVICE CONTROL",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = "🔄",
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
                style = TextStyle(color = ColorProvider(Color.White))
            )
        }
    }

    @Composable
    private fun ServiceRow(name: String, port: Int?, icon: String, isActive: Boolean, id: String, scriptPath: String) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(Color.White.copy(alpha = 0.05f))
                .cornerRadius(10.dp)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, style = TextStyle(fontSize = 18.sp))
            Spacer(modifier = GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = name,
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp)
                )
                Text(
                    text = if (port != null) "Port $port" else "Ej konfigurerad",
                    style = TextStyle(color = ColorProvider(Color.Gray), fontSize = 10.sp)
                )
            }

            if (port != null) {
                StatusIndicator(isActive)
                Spacer(modifier = GlanceModifier.width(8.dp))
                Button(
                    text = if (isActive) "STOP" else "START",
                    onClick = actionRunCallback<ToggleAction>(
                        actionParametersOf(
                            ActionParameters.Key<String>("service_id") to id,
                            ActionParameters.Key<Boolean>("is_active") to isActive,
                            ActionParameters.Key<String>("script_path") to scriptPath,
                            ActionParameters.Key<Int>("port") to port
                        )
                    ),
                    modifier = GlanceModifier.height(34.dp)
                )
            }
        }
    }

    @Composable
    private fun StatusIndicator(isActive: Boolean) {
        Box(
            modifier = GlanceModifier
                .size(10.dp)
                .background(if (isActive) Color.Green else Color.Gray)
                .cornerRadius(5.dp)
        ) {}
    }
}

// Action för Refresh-knappen
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ServiceWidget().update(context, glanceId)
    }
}

// Action för Start/Stopp-knapparna
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serviceId = parameters[ActionParameters.Key<String>("service_id")] ?: return
        val isActive = parameters[ActionParameters.Key<Boolean>("is_active")] ?: false
        val scriptPath = parameters[ActionParameters.Key<String>("script_path")] ?: ""
        val port = parameters[ActionParameters.Key<Int>("port")] ?: 0
        
        val serviceManager = ServiceManager(context)
        
        // Haptisk feedback
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

        if (isActive) {
            serviceManager.stopService(port)
        } else {
            serviceManager.runTermuxScript(scriptPath)
        }
        
        // Uppdatera widgeten efter en kort stund för att visa ny status
        kotlinx.coroutines.delay(1000)
        ServiceWidget().update(context, glanceId)
    }
}
