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

        // Use cached statuses for instant visual updates instead of blocking on network IO
        val runtimes    = manager.getCachedStatuses()
        val activeCount = services.count { service ->
            val rt = runtimes[service.id]
            rt != null && (rt.status == RunStatus.RUNNING || rt.status == RunStatus.ACTIVE)
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
                if (index < services.lastIndex) Spacer(GlanceModifier.height(8.dp))
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
    val isRunning = runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.ACTIVE
    val isUnknown = runtime.status == RunStatus.UNKNOWN

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

        // ── Open in browser (only for services with a URL) ────
        if (isRunning && !isPending && service.canOpen && service.openUrl != null) {
            Spacer(GlanceModifier.width(6.dp))
            Box(
                modifier = GlanceModifier
                    .size((settings.nameSize * 2.2f).dp)
                    .background(ColorProvider(Color(0xFF0E1A22)))
                    .cornerRadius(8.dp)
                    .clickable(
                        actionStartActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(service.openUrl))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↗",
                    style = TextStyle(
                        color      = ColorProvider(Color(0xFF2A6080)),
                        fontSize   = (settings.nameSize * 0.9f).sp,
                        fontFamily = font
                    )
                )
            }
            Spacer(GlanceModifier.width(8.dp))
        }

        // ── Power button ──────────────────────────────────────
        val btnSize = (settings.nameSize * 2.2f).dp
        val touchTarget = 44.dp
        
        Box(
            modifier = GlanceModifier
                .size(touchTarget)
                .clickable(
                    actionRunCallback<TogglePowerAction>(
                        actionParametersOf(serviceIdKey to service.id)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val btnBg = when {
                isPending -> Color(0xFF1A1A22)
                isRunning -> Color(theme.accentBg)
                else      -> Color(0xFF1C0E0E)
            }
            val btnFg = when {
                isPending -> Color(0xFF353545)
                isRunning -> accent
                isUnknown -> Color(0xFF3A3A4A)
                else      -> Color(0xFFBB3333)
            }

            Box(
                modifier = GlanceModifier
                    .size(btnSize)
                    .background(ColorProvider(btnBg))
                    .cornerRadius(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPending) "·" else "⏻",
                    style = TextStyle(
                        color      = ColorProvider(btnFg),
                        fontSize   = (settings.nameSize * 1.0f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = font
                    )
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
        ServiceWidget().update(context, glanceId)
    }
}

class ToggleMuteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serviceId = parameters[serviceIdKey] ?: return
        Log.d(TAG, "ToggleMuteAction: serviceId=$serviceId")
        ServiceManager(context).toggleMute(serviceId)
        ServiceWidget().update(context, glanceId)
    }
}

class TogglePowerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val serviceId = parameters[serviceIdKey] ?: return
        Log.d(TAG, "TogglePowerAction: serviceId=$serviceId")

        val manager = ServiceManager(context)
        
        // 1. OMEDELBAR FEEDBACK (Innan tunga status-checks)
        manager.markPending(serviceId)
        ServiceWidget().updateAll(context) 

        val service = manager.getSavedServices().find { it.id == serviceId } ?: run {
            manager.clearPending(serviceId)
            ServiceWidget().updateAll(context)
            return
        }

        // 2. Re-check actual status
        val currentStatus    = manager.checkStatus(service)
        val isCurrentlyRunning = currentStatus.status == RunStatus.RUNNING ||
                                 currentStatus.status == RunStatus.ACTIVE
        
        // 3. Command execution
        manager.togglePower(serviceId, isCurrentlyRunning)

        // 4. Final verification & Global Sync
        delay(1200)
        val newStatus     = manager.checkStatus(service)
        val isNowRunning  = newStatus.status == RunStatus.RUNNING || newStatus.status == RunStatus.ACTIVE
        
        if (isNowRunning && !isCurrentlyRunning) manager.recordStartTime(serviceId)
        if (!isNowRunning && isCurrentlyRunning) manager.clearStartTime(serviceId)
        
        manager.clearPending(serviceId)
        ServiceWidget().updateAll(context)
    }
}
