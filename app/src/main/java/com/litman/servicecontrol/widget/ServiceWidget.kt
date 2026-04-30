package com.litman.servicecontrol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.litman.servicecontrol.R
import com.litman.servicecontrol.model.AppTheme
import com.litman.servicecontrol.model.RunStatus
import com.litman.servicecontrol.model.ServiceItem
import com.litman.servicecontrol.model.ServiceManager
import com.litman.servicecontrol.model.ServiceRuntime
import com.litman.servicecontrol.model.ServiceType
import com.litman.servicecontrol.model.StatusCheckMode
import com.litman.servicecontrol.model.Themes
import com.litman.servicecontrol.model.WidgetSettings
import kotlin.math.roundToInt

private const val TAG = "ServiceCtrl"

object ServiceWidget {
    const val ACTION_REFRESH = "com.litman.servicecontrol.widget.REFRESH"
    const val ACTION_TOGGLE = "com.litman.servicecontrol.widget.TOGGLE"
    const val EXTRA_SERVICE_ID = "service_id"

    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = WidgetUpdater.appWidgetIds(appContext)
        update(appContext, manager, ids)
    }

    fun update(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val appContext = context.applicationContext
        val serviceManager = ServiceManager(appContext)
        val settings = serviceManager.getWidgetSettings()
        val services = serviceManager.getSavedServices()
            .filter { it.isEnabledOnWidget && (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID) }
            .take(4)
        val runtimes = serviceManager.getCachedStatuses()
        val uptimes = services.associate { it.id to serviceManager.getUptime(it.id) }

        appWidgetIds.forEach { id ->
            manager.updateAppWidget(id, render(appContext, id, services, runtimes, uptimes, settings))
        }
        Log.d(TAG, "[ServiceCtrl] Classic widget updated ids=${appWidgetIds.joinToString(",")}")
    }

    suspend fun refreshStatuses(context: Context) {
        val appContext = context.applicationContext
        val manager = ServiceManager(appContext)
        val services = manager.getSavedServices()
            .filter { it.isEnabledOnWidget && (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID) }
        manager.checkAllStatuses(services)
        updateAll(appContext)
    }

    suspend fun toggle(context: Context, serviceId: String) {
        val appContext = context.applicationContext
        val manager = ServiceManager(appContext)
        val service = manager.getSavedServices().find { it.id == serviceId } ?: return
        val current = manager.checkAndCacheStatus(service)
        val isRunning = current.status == RunStatus.RUNNING || current.status == RunStatus.DEGRADED
        
        // Start the command immediately
        val commandStarted = manager.togglePower(serviceId, isRunning)
        
        // Update widget immediately to show the "pending" state
        updateAll(appContext)
        
        if (!commandStarted) return
        
        // We do NOT call waitForToggleCompletion here anymore as it blocks the coroutine.
        // Instead, the background refresh or subsequent manual refreshes will catch the state change.
        // Or we could launch a separate detached scope for waiting if we really want "active" polling.
    }

    private fun render(
        context: Context,
        appWidgetId: Int,
        services: List<ServiceItem>,
        runtimes: Map<String, ServiceRuntime>,
        uptimes: Map<String, String?>,
        settings: WidgetSettings
    ): RemoteViews {
        val theme = Themes.find(settings.theme)
        val views = RemoteViews(context.packageName, R.layout.service_widget)
        val activeCount = services.count {
            val status = runtimes[it.id]?.status
            status == RunStatus.RUNNING || status == RunStatus.DEGRADED
        }

        views.setInt(R.id.widget_root, "setBackgroundColor", argb(settings.opacity, 8, 10, 13))
        views.setTextViewText(R.id.widget_title, "SERVICE CONTROL")
        views.setTextViewText(R.id.widget_summary, "$activeCount/${services.size} online")
        val rootPadding = dp(context, settings.padding)
        views.setViewPadding(R.id.widget_root, rootPadding, rootPadding, rootPadding, rootPadding)
        views.setTextColor(R.id.widget_title, Color.rgb(244, 247, 250))
        views.setTextColor(R.id.widget_summary, colorForActive(activeCount, theme))
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, settings.nameSize * 0.78f)
        views.setTextViewTextSize(R.id.widget_summary, TypedValue.COMPLEX_UNIT_SP, settings.metaSize)
        views.setOnClickPendingIntent(R.id.widget_refresh, pending(context, ACTION_REFRESH, appWidgetId, null))

        val rowIds = rows()
        rowIds.forEachIndexed { index, row ->
            val service = services.getOrNull(index)
            if (service == null) {
                views.setViewVisibility(row.container, View.GONE)
            } else {
                bindRow(context, appWidgetId, views, row, service, runtimes[service.id], uptimes[service.id], settings, theme)
            }
        }

        return views
    }

    private fun bindRow(
        context: Context,
        appWidgetId: Int,
        views: RemoteViews,
        row: WidgetRowIds,
        service: ServiceItem,
        runtime: ServiceRuntime?,
        uptime: String?,
        settings: WidgetSettings,
        theme: AppTheme
    ) {
        val status = runtime?.status ?: RunStatus.UNKNOWN
        val pending = ServiceManager(context).getPendingState(service.id)
        val isPending = pending != null
        val isRunning = status == RunStatus.RUNNING || status == RunStatus.DEGRADED
        val canToggle = !isPending && ((isRunning && service.canStop) || (!isRunning && service.canStart))
        val action = when {
            isPending -> "..."
            !canToggle -> "LOCK"
            isRunning -> "STOP"
            else -> "START"
        }
        val meta = buildString {
            append(shortMode(service))
            append("  ")
            append(shortStatus(status, isPending))
            if (settings.showMemory && isRunning && !uptime.isNullOrBlank()) append("  $uptime")
        }

        views.setViewVisibility(row.container, View.VISIBLE)
        views.setTextViewText(row.name, service.label)
        views.setTextViewText(row.meta, meta)
        views.setTextViewText(row.action, action)
        views.setTextColor(row.name, if (isRunning) Color.rgb(244, 247, 250) else Color.rgb(120, 132, 142))
        views.setTextColor(row.meta, Color.rgb(107, 120, 132))
        views.setTextColor(row.action, actionColor(status, isPending, canToggle, theme))
        views.setInt(row.indicator, "setBackgroundColor", statusColor(status, isPending, theme))
        views.setViewPadding(row.container, 0, 0, 0, dp(context, settings.rowSpacing))
        views.setTextViewTextSize(row.name, TypedValue.COMPLEX_UNIT_SP, settings.nameSize)
        views.setTextViewTextSize(row.meta, TypedValue.COMPLEX_UNIT_SP, settings.metaSize)
        views.setTextViewTextSize(row.action, TypedValue.COMPLEX_UNIT_SP, settings.metaSize * 0.95f * settings.actionScale)
        views.setOnClickPendingIntent(row.action, if (canToggle) pending(context, ACTION_TOGGLE, appWidgetId, service.id) else pending(context, ACTION_REFRESH, appWidgetId, null))
        val open = if (isRunning && service.canOpen && service.openUrl != null) openIntent(context, service) else null
        views.setOnClickPendingIntent(row.container, open ?: pending(context, ACTION_REFRESH, appWidgetId, null))
    }

    private fun pending(context: Context, action: String, appWidgetId: Int, serviceId: String?): PendingIntent {
        val intent = Intent(context, ServiceWidgetReceiver::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            serviceId?.let { putExtra(EXTRA_SERVICE_ID, it) }
        }
        val requestCode = (action + appWidgetId + (serviceId ?: "")).hashCode()
        return PendingIntent.getBroadcast(context, requestCode, intent, pendingFlags())
    }

    fun openIntent(context: Context, service: ServiceItem): PendingIntent? {
        val url = service.openUrl ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return PendingIntent.getActivity(context, service.id.hashCode(), intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.FLAG_UPDATE_CURRENT or immutable
    }

    private fun rows() = listOf(
        WidgetRowIds(R.id.widget_row_1, R.id.widget_row_1_indicator, R.id.widget_row_1_name, R.id.widget_row_1_meta, R.id.widget_row_1_action),
        WidgetRowIds(R.id.widget_row_2, R.id.widget_row_2_indicator, R.id.widget_row_2_name, R.id.widget_row_2_meta, R.id.widget_row_2_action),
        WidgetRowIds(R.id.widget_row_3, R.id.widget_row_3_indicator, R.id.widget_row_3_name, R.id.widget_row_3_meta, R.id.widget_row_3_action),
        WidgetRowIds(R.id.widget_row_4, R.id.widget_row_4_indicator, R.id.widget_row_4_name, R.id.widget_row_4_meta, R.id.widget_row_4_action)
    )

    private fun shortMode(service: ServiceItem): String = when {
        service.checkMode == StatusCheckMode.PORT && service.port != null -> ":${service.port}"
        service.checkMode == StatusCheckMode.PROCESS -> "proc"
        else -> "fork"
    }

    private fun shortStatus(status: RunStatus, pending: Boolean): String = when {
        pending -> "pending"
        status == RunStatus.RUNNING -> "online"
        status == RunStatus.DEGRADED -> "partial"
        status == RunStatus.STOPPED -> "offline"
        else -> "unknown"
    }

    private fun statusColor(status: RunStatus, pending: Boolean, theme: AppTheme): Int = when {
        pending -> Color.rgb(80, 88, 96)
        status == RunStatus.RUNNING || status == RunStatus.DEGRADED -> themeAccent(theme)
        status == RunStatus.STOPPED -> Color.rgb(255, 95, 109)
        else -> Color.rgb(80, 88, 96)
    }

    private fun actionColor(status: RunStatus, pending: Boolean, canToggle: Boolean, theme: AppTheme): Int = when {
        pending || !canToggle -> Color.rgb(107, 120, 132)
        status == RunStatus.RUNNING || status == RunStatus.DEGRADED -> statusColor(status, false, theme)
        else -> Color.rgb(255, 95, 109)
    }

    private fun colorForActive(activeCount: Int, theme: AppTheme): Int =
        if (activeCount > 0) themeAccent(theme) else Color.rgb(107, 120, 132)

    private fun themeAccent(theme: AppTheme): Int {
        val rgb = theme.accent and 0x00ffffffL
        return Color.rgb(
            ((rgb ushr 16) and 0xffL).toInt(),
            ((rgb ushr 8) and 0xffL).toInt(),
            (rgb and 0xffL).toInt()
        )
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), red, green, blue)

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}

private data class WidgetRowIds(
    val container: Int,
    val indicator: Int,
    val name: Int,
    val meta: Int,
    val action: Int
)
