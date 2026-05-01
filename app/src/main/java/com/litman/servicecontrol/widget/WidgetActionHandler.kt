package com.litman.servicecontrol.widget

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object WidgetActionHandler {
    fun handle(receiver: android.appwidget.AppWidgetProvider, context: Context, intent: Intent) {
        val pending = receiver.goAsync()
        val action = intent.action
        val serviceId = intent.getStringExtra(ServiceWidget.EXTRA_SERVICE_ID)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ServiceWidget.ACTION_REFRESH -> ServiceWidget.refreshStatuses(context)
                    ServiceWidget.ACTION_TOGGLE -> if (serviceId != null) ServiceWidget.toggle(context, serviceId)
                }
            } catch (t: Throwable) {
                android.util.Log.e("ServiceCtrl", "widget action failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
