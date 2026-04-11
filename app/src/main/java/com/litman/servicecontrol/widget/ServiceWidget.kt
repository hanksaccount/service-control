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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.litman.servicecontrol.model.*
import java.util.Locale
import kotlin.math.abs

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun widgetFont(style: String): FontFamily = when (style) {
    "MONO" -> FontFamily.Monospace
    else   -> FontFamily.SansSerif
}

private fun accentColor(style: String): Color = when (style) {
    "CYAN"  -> Color(0xFF00C8DD)
    "AMBER" -> Color(0xFFFFB300)
    else    -> Color(0xFF00D966)  // GREEN default
}

private fun accentBg(style: String): Color = when (style) {
    "CYAN"  -> Color(0xFF0F2025)
    "AMBER" -> Color(0xFF201800)
    else    -> Color(0xFF0F1F14)  // GREEN default
}

/** Mode label shown in meta row — mimics PM2's fork/proc style */
private fun modeLabel(service: ServiceItem): String = when {
    service.checkMode == StatusCheckMode.PORT && service.port != null -> ":${service.port}"
    service.checkMode == StatusCheckMode.PROCESS -> "proc"
    else -> "fork"
}

/** Stable fake memory value — consistent per service based on id hash */
private fun fakeMemMb(service: ServiceItem): String {
    val mb = 18.0 + (abs(service.id.hashCode()) % 820) / 10.0
    return String.format(Locale.US, "%.0f MB", mb)
}

// ── Widget ───────────────────────────────────────────────────────────────────

class ServiceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val manager  = ServiceManager(context)
        val settings = manager.getWidgetSettings()
        val allSaved = manager.getSavedServices()

        val services = allSaved.filter {
            it.isEnabledOnWidget &&
            (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID)
        }

        val runtimes: Map<String, ServiceRuntime> = services.associate { s ->
            s.id to manager.checkStatus(s)
        }

        val activeCount = runtimes.values.count {
            it.status == RunStatus.RUNNING || it.status == RunStatus.ACTIVE
        }

        provideContent {
            WidgetRoot(
                services    = services,
                runtimes    = runtimes,
                activeCount = activeCount,
                settings    = settings
            )
        }
    }
}

// ── Root layout ───────────────────────────────────────────────────────────────

@Composable
private fun WidgetRoot(
    services: List<ServiceItem>,
    runtimes: Map<String, ServiceRuntime>,
    activeCount: Int,
    settings: WidgetSettings
) {
    val bgAlpha   = settings.opacity
    val bgColor   = Color(red = 13, green = 13, blue = 17, alpha = bgAlpha)
    val font      = widgetFont(settings.fontStyle)
    val accent    = accentColor(settings.accentColor)
    val pad       = settings.padding.dp
    val total     = services.size

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(bgColor))
            .cornerRadius(settings.cornerRadius.dp)
            .padding(pad)
    ) {

        // ── Header ───────────────────────────────────────────────
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(bottom = (settings.padding * 0.55f).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "SERVICE CONTROL",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFCCCCD8)),
                        fontSize = (settings.nameSize * 0.75f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = font
                    )
                )
                Spacer(GlanceModifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "TERMUX / PM2  ·  ",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF484858)),
                            fontSize = (settings.metaSize * 0.88f).sp,
                            fontFamily = font
                        )
                    )
                    Text(
                        text = "$activeCount/$total",
                        style = TextStyle(
                            color = ColorProvider(if (activeCount > 0) accent else Color(0xFF484858)),
                            fontSize = (settings.metaSize * 0.88f).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = font
                        )
                    )
                    Text(
                        text = " online",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF484858)),
                            fontSize = (settings.metaSize * 0.88f).sp,
                            fontFamily = font
                        )
                    )
                }
            }

            // Refresh button
            Box(
                modifier = GlanceModifier
                    .size(26.dp)
                    .background(ColorProvider(Color(0xFF181820)))
                    .cornerRadius(7.dp)
                    .clickable(actionRunCallback<RefreshActionWidget>()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↻",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF666676)),
                        fontSize = 13.sp
                    )
                )
            }
        }

        // ── Divider ───────────────────────────────────────────────
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ColorProvider(Color(0xFF1C1C28)))
        ) {}

        Spacer(GlanceModifier.height((settings.padding * 0.65f).dp))

        // ── Column headers (optional) ─────────────────────────────
        if (settings.showColumnHeaders) {
            val hdrStyle = TextStyle(
                color = ColorProvider(Color(0xFF333344)),
                fontSize = (settings.metaSize * 0.80f).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = font
            )
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "SERVICE", modifier = GlanceModifier.defaultWeight(), style = hdrStyle)
                Text(text = "CTRL",    style = hdrStyle)
            }
        }

        // ── Service rows ──────────────────────────────────────────
        if (services.isEmpty()) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = "> no processes configured",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF484858)),
                    fontSize = settings.metaSize.sp,
                    fontFamily = font
                )
            )
        } else {
            services.forEachIndexed { index, service ->
                val runtime = runtimes[service.id] ?: ServiceRuntime.UNKNOWN
                ServiceRow(service, runtime, settings, font, accent)
                if (index < services.lastIndex) {
                    Spacer(GlanceModifier.height(9.dp))
                }
            }
        }
    }
}

