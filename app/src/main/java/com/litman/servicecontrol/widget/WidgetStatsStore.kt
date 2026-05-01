package com.litman.servicecontrol.widget

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.litman.servicecontrol.model.ServiceStats

object WidgetStatsStore {
    private const val PREFS = "services_config"
    private const val KEY = "cached_stats"
    private val gson = Gson()

    fun save(context: Context, stats: Map<String, ServiceStats>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, gson.toJson(stats))
            .apply()
    }

    fun load(context: Context): Map<String, ServiceStats> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, ServiceStats>>() {}.type
            gson.fromJson<Map<String, ServiceStats>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
