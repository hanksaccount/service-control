package com.litman.servicecontrol.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class ServiceWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            ServiceWidget.update(context, appWidgetManager, appWidgetIds)
        } catch (t: Throwable) {
            android.util.Log.e("ServiceCtrl", "widget onUpdate failed", t)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ServiceWidget.ACTION_REFRESH, ServiceWidget.ACTION_TOGGLE -> WidgetActionHandler.handle(this, context, intent)
            else -> super.onReceive(context, intent)
        }
    }
}