// ── Service row ───────────────────────────────────────────────────────────────

@Composable
private fun ServiceRow(
    service: ServiceItem,
    runtime: ServiceRuntime,
    settings: WidgetSettings,
    font: FontFamily,
    accent: Color
) {
    val isRunning   = runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.ACTIVE
    val isUnknown   = runtime.status == RunStatus.UNKNOWN
    val statusColor = when {
        isRunning -> accent
        isUnknown -> Color(0xFF444455)
        else      -> Color(0xFFAA2222)
    }
    val nameColor = when {
        isRunning -> Color(0xFFEEEEF5)
        isUnknown -> Color(0xFF555566)
        else      -> Color(0xFF6E6E80)
    }
    val statusLabel = when {
        isRunning -> "online"
        isUnknown -> "unknown"
        else      -> "offline"
    }
    val modeStr = modeLabel(service)

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left: name + meta ─────────────────────────────────
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = service.label,
                style = TextStyle(
                    color = ColorProvider(nameColor),
                    fontSize = settings.nameSize.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = font
                )
            )
            Spacer(GlanceModifier.height(2.dp))
            // Meta row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = modeStr,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF454558)),
                        fontSize = settings.metaSize.sp,
                        fontFamily = font
                    )
                )
                Text(
                    text = "  ·  ",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF28282E)),
                        fontSize = settings.metaSize.sp,
                        fontFamily = font
                    )
                )
                Text(
                    text = statusLabel,
                    style = TextStyle(
                        color = ColorProvider(statusColor),
                        fontSize = settings.metaSize.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = font
                    )
                )
                if (isRunning && settings.showMemory) {
                    Text(
                        text = "  ·  ",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF28282E)),
                            fontSize = settings.metaSize.sp,
                            fontFamily = font
                        )
                    )
                    Text(
                        text = fakeMemMb(service),
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF454558)),
                            fontSize = settings.metaSize.sp,
                            fontFamily = font
                        )
                    )
                }
            }
        }

        // ── Right: power button ───────────────────────────────
        val btnBg = if (isRunning) accentBg(settings.accentColor) else Color(0xFF200E0E)
        val btnFg = if (isRunning) accent else Color(0xFF993322)

        Box(
            modifier = GlanceModifier
                .size((settings.nameSize * 2.05f).dp)
                .background(ColorProvider(btnBg))
                .cornerRadius(8.dp)
                .clickable(
                    actionRunCallback<TogglePowerAction>(
                        actionParametersOf(
                            serviceIdKey to service.id,
                            isRunningKey to isRunning
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⏻",
                style = TextStyle(
                    color = ColorProvider(btnFg),
                    fontSize = (settings.nameSize * 1.0f).sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

// ── Action callbacks ──────────────────────────────────────────────────────────

private val serviceIdKey = ActionParameters.Key<String>("serviceId")
private val isRunningKey  = ActionParameters.Key<Boolean>("isRunning")

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
        val isRunning = parameters[isRunningKey] ?: false
        ServiceManager(context).togglePower(serviceId, isRunning)
        ServiceWidget().update(context, glanceId)
    }
}
