package com.litman.servicecontrol.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.litman.servicecontrol.model.*
import kotlinx.coroutines.delay

private const val TAG = "ServiceCtrl"

// ── Theme helpers ─────────────────────────────────────────────────────────────

private fun widgetFont(style: String): FontFamily = when (style) {
    "MONO" -> FontFamily.Monospace
    else   -> FontFamily.SansSerif
}

private fun themeColors(themeId: String): AppTheme = Themes.find(themeId)

// ── Data helpers ──────────────────────────────────────────────────────────────

private fun modeStr(service: ServiceItem): String = when {
    service.checkMode == StatusCheckMode.PORT && service.port != null -> ":${service.port}"
    service.checkMode == StatusCheckMode.PROCESS -> "proc"
    else -> "fork"
}

// ── Widget entry point ────────────────────────────────────────────────────────

class ServiceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Log.d(TAG, "provideGlance: loading settings and services")
        val manager  = ServiceManager(context)
        val settings = manager.getWidgetSettings()
        val all      = manager.getSavedServices()

        val services = all.filter {
            it.isEnabledOnWidget &&
            (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID)
        }
        Log.d(TAG, "provideGlance: ${services.size} widget services")

        // Use cached statuses but OVERRIDE with pending state if active
        val cachedRuntimes = manager.getCachedStatuses()
        val runtimes = services.associate { s ->
            val pending = manager.getPendingState(s.id)
            val rt = when (pending) {
                "STARTING" -> ServiceRuntime(RunStatus.STARTING)
                "STOPPING" -> ServiceRuntime(RunStatus.STOPPING)
                else -> cachedRuntimes[s.id] ?: ServiceRuntime.UNKNOWN
            }
            s.id to rt
        }

        val activeCount = runtimes.values.count { 
            it.status == RunStatus.RUNNING || it.status == RunStatus.DEGRADED 
        }
        
        val pending = services.associate { it.id to manager.isPending(it.id) }
        // Uptime per running service (null when unknown or not tracked)
        val uptimes = services.associate { it.id to manager.getUptime(it.id) }

        Log.d(TAG, "provideGlance: $activeCount/${services.size} online")

        provideContent {
            WidgetBoard(
                services    = services,
                runtimes    = runtimes,
                activeCount = activeCount,
                pending     = pending,
                uptimes     = uptimes,
                settings    = settings
            )
        }
    }
}

// ── Widget board ──────────────────────────────────────────────────────────────

@Composable
private fun WidgetBoard(
    services: List<ServiceItem>,
    runtimes: Map<String, ServiceRuntime>,
    activeCount: Int,
    pending: Map<String, Boolean>,
    uptimes: Map<String, String?>,
    settings: WidgetSettings
) {
    val theme  = themeColors(settings.theme)
    val bg     = Color(red = 13, green = 13, blue = 17, alpha = settings.opacity)
    val accent = Color(theme.accent)
    val font   = widgetFont(settings.fontStyle)
    val total  = services.size
    val pad    = settings.padding.dp
    val rowGap = settings.rowSpacing.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(bg))
            .cornerRadius(settings.cornerRadius.dp)
            .padding(pad)
    ) {
        // ── Header row ───────────────────────────────────────
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(bottom = (settings.padding * 0.5f).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "SERVICE CONTROL",
                    style = TextStyle(
                        color      = ColorProvider(Color(0xFFBBBBC8)),
                        fontSize   = (settings.nameSize * 0.73f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = font
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "PM2  ·  ",
                        style = TextStyle(
                            color      = ColorProvider(Color(0xFF404050)),
                            fontSize   = (settings.metaSize * 0.85f).sp,
                            fontFamily = font
                        )
                    )
                    Text(
                        text = "$activeCount/$total",
                        style = TextStyle(
                            color      = ColorProvider(if (activeCount > 0) accent else Color(0xFF404050)),
                            fontSize   = (settings.metaSize * 0.85f).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = font
                        )
                    )
                    Text(
                        text = " online",
                        style = TextStyle(
                            color      = ColorProvider(Color(0xFF404050)),
                            fontSize   = (settings.metaSize * 0.85f).sp,
                            fontFamily = font
                        )
                    )
                }
            }

            Box(
                modifier = GlanceModifier
                    .size(24.dp)
                    .background(ColorProvider(Color(0xFF16161E)))
                    .cornerRadius(6.dp)
                    .clickable(actionRunCallback<RefreshActionWidget>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↻",
                    style = TextStyle(color = ColorProvider(Color(0xFF55556A)), fontSize = 12.sp)
                )
            }
        }

        // ── Divider ───────────────────────────────────────────
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ColorProvider(Color(0xFF1A1A26)))
        ) {}

        Spacer(GlanceModifier.height((settings.padding * 0.6f).dp))

        // ── Optional column headers ───────────────────────────
        if (settings.showColumnHeaders) {
            val hStyle = TextStyle(
                color      = ColorProvider(Color(0xFF2E2E3E)),
                fontSize   = (settings.metaSize * 0.78f).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = font
            )
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "SERVICE", modifier = GlanceModifier.defaultWeight(), style = hStyle)
                Text(text = "CTRL", style = hStyle)
            }
        }

        // ── Service rows ──────────────────────────────────────
        if (services.isEmpty()) {
            Text(
                text = "> no services configured",
                modifier = GlanceModifier.padding(top = 4.dp),
                style = TextStyle(
                    color      = ColorProvider(Color(0xFF404050)),
                    fontSize   = settings.metaSize.sp,
                    fontFamily = font
                )
            )
        } else {
            services.forEachIndexed { index, service ->
                val runtime   = runtimes[service.id] ?: ServiceRuntime.UNKNOWN
                val isPending = pending[service.id] ?: false
                val uptime    = uptimes[service.id]
                WidgetServiceRow(service, runtime, isPending, uptime, settings, font, accent, theme)
                if (index < services.lastIndex) Spacer(GlanceModifier.height(rowGap))
            }
        }
    }
}

