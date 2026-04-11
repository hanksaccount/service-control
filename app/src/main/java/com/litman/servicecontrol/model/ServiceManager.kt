package com.litman.servicecontrol.model

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*

data class ServiceItem(
    val id: String,
    val name: String,
    var displayName: String = "",
    var port: Int? = null,
    val scriptPath: String,
    var isEnabledOnWidget: Boolean = false,
    var type: ServiceType = ServiceType.UNKNOWN,
    var group: ServiceGroup = ServiceGroup.UNKNOWN,
    var checkMode: StatusCheckMode = StatusCheckMode.PORT,
    var processMatch: String? = null,
    var canOpen: Boolean = false,
    var openUrl: String? = null,
    var canStart: Boolean = true,
    var canStop: Boolean = true,
    var isMuted: Boolean = false
) {
    val label: String get() = if (displayName.isNotBlank()) displayName else name
}

data class ServiceTemplate(
    val name: String,
    val displayName: String,
    val type: ServiceType,
    val defaultPort: Int? = null,
    val group: ServiceGroup,
    val checkMode: StatusCheckMode = StatusCheckMode.PORT,
    val processMatch: String? = null,
    val showInWidget: Boolean = false,
    val canOpen: Boolean = false,
    val openUrl: String? = null,
    val canStart: Boolean = true,
    val canStop: Boolean = true,
    val isMuted: Boolean = false
)

object TemplateRegistry {
    private val templates = listOf(
        ServiceTemplate("autosort", "AutoSort", ServiceType.WEB_PANEL, 5300, ServiceGroup.PANELS, StatusCheckMode.PORT, null, true, true, "http://127.0.0.1:5300"),
        ServiceTemplate("dashboard", "Dashboard", ServiceType.HYBRID, null, ServiceGroup.PANELS, StatusCheckMode.PROCESS, ".shortcuts/dashboard.sh", true, true),
        ServiceTemplate("elpris", "Elpris", ServiceType.HYBRID, 5100, ServiceGroup.PANELS, StatusCheckMode.PORT, null, true, true, "http://127.0.0.1:5100"),
        ServiceTemplate("fuel", "Fuel", ServiceType.WEB_PANEL, null, ServiceGroup.PANELS, StatusCheckMode.PROCESS, null, false, true), // Osäker -> null
        ServiceTemplate("runfull", "Kör alla", ServiceType.ACTION_SCRIPT, null, ServiceGroup.ACTIONS, StatusCheckMode.ACTION, null, false, false, canStop = false),
        ServiceTemplate("stopall", "Stoppa alla", ServiceType.ACTION_SCRIPT, null, ServiceGroup.ACTIONS, StatusCheckMode.ACTION, null, false, false, canStop = false)
    )

    fun find(name: String) = templates.find { it.name == name }
}

data class WidgetSettings(
    var nameSize: Float = 14f,
    var metaSize: Float = 10f,
    var padding: Float = 16f,
    var opacity: Int = 255,
    var cornerRadius: Float = 8f
)

