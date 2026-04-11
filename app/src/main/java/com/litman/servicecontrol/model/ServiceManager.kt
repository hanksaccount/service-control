package com.litman.servicecontrol.model

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "ServiceCtrl"

// ── Domain models ────────────────────────────────────────────────────────────

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
    var isMuted: Boolean = false,
    var isManuallyStopped: Boolean = false
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
    val canStop: Boolean = true
)

// ── Widget settings ──────────────────────────────────────────────────────────

data class WidgetSettings(
    val nameSize: Float     = 13f,
    val metaSize: Float     = 9f,
    val padding: Float      = 12f,
    val opacity: Int        = 230,
    val cornerRadius: Float = 12f,
    val fontStyle: String   = "SANS",    // SANS | MONO
    val accentColor: String = "GREEN",   // GREEN | CYAN | AMBER
    val showMemory: Boolean  = true,
    val showColumnHeaders: Boolean = true
)

// ── Template registry ────────────────────────────────────────────────────────

object TemplateRegistry {
    private val templates = listOf(
        ServiceTemplate(
            name = "fuel-intel", displayName = "fuel-intel",
            type = ServiceType.WEB_PANEL, defaultPort = 5210,
            group = ServiceGroup.PANELS, checkMode = StatusCheckMode.PORT,
            showInWidget = true, canOpen = true, openUrl = "http://127.0.0.1:5210"
        ),
        ServiceTemplate(
            name = "elpris", displayName = "elpris",
            type = ServiceType.HYBRID, defaultPort = 5100,
            group = ServiceGroup.PANELS, checkMode = StatusCheckMode.PORT,
            showInWidget = true, canOpen = true, openUrl = "http://127.0.0.1:5100"
        ),
        ServiceTemplate(
            name = "dashboard", displayName = "dashboard",
            type = ServiceType.HYBRID, defaultPort = 5000,
            group = ServiceGroup.PANELS, checkMode = StatusCheckMode.PORT,
            showInWidget = true, canOpen = true, openUrl = "http://127.0.0.1:5000"
        ),
        ServiceTemplate(
            name = "autosort", displayName = "autosort",
            type = ServiceType.WEB_PANEL, defaultPort = 5300,
            group = ServiceGroup.PANELS, checkMode = StatusCheckMode.PORT,
            showInWidget = true, canOpen = true, openUrl = "http://127.0.0.1:5300"
        ),
        ServiceTemplate(
            name = "runfull", displayName = "Kör alla",
            type = ServiceType.ACTION_SCRIPT, defaultPort = null,
            group = ServiceGroup.ACTIONS, checkMode = StatusCheckMode.ACTION,
            canStop = false
        ),
        ServiceTemplate(
            name = "stopall", displayName = "Stoppa alla",
            type = ServiceType.ACTION_SCRIPT, defaultPort = null,
            group = ServiceGroup.ACTIONS, checkMode = StatusCheckMode.ACTION,
            canStop = false
        )
    )
    fun find(name: String) = templates.find { it.name == name }
}

// ── Service manager ──────────────────────────────────────────────────────────