// ── Service row ───────────────────────────────────────────────────────────────

@Composable
private fun WidgetServiceRow(
    service: ServiceItem,
    runtime: ServiceRuntime,
    isPending: Boolean,
    uptime: String?,
    settings: WidgetSettings,
    font: FontFamily,
    accent: Color,
    theme: AppTheme
) {
    val isRunning = runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.DEGRADED
    val isUnknown = runtime.status == RunStatus.UNKNOWN
    val canToggle = !isPending && ((isRunning && service.canStop) || (!isRunning && service.canStart))

    val nameColor = when {
        isPending -> Color(0xFF505060)
        isRunning -> Color(0xFFEEEEF5)
        isUnknown -> Color(0xFF505060)
        else      -> Color(0xFF6A6A7A)
    }
    val statusColor = when {
        isPending -> Color(0xFF404050)  // pending: muted
        isRunning -> accent
        isUnknown -> Color(0xFF3A3A4A)
        else      -> Color(0xFF993333)
    }
    val statusLabel = when {
        isPending -> "···"   // clear visual indicator that action is in flight
        isRunning -> "online"
        isUnknown -> "—"
        else      -> "offline"
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left column ───────────────────────────────────────
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = service.label,
                style = TextStyle(
                    color      = ColorProvider(nameColor),
                    fontSize   = settings.nameSize.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = font
                )
            )
            Spacer(GlanceModifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isPending) {
                    Text(
                        text = modeStr(service),
                        style = TextStyle(
                            color      = ColorProvider(Color(0xFF3E3E52)),
                            fontSize   = settings.metaSize.sp,
                            fontFamily = font
                        )
                    )
                    Text(
                        text = "  ·  ",
                        style = TextStyle(
                            color      = ColorProvider(Color(0xFF252530)),
                            fontSize   = settings.metaSize.sp,
                            fontFamily = font
                        )
                    )
                }
                Text(
                    text = statusLabel,
                    style = TextStyle(
                        color      = ColorProvider(statusColor),
                        fontSize   = settings.metaSize.sp,
                        fontWeight = if (isRunning && !isPending) FontWeight.Medium else FontWeight.Normal,
                        fontFamily = font
                    )
                )
                if (isRunning && !isPending && settings.showMemory && uptime != null) {
                    Text(
                        text = "  ·  ",
                        style = TextStyle(
                            color      = ColorProvider(Color(0xFF252530)),
                            fontSize   = settings.metaSize.sp,
                            fontFamily = font
                        )
                    )
                    Text(
                        text = uptime,
                        style = TextStyle(
                            color      = ColorProvider(Color(0xFF3E3E52)),
                            fontSize   = settings.metaSize.sp,
                            fontFamily = font
                        )
                    )
                }
            }
        }

        // ── Actions ──────────────────────────────────────────
        val actionTextSize = (settings.metaSize * 0.9f * settings.actionScale).sp
        val actionFont     = font
        val buttonHPad     = (10f * settings.actionScale).dp
        val buttonVPad     = (6f * settings.actionScale).dp
        val openHPad       = (8f * settings.actionScale).dp
        val openVPad       = (4f * settings.actionScale).dp

        Row(verticalAlignment = Alignment.CenterVertically) {
            // ── Open Link ──
            if (isRunning && !isPending && service.canOpen && service.openUrl != null) {
                Box(
                    modifier = GlanceModifier
                        .padding(end = 6.dp)
                        .background(ColorProvider(Color(0xFF161C22)))
                        .cornerRadius(6.dp)
                        .clickable(actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.openUrl)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OPEN",
                        modifier = GlanceModifier.padding(horizontal = openHPad, vertical = openVPad),
                        style = TextStyle(color = ColorProvider(Color(0xFF3399FF)), fontSize = actionTextSize, fontWeight = FontWeight.Bold, fontFamily = actionFont)
                    )
                }
            }

            // ── Power Button (Text Chip) ──
            val btnBg = when {
                isPending -> Color(0xFF1A1A22)
                !canToggle -> Color(0xFF15151B)
                isRunning -> Color(theme.accentBg)
                else      -> Color(0xFF1C0E0E)
            }
            val btnFg = when {
                isPending -> Color(0xFF353545)
                !canToggle -> Color(0xFF505060)
                isRunning -> accent
                else      -> Color(0xFFBB3333)
            }
            val btnLabel = when {
                isPending -> "···"
                !canToggle -> "LOCK"
                isRunning -> "STOP"
                else      -> "START"
            }
            val powerModifier = GlanceModifier
                .background(ColorProvider(btnBg))
                .cornerRadius(6.dp)
                .let {
                    if (canToggle) {
                        it.clickable(actionRunCallback<TogglePowerAction>(actionParametersOf(serviceIdKey to service.id)))
                    } else {
                        it
                    }
                }

            Box(
                modifier = powerModifier,
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = btnLabel,
                    modifier = GlanceModifier.padding(horizontal = buttonHPad, vertical = buttonVPad),
                    style = TextStyle(color = ColorProvider(btnFg), fontSize = actionTextSize, fontWeight = FontWeight.Bold, fontFamily = actionFont)
                )
            }
        }
    }
}

