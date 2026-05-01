package com.litman.servicecontrol.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

private const val TAG = "ServiceCtrl"

object WidgetUpdater {
    fun appWidgetIds(context: Context): IntArray {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, ServiceWidgetReceiver::class.java)
        return manager.getAppWidgetIds(component)
    }

    fun statusWidgetIds(context: Context): IntArray {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, ServiceStatusWidgetReceiver::class.java)
        return manager.getAppWidgetIds(component)
    }

    fun monitorWidgetIds(context: Context): IntArray {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, ServiceMonitorWidgetReceiver::class.java)
        return manager.getAppWidgetIds(component)
    }

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val classicIds = appWidgetIds(appContext)
        val statusIds = statusWidgetIds(appContext)
        val monitorIds = monitorWidgetIds(appContext)

        Log.d(TAG, "[ServiceCtrl] WidgetUpdater: classic=${classicIds.size} status=${statusIds.size} monitor=${monitorIds.size}")
        ServiceWidget.update(appContext, manager, classicIds)
        ServiceStatusWidget.update(appContext, manager, statusIds)
        ServiceMonitorWidget.update(appContext, manager, monitorIds)
    }
}
