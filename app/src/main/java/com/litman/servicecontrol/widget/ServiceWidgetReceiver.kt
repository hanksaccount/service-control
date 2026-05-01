package com.litman.servicecontrol.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            ServiceWidget.ACTION_REFRESH, ServiceWidget.ACTION_TOGGLE -> handleWidgetAction(context, intent)
            else -> super.onReceive(context, intent)
        }
    }

    private fun handleWidgetAction(context: Context, intent: Intent) {
        val pending = goAsync()
        val action = intent.action
        val serviceId = intent.getStringExtra(ServiceWidget.EXTRA_SERVICE_ID)
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ServiceWidget.ACTION_REFRESH -> ServiceWidget.refreshStatuses(context)
                    ServiceWidget.ACTION_TOGGLE -> {
                        if (serviceId != null) {
                            ServiceWidget.toggle(context, serviceId)
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ServiceCtrl", "handleWidgetAction failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
