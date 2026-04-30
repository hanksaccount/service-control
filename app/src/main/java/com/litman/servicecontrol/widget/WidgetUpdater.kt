package com.litman.servicecontrol.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager

private const val TAG = "ServiceCtrl"

object WidgetUpdater {
    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        val widget = ServiceWidget()
        val manager = GlanceAppWidgetManager(appContext)
        val glanceIds = manager.getGlanceIds(ServiceWidget::class.java)

        Log.d(TAG, "[ServiceCtrl] WidgetUpdater: refreshing ${glanceIds.size} Glance widgets")
        glanceIds.forEach { id -> widget.update(appContext, id) }

        // Some launchers are conservative with Glance redraws after preference changes.
        val appWidgetManager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, ServiceWidgetReceiver::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(component)
        if (appWidgetIds.isNotEmpty()) {
            appContext.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    setComponent(component)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                }
            )
        }
    }
}
