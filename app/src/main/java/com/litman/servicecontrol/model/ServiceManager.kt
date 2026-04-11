package com.litman.servicecontrol.model

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

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

    // Version must be incremented whenever seed port/path data changes.
    private val SERVICES_VERSION = 3

    // ── Settings ─────────────────────────────────────────────────────────────

    fun getWidgetSettings(): WidgetSettings {
        val json = prefs.getString("widget_settings", null) ?: return WidgetSettings()
        return try {
            val d   = WidgetSettings()
            val obj = JsonParser.parseString(json).asJsonObject
            WidgetSettings(
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
        } catch (e: Exception) {
            WidgetSettings()
        }
    }

    fun saveWidgetSettings(settings: WidgetSettings) {
        prefs.edit().putString("widget_settings", gson.toJson(settings)).apply()
    }

    // ── Service list ──────────────────────────────────────────────────────────

    fun getSavedServices(): List<ServiceItem> {
        val json = prefs.getString("services_list", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ServiceItem>>() {}.type
            gson.fromJson<List<ServiceItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveServices(services: List<ServiceItem>) {
        prefs.edit().putString("services_list", gson.toJson(services)).apply()
    }

    /**
     * Ensures services are seeded with correct current config.
     * Runs on every launch but only touches config when the SERVICES_VERSION bumps.
     * User preferences (isMuted, isEnabledOnWidget) are preserved across migrations.
     */
    fun ensureServiceConfig() {
        val savedVersion = prefs.getInt("services_config_version", 0)
        val existing     = getSavedServices()

        if (existing.isNotEmpty() && savedVersion >= SERVICES_VERSION) return

        val existingById = existing.associateBy { it.id }
        val updated = buildDefaultServices().map { default ->
            val current = existingById[default.id]
            if (current != null) {
                // Migrate: keep user prefs, overwrite technical config
                default.copy(
                    isMuted          = current.isMuted,
                    isEnabledOnWidget = current.isEnabledOnWidget
                )
            } else {
                default
            }
        }

        saveServices(updated)
        prefs.edit().putInt("services_config_version", SERVICES_VERSION).apply()
    }

    private fun buildDefaultServices(): List<ServiceItem> {
        val base = "/data/data/com.termux/files/home/.shortcuts"
        return listOf(
            ServiceItem(
                id = "fuel-intel", name = "fuel-intel", displayName = "fuel-intel",
                port = 5210,
                scriptPath = "$base/fuel.sh",       // ← shortcut is fuel.sh not fuel-intel.sh
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
                port = 5000,                        // ← was PROCESS mode with no port; correct port is 5000
                scriptPath = "$base/dashboard.sh",
                isEnabledOnWidget = true,
                type = ServiceType.HYBRID, group = ServiceGroup.PANELS,
                checkMode = StatusCheckMode.PORT    // ← switched from PROCESS to PORT
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
    //
    // Primary: TCP socket connect to known port.
    //   - Works from Android sandbox (no HTTP policy, no SELinux block)
    //   - Fast: 1.5s timeout, result is deterministic
    //   - Reliable: confirms the port is bound and accepting
    //
    // Process check (pgrep) is kept as fallback for services without ports,
    // but is unreliable from within the Android app due to SELinux restrictions
    // on cross-UID /proc access. Prefer PORT mode whenever possible.

    suspend fun checkStatus(item: ServiceItem): ServiceRuntime = when (item.checkMode) {
        StatusCheckMode.ACTION  -> ServiceRuntime.UNKNOWN
        StatusCheckMode.PORT    -> checkTcpPort(item.port)
        StatusCheckMode.PROCESS -> checkProcess(item)
    }

    /** Raw TCP socket connect — the most reliable way to probe a local service port. */
    private suspend fun checkTcpPort(port: Int?): ServiceRuntime {
        if (port == null) return ServiceRuntime.NO_PORT
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 1500)
                }
                ServiceRuntime(RunStatus.RUNNING)
            } catch (e: Exception) {
                ServiceRuntime(RunStatus.STOPPED)
            }
        }
    }

    /**
     * Process check: tries pgrep first (works in Termux env), then falls back to
     * a port check if the service has a known port. If both fail → UNKNOWN.
     */
    private suspend fun checkProcess(item: ServiceItem): ServiceRuntime {
        val match = item.processMatch
        if (!match.isNullOrBlank()) {
            val pgrepResult = withContext(Dispatchers.IO) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", match))
                    if (proc.waitFor() == 0) RunStatus.ACTIVE else null
                } catch (e: Exception) {
                    null
                }
            }
            if (pgrepResult != null) return ServiceRuntime(pgrepResult)
        }
        // Fallback: if the service has a port, use TCP check
        if (item.port != null) return checkTcpPort(item.port)
        return ServiceRuntime.UNKNOWN
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun runTermuxScript(scriptPath: String) = sendTermuxCommand(arrayOf(scriptPath))

    fun stopService(item: ServiceItem) {
        when {
            item.checkMode == StatusCheckMode.PORT && item.port != null ->
                sendTermuxCommand(arrayOf("-c", "fuser -k ${item.port}/tcp"))
            item.checkMode == StatusCheckMode.PROCESS && !item.processMatch.isNullOrBlank() ->
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
        val item = getSavedServices().find { it.id == serviceId } ?: return
        if (isRunning) stopService(item) else runTermuxScript(item.scriptPath)
    }

    /** Merge scan results from .sh script paths into the saved list. */
    fun parseScanResult(stdout: String): List<ServiceItem> {
        val lines   = stdout.lines().map { it.trim() }.filter { it.endsWith(".sh") }
        val current = getSavedServices().toMutableList()

        lines.forEach { path ->
            val name     = path.substringAfterLast("/").removeSuffix(".sh")
            val template = TemplateRegistry.find(name)
            val existing = current.indexOfFirst { it.scriptPath == path }

            if (existing == -1) {
                current.add(if (template != null) {
                    ServiceItem(
                        id = path.hashCode().toString(), name = name,
                        displayName = template.displayName, port = template.defaultPort,
                        scriptPath = path, isEnabledOnWidget = template.showInWidget,
                        type = template.type, group = template.group,
                        checkMode = template.checkMode, processMatch = template.processMatch,
                        canOpen = template.canOpen, openUrl = template.openUrl,
                        canStart = template.canStart, canStop = template.canStop
                    )
                } else {
                    ServiceItem(id = path.hashCode().toString(), name = name, scriptPath = path)
                })
            } else if (template != null) {
                val e = current[existing]
                current[existing] = e.copy(
                    displayName = template.displayName, port = template.defaultPort,
                    isEnabledOnWidget = template.showInWidget, type = template.type,
                    group = template.group, checkMode = template.checkMode,
                    processMatch = template.processMatch, canOpen = template.canOpen,
                    openUrl = template.openUrl, canStart = template.canStart,
                    canStop = template.canStop
                )
            }
        }
        saveServices(current)
        return current
    }

    private fun sendTermuxCommand(args: Array<String>) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        try { context.startService(intent) } catch (_: Exception) {}
    }
}