class ServiceManager(val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("services_config", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val client = HttpClient(Android) {
        engine {
            connectTimeout = 1000
            socketTimeout = 1000
        }
    }

    fun getWidgetSettings(): WidgetSettings {
        val json = prefs.getString("widget_settings", "{}")
        return gson.fromJson(json, WidgetSettings::class.java) ?: WidgetSettings()
    }

    fun saveWidgetSettings(settings: WidgetSettings) {
        prefs.edit().putString("widget_settings", gson.toJson(settings)).apply()
    }

    fun getSavedServices(): List<ServiceItem> {
        val json = prefs.getString("services_list", "[]")
        val type = object : TypeToken<List<ServiceItem>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveServices(services: List<ServiceItem>) {
        val json = gson.toJson(services)
        prefs.edit().putString("services_list", json).apply()
    }

    fun parseScanResult(stdout: String): List<ServiceItem> {
        val lines = stdout.lines().map { it.trim() }.filter { it.endsWith(".sh") }
        val current = getSavedServices().toMutableList()

        lines.forEach { path ->
            val name = path.substringAfterLast("/").removeSuffix(".sh")
            val template = TemplateRegistry.find(name)
            val existingIndex = current.indexOfFirst { it.scriptPath == path }

            if (existingIndex == -1) {
                val item = if (template != null) {
                    ServiceItem(
                        id = path.hashCode().toString(),
                        name = name,
                        displayName = template.displayName,
                        port = template.defaultPort,
                        scriptPath = path,
                        isEnabledOnWidget = template.showInWidget,
                        type = template.type,
                        group = template.group,
                        checkMode = template.checkMode,
                        processMatch = template.processMatch,
                        canOpen = template.canOpen,
                        openUrl = template.openUrl,
                        canStart = template.canStart,
                        canStop = template.canStop,
                        isMuted = template.isMuted
                    )
                } else {
                    ServiceItem(id = path.hashCode().toString(), name = name, scriptPath = path)
                }
                current.add(item)
            } else if (template != null) {
                val existing = current[existingIndex]
                current[existingIndex] = existing.copy(
                    displayName = template.displayName,
                    port = template.defaultPort,
                    isEnabledOnWidget = template.showInWidget,
                    type = template.type,
                    group = template.group,
                    checkMode = template.checkMode,
                    processMatch = template.processMatch,
                    canOpen = template.canOpen,
                    openUrl = template.openUrl,
                    canStart = template.canStart,
                    canStop = template.canStop,
                    isMuted = template.isMuted
                )
            }
        }
        saveServices(current)
        return current
    }

    suspend fun checkStatus(item: ServiceItem): ServiceRuntime {
        return when (item.checkMode) {
            StatusCheckMode.ACTION -> ServiceRuntime.UNKNOWN
            StatusCheckMode.PORT -> checkPortStatus(item.port)
            StatusCheckMode.PROCESS -> checkProcessStatus(item.processMatch)
        }
    }

    private suspend fun checkPortStatus(port: Int?): ServiceRuntime {
        if (port == null) return ServiceRuntime.NO_PORT
        return try {
            val response = try {
                withTimeoutOrNull(2000) { client.get("http://127.0.0.1:$port") }
            } catch (e: Exception) { null }
            
            if (response != null && response.status.value in 200..499) {
                ServiceRuntime(RunStatus.RUNNING)
            } else {
                ServiceRuntime(RunStatus.STOPPED)
            }
        } catch (e: Exception) {
            ServiceRuntime(RunStatus.UNKNOWN)
        }
    }

    private suspend fun checkProcessStatus(match: String?): ServiceRuntime {
        if (match.isNullOrBlank()) return ServiceRuntime.NO_PORT
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", match))
                val exitValue = process.waitFor()
                if (exitValue == 0) ServiceRuntime(RunStatus.ACTIVE)
                else ServiceRuntime(RunStatus.STOPPED)
            } catch (e: Exception) {
                ServiceRuntime(RunStatus.UNKNOWN)
            }
        }
    }

    fun runTermuxScript(scriptPath: String) {
        sendTermuxCommand(arrayOf(scriptPath))
    }

    fun stopService(item: ServiceItem) {
        if (item.checkMode == StatusCheckMode.PORT && item.port != null) {
            sendTermuxCommand(arrayOf("-c", "fuser -k ${item.port}/tcp"))
        } else if (item.checkMode == StatusCheckMode.PROCESS && !item.processMatch.isNullOrBlank()) {
            sendTermuxCommand(arrayOf("-c", "pkill -f \"${item.processMatch}\""))
        }
    }

    fun toggleMute(serviceId: String) {
        val services = getSavedServices().toMutableList()
        val idx = services.indexOfFirst { it.id == serviceId }
        if (idx != -1) {
            services[idx] = services[idx].copy(isMuted = !services[idx].isMuted)
            saveServices(services)
        }
    }

    fun togglePower(serviceId: String, isRunning: Boolean) {
        val services = getSavedServices()
        val item = services.find { it.id == serviceId } ?: return
        if (isRunning) stopService(item) else runTermuxScript(item.scriptPath)
    }

    private fun sendTermuxCommand(args: Array<String>) {
        val intent = Intent("com.termux.RUN_COMMAND")
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        try { context.startService(intent) } catch (_: Exception) {}
    }
}