// ── Action callbacks ──────────────────────────────────────────────────────────

private val serviceIdKey = ActionParameters.Key<String>("serviceId")

class RefreshActionWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.d(TAG, "RefreshActionWidget: triggered")
        val manager = ServiceManager(context)
        val services = manager.getSavedServices().filter {
            it.isEnabledOnWidget &&
            (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID)
        }
        manager.checkAllStatuses(services)
        WidgetUpdater.refresh(context)
    }
}

class TogglePowerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serviceId = parameters[serviceIdKey] ?: return
        Log.d(TAG, "[ServiceCtrl] Widget Action ENTERED: id=$serviceId")

        val manager = ServiceManager(context)
        val services = manager.getSavedServices()
        val service = services.find { it.id == serviceId } ?: return

        // 1. Re-check actual status to determine direction
        val currentStatus = manager.checkStatus(service)
        val isCurrentlyRunning = currentStatus.status == RunStatus.RUNNING || 
                                 currentStatus.status == RunStatus.DEGRADED
        
        // 2. Command execution — togglePower marks pending (STARTING/STOPPING) internally
        val commandStarted = manager.togglePower(serviceId, isCurrentlyRunning)
        // Immediate visual feedback: widget reflects pending state set by togglePower
        WidgetUpdater.refresh(context)
        if (!commandStarted) return

        // 4. Robust Verification Loop (10 attempts = 10s)
        var reachedTarget = false
        val targetStopped = isCurrentlyRunning // If it was running, we want it stopped
        
        for (i in 1..10) {
            delay(1000)
            val finalStatus = manager.checkAndCacheStatus(service)
            
            reachedTarget = if (targetStopped) {
                finalStatus.status == RunStatus.STOPPED
            } else {
                finalStatus.status == RunStatus.RUNNING || finalStatus.status == RunStatus.DEGRADED
            }
            
            if (reachedTarget) {
                Log.d(TAG, "[ServiceCtrl] Widget Action: Target reached in attempt $i")
                break
            }
        }
        
        // 5. Cleanup
        manager.clearPending(serviceId)
        if (!reachedTarget) {
            Log.w(TAG, "[ServiceCtrl] Widget Action: Timeout (10s) reached for $serviceId. Target state not confirmed.")
        }
        WidgetUpdater.refresh(context)
    }
}
