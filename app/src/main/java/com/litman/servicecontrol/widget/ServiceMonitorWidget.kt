package com.litman.servicecontrol.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.litman.servicecontrol.R
import com.litman.servicecontrol.model.RunStatus
import com.litman.servicecontrol.model.ServiceItem
import com.litman.servicecontrol.model.ServiceManager
import com.litman.servicecontrol.model.ServiceRuntime
import com.litman.servicecontrol.model.ServiceStats
import com.litman.servicecontrol.model.ServiceType

object ServiceMonitorWidget {
    fun update(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        val appContext = context.applicationContext
        val serviceManager = ServiceManager(appContext)
        val services = serviceManager.getSavedServices()
            .filter { it.isEnabledOnWidget && (it.type == ServiceType.WEB_PANEL || it.type == ServiceType.HYBRID) }
            .take(4)
        val runtimes = serviceManager.getCachedStatuses()
        val stats = WidgetStatsStore.load(appContext)
        val uptimes = services.associate { it.id to serviceManager.getUptime(it.id) }
        val heaviest = services.maxByOrNull { stats[it.id]?.memoryMb ?: 0f }
        val totalCpu = services.sumOf { (stats[it.id]?.cpuPercent ?: 0f).toDouble() }.toFloat()
        val totalMemory = services.sumOf { (stats[it.id]?.memoryMb ?: 0f).toDouble() }.toFloat()
        val checkedAt = services.maxOfOrNull { maxOf(runtimes[it.id]?.checkedAt ?: 0L, stats[it.id]?.checkedAt ?: 0L) } ?: 0L

        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(appContext.packageName, R.layout.service_monitor_widget)
            views.setInt(R.id.monitor_widget_root, "setBackgroundColor", Color.argb(serviceManager.getWidgetSettings().opacity.coerceIn(0, 255), 8, 10, 13))
            views.setTextViewText(R.id.monitor_widget_title, "SERVICE MONITOR")
            views.setTextViewText(R.id.monitor_widget_summary, "${formatOneDecimal(totalCpu)}% cpu  ·  ${formatMemory(totalMemory)}")
            views.setTextViewText(
                R.id.monitor_widget_footer,
                buildString {
                    append("chk ${formatCheckedAge(checkedAt)}")
                    if (heaviest != null) append("  ·  top ${heaviest.label}")
                }
            )
            views.setOnClickPendingIntent(R.id.monitor_widget_refresh, WidgetIntents.refresh(appContext, ServiceMonitorWidgetReceiver::class.java, appWidgetId))

            val rows = listOf(
                MonitorRowIds(R.id.monitor_row_1, R.id.monitor_row_1_indicator, R.id.monitor_row_1_name, R.id.monitor_row_1_meta, R.id.monitor_row_1_stats),
                MonitorRowIds(R.id.monitor_row_2, R.id.monitor_row_2_indicator, R.id.monitor_row_2_name, R.id.monitor_row_2_meta, R.id.monitor_row_2_stats),
                MonitorRowIds(R.id.monitor_row_3, R.id.monitor_row_3_indicator, R.id.monitor_row_3_name, R.id.monitor_row_3_meta, R.id.monitor_row_3_stats),
                MonitorRowIds(R.id.monitor_row_4, R.id.monitor_row_4_indicator, R.id.monitor_row_4_name, R.id.monitor_row_4_meta, R.id.monitor_row_4_stats)
            )

            rows.forEachIndexed { index, row ->
                val service = services.getOrNull(index)
                if (service == null) {
                    views.setViewVisibility(row.container, View.GONE)
                } else {
                    bindRow(
                        appContext,
                        appWidgetId,
                        views,
                        row,
                        service,
                        runtimes[service.id] ?: ServiceRuntime.UNKNOWN,
                        stats[service.id] ?: ServiceStats.EMPTY,
                        uptimes[service.id],
                        serviceManager
                    )
                }
            }

            manager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun bindRow(
        context: Context,
        appWidgetId: Int,
        views: RemoteViews,
        row: MonitorRowIds,
        service: ServiceItem,
        runtime: ServiceRuntime,
        stats: ServiceStats,
        uptime: String?,
        manager: ServiceManager
    ) {
        val pending = manager.getPendingState(service.id)
        val isPending = pending != null
        val statusText = statusSummary(runtime, isPending)
        val statText = if (stats.processCount == 0) {
            stats.detail.ifBlank { "no proc data" }
        } else {
            "${stats.processCount}p  ·  ${formatOneDecimal(stats.cpuPercent)}%  ·  ${formatMemory(stats.memoryMb)}"
        }

        views.setViewVisibility(row.container, View.VISIBLE)
        views.setTextViewText(row.name, service.label)
        views.setTextViewText(row.meta, buildString {
            append(statusText)
            uptime?.let {
                append("  ·  ")
                append(it)
            }
        })
        views.setTextViewText(row.stats, "${impactLabel(stats.impact)}  ·  $statText")
        views.setInt(row.indicator, "setBackgroundColor", statusColor(runtime.status, isPending))
        views.setTextColor(row.meta, if (isPending) Color.rgb(255, 184, 77) else Color.rgb(107, 120, 132))
        views.setTextColor(row.stats, impactColor(stats.impact))
        val open = if ((runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.DEGRADED) && service.canOpen) {
            WidgetIntents.open(context, service)
        } else null
        views.setOnClickPendingIntent(row.container, open ?: WidgetIntents.refresh(context, ServiceMonitorWidgetReceiver::class.java, appWidgetId))
    }

    private fun statusColor(status: RunStatus, isPending: Boolean): Int = when {
        isPending -> Color.rgb(255, 184, 77)
        status == RunStatus.RUNNING -> Color.rgb(73, 230, 138)
        status == RunStatus.DEGRADED -> Color.rgb(255, 184, 77)
        status == RunStatus.STOPPED -> Color.rgb(255, 95, 109)
        else -> Color.rgb(120, 132, 142)
    }
}

private data class MonitorRowIds(
    val container: Int,
    val indicator: Int,
    val name: Int,
    val meta: Int,
    val stats: Int
)
