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

    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        val ids = appWidgetIds(appContext)
        Log.d(TAG, "[ServiceCtrl] WidgetUpdater: refreshing classic widgets count=${ids.size}")
        ServiceWidget.update(appContext, AppWidgetManager.getInstance(appContext), ids)
    }
}
