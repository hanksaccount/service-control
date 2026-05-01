package com.litman.servicecontrol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.litman.servicecontrol.model.ServiceItem

object WidgetIntents {
    fun refresh(context: Context, receiver: Class<*>, appWidgetId: Int): PendingIntent =
        broadcast(context, receiver, ServiceWidget.ACTION_REFRESH, appWidgetId, null)

    fun toggle(context: Context, receiver: Class<*>, appWidgetId: Int, serviceId: String): PendingIntent =
        broadcast(context, receiver, ServiceWidget.ACTION_TOGGLE, appWidgetId, serviceId)

    fun open(context: Context, service: ServiceItem): PendingIntent? {
        val url = service.openUrl ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return PendingIntent.getActivity(context, service.id.hashCode(), intent, pendingFlags())
    }

    private fun broadcast(
        context: Context,
        receiver: Class<*>,
        action: String,
        appWidgetId: Int,
        serviceId: String?
    ): PendingIntent {
        val intent = Intent(context, receiver).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            serviceId?.let { putExtra(ServiceWidget.EXTRA_SERVICE_ID, it) }
        }
        val requestCode = (receiver.name + action + appWidgetId + (serviceId ?: "")).hashCode()
        return PendingIntent.getBroadcast(context, requestCode, intent, pendingFlags())
    }

    private fun pendingFlags(): Int {
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.FLAG_UPDATE_CURRENT or immutable
    }
}
