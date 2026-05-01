package com.litman.servicecontrol.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.litman.servicecontrol.R
import com.litman.servicecontrol.model.ResourceImpact
import com.litman.servicecontrol.model.RunStatus
import com.litman.servicecontrol.model.ServiceManager
import com.litman.servicecontrol.model.ServiceType
import com.litman.servicecontrol.model.Themes

private const val STATUS_TAG = "ServiceCtrl"

object ServiceStatusWidget {
    fun update(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val appContext = context.applicationContext
        val serviceManager = ServiceManager(appContext)
        val settings = serviceManager.getWidgetSettings()
        val theme = Themes.find(settings.theme)
        val services = serviceManager.getSavedServices()
            .filter { it.isEnabledOnWidget && (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID) }
        val runtimes = serviceManager.getCachedStatuses()
        val stats = WidgetStatsStore.load(appContext)

        val onlineCount = services.count {
            val status = runtimes[it.id]?.status
            status == RunStatus.RUNNING || status == RunStatus.DEGRADED
        }
        val pendingCount = services.count { serviceManager.getPendingState(it.id) != null }
        val totalMemory = services.sumOf { (stats[it.id]?.memoryMb ?: 0f).toDouble() }.toFloat()
        val topImpact = services
            .map { stats[it.id]?.impact ?: ResourceImpact.UNKNOWN }
            .maxByOrNull { impactRank(it) } ?: ResourceImpact.UNKNOWN
        val freshestCheck = services.maxOfOrNull { runtimes[it.id]?.checkedAt ?: 0L } ?: 0L

        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(appContext.packageName, R.layout.service_status_widget)
            views.setInt(R.id.status_widget_root, "setBackgroundColor", Color.argb(settings.opacity.coerceIn(0, 255), 8, 10, 13))
            views.setTextViewText(R.id.status_widget_title, "AT A GLANCE")
            views.setTextViewText(R.id.status_widget_online, "$onlineCount/${services.size} online")
            views.setTextViewText(R.id.status_widget_memory, formatMemory(totalMemory))
            views.setTextViewText(R.id.status_widget_impact, impactLabel(topImpact))
            views.setTextViewText(R.id.status_widget_pending, if (pendingCount > 0) "$pendingCount pending" else "stable")
            views.setTextViewText(R.id.status_widget_checked, "chk ${formatCheckedAge(freshestCheck)}")
            views.setTextColor(R.id.status_widget_online, themeAccent(theme))
            views.setTextColor(R.id.status_widget_memory, Color.rgb(244, 247, 250))
            views.setTextColor(R.id.status_widget_impact, impactColor(topImpact))
            views.setTextColor(R.id.status_widget_pending, if (pendingCount > 0) Color.rgb(255, 184, 77) else Color.rgb(107, 120, 132))
            views.setTextColor(R.id.status_widget_checked, Color.rgb(107, 120, 132))
            views.setOnClickPendingIntent(R.id.status_widget_refresh, WidgetIntents.refresh(appContext, ServiceStatusWidgetReceiver::class.java, appWidgetId))
            if (pendingCount == 0) {
                views.setViewVisibility(R.id.status_widget_pending, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.status_widget_pending, View.VISIBLE)
            }
            manager.updateAppWidget(appWidgetId, views)
        }

        Log.d(STATUS_TAG, "[ServiceCtrl] Status widget updated ids=${appWidgetIds.joinToString(",")}")
    }

    private fun impactRank(impact: ResourceImpact): Int = when (impact) {
        ResourceImpact.HIGH -> 3
        ResourceImpact.MEDIUM -> 2
        ResourceImpact.LOW -> 1
        ResourceImpact.UNKNOWN -> 0
    }

    private fun themeAccent(theme: com.litman.servicecontrol.model.AppTheme): Int {
        val rgb = theme.accent and 0x00ffffffL
        return Color.rgb(
            ((rgb ushr 16) and 0xffL).toInt(),
            ((rgb ushr 8) and 0xffL).toInt(),
            (rgb and 0xffL).toInt()
        )
    }
}