class ServiceManager(val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("services_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Bump this when buildDefaultServices() changes port/path/mode config.
    private val SERVICES_VERSION = 3

    // ── Settings ─────────────────────────────────────────────────────────────

    fun getWidgetSettings(): WidgetSettings {
        val json = prefs.getString("widget_settings", null) ?: run {
            Log.d(TAG, "getWidgetSettings: no saved settings, returning defaults")
            return WidgetSettings()
        }
        return try {
            val d   = WidgetSettings()
            val obj = JsonParser.parseString(json).asJsonObject
            val s = WidgetSettings(
                nameSize          = if (obj.has("nameSize"))          obj["nameSize"].asFloat          else d.nameSize,
                metaSize          = if (obj.has("metaSize"))          obj["metaSize"].asFloat          else d.metaSize,
                padding           = if (obj.has("padding"))           obj["padding"].asFloat           else d.padding,
                opacity           = if (obj.has("opacity"))           obj["opacity"].asInt             else d.opacity,
                cornerRadius      = if (obj.has("cornerRadius"))      obj["cornerRadius"].asFloat      else d.cornerRadius,
                fontStyle         = if (obj.has("fontStyle"))         obj["fontStyle"].asString        else d.fontStyle,
                accentColor       = if (obj.has("accentColor"))       obj["accentColor"].asString      else d.accentColor,
                showMemory        = if (obj.has("showMemory"))        obj["showMemory"].asBoolean      else d.showMemory,
                showColumnHeaders = if (obj.has("showColumnHeaders")) obj["showColumnHeaders"].asBoolean else d.showColumnHeaders
            )
            Log.d(TAG, "getWidgetSettings: opacity=${s.opacity} font=${s.fontStyle} accent=${s.accentColor} nameSize=${s.nameSize}")
            s
        } catch (e: Exception) {
            Log.e(TAG, "getWidgetSettings: parse error, returning defaults", e)
            WidgetSettings()
        }
    }

    fun saveWidgetSettings(settings: WidgetSettings) {
        val json = gson.toJson(settings)
        // commit() is synchronous — guarantees the value is readable before we push the widget
        prefs.edit().putString("widget_settings", json).commit()
        Log.d(TAG, "saveWidgetSettings: opacity=${settings.opacity} font=${settings.fontStyle} accent=${settings.accentColor}")
    }

    // ── Pending action tracking ───────────────────────────────────────────────
    // Used by both the app and the widget to show "pending" visual state
    // while a start/stop command is in flight.

    fun markPending(serviceId: String) {
        val set = prefs.getStringSet("pending_actions", emptySet())!!.toMutableSet()
        set.add(serviceId)
        prefs.edit().putStringSet("pending_actions", set).commit()
        Log.d(TAG, "markPending: $serviceId")
    }

    fun clearPending(serviceId: String) {
        val set = prefs.getStringSet("pending_actions", emptySet())!!.toMutableSet()
        set.remove(serviceId)
        prefs.edit().putStringSet("pending_actions", set).commit()
        Log.d(TAG, "clearPending: $serviceId")
    }

    fun isPending(serviceId: String): Boolean =
        prefs.getStringSet("pending_actions", emptySet())!!.contains(serviceId)

    // ── Service list ──────────────────────────────────────────────────────────

    fun getSavedServices(): List<ServiceItem> {
        val json = prefs.getString("services_list", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ServiceItem>>() {}.type
            gson.fromJson<List<ServiceItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getSavedServices: parse error", e)
            emptyList()
        }
    }

    fun saveServices(services: List<ServiceItem>) {
        prefs.edit().putString("services_list", gson.toJson(services)).apply()
    }

    /**
     * Version-controlled seed.
     * Updates port/path/mode config while preserving user prefs (isMuted, isEnabledOnWidget).
     * Runs on every launch but only re-seeds when SERVICES_VERSION bumps.
     */
    fun ensureServiceConfig() {
        val savedVersion = prefs.getInt("services_config_version", 0)
        val existing     = getSavedServices()
        Log.d(TAG, "ensureServiceConfig: savedVersion=$savedVersion SERVICES_VERSION=$SERVICES_VERSION services=${existing.size}")

        if (existing.isNotEmpty() && savedVersion >= SERVICES_VERSION) return

        val existingById = existing.associateBy { it.id }
        val updated = buildDefaultServices().map { default ->
            val current = existingById[default.id]
            if (current != null) {
                default.copy(isMuted = current.isMuted, isEnabledOnWidget = current.isEnabledOnWidget, isManuallyStopped = current.isManuallyStopped)
            } else {
                default
            }
        }
        saveServices(updated)
        prefs.edit().putInt("services_config_version", SERVICES_VERSION).apply()
        Log.d(TAG, "ensureServiceConfig: migrated to version $SERVICES_VERSION (${updated.size} services)")
    }

    private fun buildDefaultServices(): List<ServiceItem> {
        val base = "/data/data/com.termux/files/home/.shortcuts"
        return listOf(
            ServiceItem(
                id = "fuel-intel", name = "fuel-intel", displayName = "fuel-intel",
                port = 5210,
                scriptPath = "$base/fuel.sh",
                isEnabledOnWidget = true,
                type = ServiceType.WEB_PANEL, group = ServiceGroup.PANELS,
                checkMode = StatusCheckMode.PORT
            ),
            ServiceItem(
                id = "elpris", name = "elpris", displayName = "elpris",
                port = 5100,
                scriptPath = "$base/elpris.sh",
                isEnabledOnWidget = true,
                type = ServiceType.HYBRID, group = ServiceGroup.PANELS,
                checkMode = StatusCheckMode.PORT
            ),
            ServiceItem(
                id = "dashboard", name = "dashboard", displayName = "dashboard",
                port = 5000,
                scriptPath = "$base/dashboard.sh",
                isEnabledOnWidget = true,
                type = ServiceType.HYBRID, group = ServiceGroup.PANELS,
                checkMode = StatusCheckMode.PORT
            ),
            ServiceItem(
                id = "autosort", name = "autosort", displayName = "autosort",
                port = 5300,
                scriptPath = "$base/autosort.sh",
                isEnabledOnWidget = true,
                type = ServiceType.WEB_PANEL, group = ServiceGroup.PANELS,
                checkMode = StatusCheckMode.PORT
            ),
            ServiceItem(
                id = "runfull", name = "runfull", displayName = "Kör alla",
                port = null,
                scriptPath = "$base/runfull.sh",
                isEnabledOnWidget = false,
                type = ServiceType.ACTION_SCRIPT, group = ServiceGroup.ACTIONS,
                checkMode = StatusCheckMode.ACTION, canStop = false
            )
        )
    }

    // ── Status checks ─────────────────────────────────────────────────────────

    suspend fun checkStatus(item: ServiceItem): ServiceRuntime = when (item.checkMode) {
        StatusCheckMode.ACTION  -> ServiceRuntime.UNKNOWN
        StatusCheckMode.PORT    -> checkTcpPort(item.port)
        StatusCheckMode.PROCESS -> checkProcess(item)
    }

    private suspend fun checkTcpPort(port: Int?): ServiceRuntime {
        if (port == null) return ServiceRuntime.NO_PORT
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                }
                Log.d(TAG, "checkTcpPort: port=$port → RUNNING")
                ServiceRuntime(RunStatus.RUNNING)
            } catch (e: Exception) {
                Log.d(TAG, "checkTcpPort: port=$port → STOPPED (${e.javaClass.simpleName})")
                ServiceRuntime(RunStatus.STOPPED)
            }
        }
    }

    private suspend fun checkProcess(item: ServiceItem): ServiceRuntime {
        val match = item.processMatch
        if (!match.isNullOrBlank()) {
            val result = withContext(Dispatchers.IO) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", match))
                    if (proc.waitFor() == 0) RunStatus.ACTIVE else null
                } catch (e: Exception) {
                    Log.w(TAG, "checkProcess: pgrep failed for '$match' (${e.javaClass.simpleName})")
                    null
                }
            }
            if (result != null) {
                Log.d(TAG, "checkProcess: '$match' → $result")
                return ServiceRuntime(result)
            }
        }
        // Fallback to port check
        if (item.port != null) return checkTcpPort(item.port)
        return ServiceRuntime.UNKNOWN
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun runTermuxScript(scriptPath: String) {
        Log.d(TAG, "runTermuxScript: $scriptPath")
        sendTermuxCommand(arrayOf(scriptPath))
    }

    fun stopService(item: ServiceItem) {
        Log.d(TAG, "stopService: ${item.label} mode=${item.checkMode} port=${item.port}")
        
        // 1. Create standard STOP flag to tell Termux background loops to exit
        val flagName = item.name.replace('-', '_').uppercase()
        val flagCmd = "touch /data/data/com.termux/files/home/STOP_$flagName"

        // 2. Kill the process/port
        val killCmd = when {
            item.port != null ->
                "fuser -k ${item.port}/tcp 2>/dev/null; pkill -f '${item.name}' 2>/dev/null; true"
            item.checkMode == StatusCheckMode.PROCESS && !item.processMatch.isNullOrBlank() ->
                "pkill -f \"${item.processMatch}\""
            else -> "true"
        }
        
        sendTermuxCommand(arrayOf("-c", "$flagCmd; $killCmd"))
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
        val services = getSavedServices().toMutableList()
        val idx = services.indexOfFirst { it.id == serviceId }
        if (idx == -1) {
            Log.w(TAG, "togglePower: service not found: $serviceId")
            return
        }
        val item = services[idx]
        Log.d(TAG, "togglePower: ${item.label} isRunning=$isRunning → ${if (isRunning) "STOP" else "START"}")
        
        if (isRunning) {
            services[idx] = item.copy(isManuallyStopped = true)
            saveServices(services)
            stopService(item)
        } else {
            services[idx] = item.copy(isManuallyStopped = false)
            saveServices(services)
            // Also remove the stop flag when manually starting
            val flagName = item.name.replace('-', '_').uppercase()
            sendTermuxCommand(arrayOf("-c", "rm -f /data/data/com.termux/files/home/STOP_$flagName; ${item.scriptPath}"))
        }
    }

    fun startAllEligible() {
        Log.d(TAG, "startAllEligible: Starting all non-manually stopped services")
        
        val services = getSavedServices()
        for (item in services) {
            if (item.type == ServiceType.ACTION_SCRIPT) continue
            if (item.isManuallyStopped) {
                Log.d(TAG, "startAllEligible: skipping ${item.label} (manually stopped)")
                continue
            }
            Log.d(TAG, "startAllEligible: starting ${item.label}")
            val flagName = item.name.replace('-', '_').uppercase()
            sendTermuxCommand(arrayOf("-c", "rm -f /data/data/com.termux/files/home/STOP_$flagName; ${item.scriptPath}"))
        }
        sendTermuxCommand(arrayOf("-c", "termux-toast \"Native app-start slutförd\""))
    }

    fun stopAll() {
        Log.d(TAG, "stopAll: Stopping all services")
        
        val services = getSavedServices().toMutableList()
        var changed = false
        for (i in services.indices) {
            val item = services[i]
            if (item.type == ServiceType.ACTION_SCRIPT) continue
            
            services[i] = item.copy(isManuallyStopped = true)
            changed = true
            stopService(item)
        }
        if (changed) saveServices(services)
        sendTermuxCommand(arrayOf("-c", "termux-toast \"Alla tjänster stoppade\""))
    }

    private fun sendTermuxCommand(args: Array<String>) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        Log.d(TAG, "sendTermuxCommand: bash ${args.joinToString(" ")}")
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "sendTermuxCommand: startService failed", e)
        }
    }

    // ── Parallel status check ─────────────────────────────────────────────────

    /** Check all services in parallel — much faster than sequential when some are offline. */
    suspend fun checkAllStatuses(services: List<ServiceItem>): Map<String, ServiceRuntime> =
        coroutineScope {
            val results = services.map { service ->
                async { service.id to checkStatus(service) }
            }.awaitAll().toMap()
            saveCachedStatuses(results)
            results
        }

    fun getCachedStatuses(): Map<String, ServiceRuntime> {
        val json = prefs.getString("cached_statuses", null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, ServiceRuntime>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveCachedStatuses(statuses: Map<String, ServiceRuntime>) {
        prefs.edit().putString("cached_statuses", gson.toJson(statuses)).apply()
    }

    // ── Uptime tracking ───────────────────────────────────────────────────────
    // Called when we confirm a service transitioned STOPPED → RUNNING.

    fun recordStartTime(serviceId: String) {
        prefs.edit().putLong("up_since_$serviceId", System.currentTimeMillis()).commit()
        Log.d(TAG, "recordStartTime: $serviceId")
    }

    fun clearStartTime(serviceId: String) {
        prefs.edit().remove("up_since_$serviceId").apply()
        Log.d(TAG, "clearStartTime: $serviceId")
    }

    fun getUptime(serviceId: String): String? {
        val since = prefs.getLong("up_since_$serviceId", 0L)
        if (since == 0L) return null
        val sec = (System.currentTimeMillis() - since) / 1000L
        return when {
            sec < 60   -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            else       -> "${sec / 3600}h ${(sec % 3600) / 60}m"
        }
    }

    /** Scan .sh scripts and merge into saved list. */
    fun parseScanResult(stdout: String): List<ServiceItem> {
        val lines   = stdout.lines().map { it.trim() }.filter { it.endsWith(".sh") }
        val current = getSavedServices().toMutableList()
        lines.forEach { path ->
            val name     = path.substringAfterLast("/").removeSuffix(".sh")
            val template = TemplateRegistry.find(name)
            val existing = current.indexOfFirst { it.scriptPath == path }
            if (existing == -1) {
                current.add(if (template != null) ServiceItem(
                    id = path.hashCode().toString(), name = name,
                    displayName = template.displayName, port = template.defaultPort,
                    scriptPath = path, isEnabledOnWidget = template.showInWidget,
                    type = template.type, group = template.group,
                    checkMode = template.checkMode, processMatch = template.processMatch,
                    canOpen = template.canOpen, openUrl = template.openUrl,
                    canStart = template.canStart, canStop = template.canStop
                ) else ServiceItem(id = path.hashCode().toString(), name = name, scriptPath = path))
            } else if (template != null) {
                val e = current[existing]
                current[existing] = e.copy(
                    displayName = template.displayName, port = template.defaultPort,
                    isEnabledOnWidget = template.showInWidget, type = template.type,
                    group = template.group, checkMode = template.checkMode,
                    processMatch = template.processMatch, canOpen = template.canOpen,
                    openUrl = template.openUrl, canStart = template.canStart, canStop = template.canStop
                )
            }
        }
        saveServices(current)
        return current
    }
}
