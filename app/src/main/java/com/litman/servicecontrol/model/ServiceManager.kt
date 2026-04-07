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
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*

data class ServiceItem(
    val id: String,
    val name: String,
    var displayName: String = "",
    var port: Int? = null,
    var icon: String = "📄",
    val scriptPath: String,
    var isEnabledOnWidget: Boolean = false,
    var type: ServiceType = ServiceType.UNKNOWN,
    var impactProfile: ImpactLevel = ImpactLevel.IDLE,
    var group: ServiceGroup = ServiceGroup.UNKNOWN,
    var canOpen: Boolean = false,
    var openUrl: String? = null,
    var canStart: Boolean = true,
    var canStop: Boolean = true
) {
    val label: String get() = if (displayName.isNotBlank()) displayName else name
}

data class ServiceTemplate(
    val name: String,
    val displayName: String,
    val type: ServiceType,
    val defaultPort: Int? = null,
    val impact: ImpactLevel,
    val group: ServiceGroup,
    val showInWidget: Boolean = false,
    val canOpen: Boolean = false,
    val openUrl: String? = null,
    val canStart: Boolean = true,
    val canStop: Boolean = true
)

object TemplateRegistry {
    private val templates = listOf(
        ServiceTemplate("autosort", "AutoSort", ServiceType.WEB_PANEL, 5300, ImpactLevel.MEDIUM, ServiceGroup.PANELS, true, true, "http://127.0.0.1:5300"),
        ServiceTemplate("dashboard", "Dashboard", ServiceType.HYBRID, null, ImpactLevel.MEDIUM, ServiceGroup.PANELS, true, true),
        ServiceTemplate("elpris", "Elpris", ServiceType.HYBRID, 5100, ImpactLevel.LOW, ServiceGroup.PANELS, true, true, "http://127.0.0.1:5100"),
        ServiceTemplate("fuel", "Fuel", ServiceType.WEB_PANEL, null, ImpactLevel.MEDIUM, ServiceGroup.PANELS, false, true),
        ServiceTemplate("runfull", "Kör alla", ServiceType.ACTION_SCRIPT, null, ImpactLevel.BURST, ServiceGroup.ACTIONS, false, false, canStop = false),
        ServiceTemplate("stopall", "Stoppa alla", ServiceType.ACTION_SCRIPT, null, ImpactLevel.BURST, ServiceGroup.ACTIONS, false, false, canStop = false)
    )

    fun find(name: String) = templates.find { it.name == name }
}

data class TermuxResult(val command: String, val error: String? = null)

class ServiceManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("services_config", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val client = HttpClient(Android) {
        engine {
            connectTimeout = 1000
            socketTimeout = 1000
        }
    }

    private val logFile get() = context.getFileStreamPath("debug.log")

    fun debugLog(msg: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logFile.appendText("[$ts] $msg\n")
        } catch (_: Exception) {}
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

    fun triggerDiscoveryScan(pendingIntent: PendingIntent): TermuxResult {
        val command = "ls /data/data/com.termux/files/home/.shortcuts/*.sh 2>&1"
        return startTermuxCommand(command, pendingIntent)
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
                        impactProfile = template.impact,
                        group = template.group,
                        canOpen = template.canOpen,
                        openUrl = template.openUrl,
                        canStart = template.canStart,
                        canStop = template.canStop
                    )
                } else {
                    ServiceItem(id = path.hashCode().toString(), name = name, scriptPath = path)
                }
                current.add(item)
            } else if (template != null && current[existingIndex].type == ServiceType.UNKNOWN) {
                val existing = current[existingIndex]
                current[existingIndex] = existing.copy(
                    displayName = template.displayName,
                    port = template.defaultPort,
                    isEnabledOnWidget = template.showInWidget,
                    type = template.type,
                    impactProfile = template.impact,
                    group = template.group,
                    canOpen = template.canOpen,
                    openUrl = template.openUrl,
                    canStart = template.canStart,
                    canStop = template.canStop
                )
            }
        }
        saveServices(current)
        return current
    }

    suspend fun checkStatusWithLoad(item: ServiceItem): ServiceRuntime {
        if (item.type == ServiceType.ACTION_SCRIPT) return ServiceRuntime.UNKNOWN
        
        val port = item.port
        if (port == null) {
            return if (item.type == ServiceType.NOTIFIER) ServiceRuntime(RunStatus.UNKNOWN, item.impactProfile)
            else ServiceRuntime.NO_PORT
        }

        return try {
            val t0 = System.currentTimeMillis()
            val response = try {
                withTimeoutOrNull(2000) {
                    client.get("http://127.0.0.1:$port")
                }
            } catch (e: Exception) {
                null
            }
            val ms = System.currentTimeMillis() - t0
            
            if (response == null) {
                ServiceRuntime(RunStatus.STOPPED, ImpactLevel.IDLE, null)
            } else {
                val ok = response.status.value in 200..499
                ServiceRuntime(
                    status = if (ok) RunStatus.RUNNING else RunStatus.STOPPED,
                    impact = if (ok) item.impactProfile else ImpactLevel.IDLE,
                    responseMs = if (ok) ms else null
                )
            }
        } catch (e: Exception) {
            ServiceRuntime(RunStatus.UNKNOWN, ImpactLevel.IDLE, null)
        }
    }

    fun runTermuxScript(scriptPath: String) {
        sendTermuxCommand(arrayOf(scriptPath))
    }

    fun stopService(port: Int) {
        sendTermuxCommand(arrayOf("-c", "fuser -k $port/tcp"))
    }

    private fun startTermuxCommand(command: String, pendingIntent: PendingIntent): TermuxResult {
        val intent = Intent("com.termux.RUN_COMMAND")
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        return try {
            context.startService(intent)
            TermuxResult(command)
        } catch (e: Exception) {
            TermuxResult(command, "${e.javaClass.simpleName}: ${e.message}")
        }
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
