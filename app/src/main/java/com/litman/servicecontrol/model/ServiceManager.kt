package com.litman.servicecontrol.model

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

private const val TAG = "ServiceCtrl"
private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

// ── Domain models ────────────────────────────────────────────────────────────

data class ServiceItem(
    val id: String,
    val name: String,
    var displayName: String = "",
    var scriptPath: String,
    var stopFlagPath: String? = null,
    var ports: List<Int> = emptyList(),
    var killPatterns: List<String> = emptyList(),
    var notificationIds: List<String> = emptyList(),
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
    var isManuallyStopped: Boolean = false,
    var contractPath: String? = null  // pilot: sökväg till services/<id>/ med start/stop/status.sh
) {
    val label: String get() = if (displayName.isNotBlank()) displayName else name
    // For backward compatibility and status checks, we still use the first port as primary
    val port: Int? get() = ports.firstOrNull()
}

data class ServiceTemplate(
    val name: String,
    val displayName: String,
    val type: ServiceType,
    val group: ServiceGroup,
    val scriptName: String? = null,
    val stopFlagPath: String? = null,
    val ports: List<Int> = emptyList(),
    val killPatterns: List<String> = emptyList(),
    val notificationIds: List<String> = emptyList(),
    val checkMode: StatusCheckMode = StatusCheckMode.PORT,
    val processMatch: String? = null,
    val showInWidget: Boolean = false,
    val canOpen: Boolean = false,
    val openUrl: String? = null,
    val canStart: Boolean = true,
    val canStop: Boolean = true,
    val contractPath: String? = null  // pilot: sökväg till services/<id>/ med start/stop/status.sh
)

// ── Template registry ────────────────────────────────────────────────────────

object TemplateRegistry {
    private const val HOME = "/data/data/com.termux/files/home"
    
    private val templates = listOf(
        ServiceTemplate(
            name = "fuel-intel", displayName = "fuel-intel",
            type = ServiceType.WEB_PANEL, group = ServiceGroup.PANELS,
            scriptName = "fuel.sh",
            stopFlagPath = "$HOME/STOP_FUEL_INTEL",
            ports = listOf(5201, 5210),
            killPatterns = listOf("fuel_service.sh", "server/index.js", "vite"),
            notificationIds = listOf("fuel-intel"),
            checkMode = StatusCheckMode.PORT,
            showInWidget = true, canOpen = true, openUrl = "http://127.0.0.1:5210",
            contractPath = "$HOME/projects/service-control/services/fuel-intel"
        ),
        ServiceTemplate(
            name = "elpris", displayName = "elpris",
            type = ServiceType.HYBRID, group = ServiceGroup.PANELS,
            scriptName = "elpris.sh",
            stopFlagPath = "$HOME/STOP_ELPRIS",
            ports = listOf(5100),
            killPatterns = listOf("elpris_service.sh", "server.py"),
            notificationIds = listOf("elpris"),
            checkMode = StatusCheckMode.PORT,
            showInWidget = true, canOpen = true, openUrl = "http://127.0.0.1:5100",
            contractPath = "$HOME/projects/service-control/services/elpris"
        ),
        ServiceTemplate(
            name = "dashboard", displayName = "dashboard",
            type = ServiceType.HYBRID, group = ServiceGroup.PANELS,
            scriptName = "dashboard.sh",
            stopFlagPath = "$HOME/STOP_DASHBOARD",
            ports = listOf(5000),
            killPatterns = listOf("start.sh", "battery_monitor.sh"),
            notificationIds = listOf("dashboard_pro"),
            checkMode = StatusCheckMode.PORT,
            showInWidget = true, canOpen = true, openUrl = "http://127.0.0.1:5000",
            contractPath = "$HOME/projects/service-control/services/dashboard"
        ),
        ServiceTemplate(
            name = "autosort", displayName = "autosort",
            type = ServiceType.WEB_PANEL, group = ServiceGroup.PANELS,
            scriptName = "autosort.sh",
            stopFlagPath = "$HOME/STOP_AUTOSORT",
            ports = listOf(5300),
            killPatterns = listOf("auto_sort_service.sh", "panel-start.sh"),
            notificationIds = listOf("autosort", "autosort-status"),
            checkMode = StatusCheckMode.PORT,
            showInWidget = true, canOpen = true, openUrl = "http://127.0.0.1:5300",
            contractPath = "$HOME/projects/service-control/services/autosort"
        ),
        ServiceTemplate(
            name = "runfull", displayName = "Kör alla",
            type = ServiceType.ACTION_SCRIPT, group = ServiceGroup.ACTIONS,
            scriptName = "runfull.sh",
            checkMode = StatusCheckMode.ACTION, canStop = false
        )
    )
    fun find(name: String) = templates.find { it.name == name }
}

// ── Widget settings ──────────────────────────────────────────────────────────

data class WidgetSettings(
    val nameSize: Float     = 13f,
    val metaSize: Float     = 9f,
    val padding: Float      = 12f,
    val rowSpacing: Float   = 8f,
    val actionScale: Float  = 1f,
    val opacity: Int        = 230,
    val cornerRadius: Float = 12f,
    val fontStyle: String   = "SANS",    // SANS | MONO
    val theme: String       = "GRAPHITE", // GRAPHITE | SLATE | DEEP_BLUE | SOFT_GREEN | AMBER | MONO
    val showMemory: Boolean  = true,
    val showColumnHeaders: Boolean = true,
    val showNetworkUsage: Boolean = false
)

// ── Theme definitions ────────────────────────────────────────────────────────

data class AppTheme(
    val id: String,
    val name: String,
    val accent: Long,
    val accentBg: Long,
    val isPremium: Boolean = true
)

object Themes {
    val ALL = listOf(
        AppTheme("GRAPHITE",   "Graphite",   0xFFBBBBC8, 0xFF1A1A22),
        AppTheme("SLATE",      "Slate",      0xFF708090, 0xFF14191E),
        AppTheme("DEEP_BLUE",  "Deep Blue",  0xFF336699, 0xFF0A141E),
        AppTheme("SOFT_GREEN", "Soft Green", 0xFF66BB6A, 0xFF0E1E12),
        AppTheme("AMBER",      "Amber Ind.", 0xFFFFB300, 0xFF1E1600),
        AppTheme("MONO",       "Monochrome", 0xFFEEEEF5, 0xFF16161E)
    )
    fun find(id: String) = ALL.find { it.id == id } ?: ALL[0]
}

// ── Service manager ──────────────────────────────────────────────────────────

class ServiceManager(val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("services_config", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val termuxHome = "/data/data/com.termux/files/home"
    private val termuxBash = "/data/data/com.termux/files/usr/bin/bash"

    // Bump this when buildDefaultServices() changes port/path/mode config.
    private val SERVICES_VERSION = 9  // v9: force refresh after widget/provider cleanup

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
                rowSpacing        = if (obj.has("rowSpacing"))        obj["rowSpacing"].asFloat        else d.rowSpacing,
                actionScale       = if (obj.has("actionScale"))       obj["actionScale"].asFloat       else d.actionScale,
                opacity           = if (obj.has("opacity"))           obj["opacity"].asInt             else d.opacity,
                cornerRadius      = if (obj.has("cornerRadius"))      obj["cornerRadius"].asFloat      else d.cornerRadius,
                fontStyle         = if (obj.has("fontStyle"))         obj["fontStyle"].asString        else d.fontStyle,
                theme             = if (obj.has("theme"))             obj["theme"].asString            else if (obj.has("accentColor")) obj["accentColor"].asString else d.theme,
                showMemory        = if (obj.has("showMemory"))        obj["showMemory"].asBoolean      else d.showMemory,
                showColumnHeaders = if (obj.has("showColumnHeaders")) obj["showColumnHeaders"].asBoolean else d.showColumnHeaders,
                showNetworkUsage  = if (obj.has("showNetworkUsage"))  obj["showNetworkUsage"].asBoolean  else d.showNetworkUsage
            )
            Log.d(TAG, "getWidgetSettings: opacity=${s.opacity} font=${s.fontStyle} theme=${s.theme}")
            s
        } catch (e: Exception) {
            Log.e(TAG, "getWidgetSettings: parse error, returning defaults", e)
            WidgetSettings()
        }
    }

    fun saveWidgetSettings(settings: WidgetSettings) {
        val json = gson.toJson(settings)
        prefs.edit().putString("widget_settings", json).commit()
        Log.d(TAG, "[ServiceCtrl] saveWidgetSettings: opacity=${settings.opacity} font=${settings.fontStyle} theme=${settings.theme}")
    }

    // ── Pending action tracking ───────────────────────────────────────────────
    // Key: serviceId, Value: STARTING | STOPPING

    fun markStarting(serviceId: String) {
        prefs.edit().putString("pending_$serviceId", "STARTING").commit()
        // Optimistic cache update for immediate widget/app feedback
        updateSingleCachedStatus(serviceId, ServiceRuntime(RunStatus.STARTING, "command sent"))
        Log.d(TAG, "[ServiceCtrl] markStarting: $serviceId (optimistic cache set)")
    }

    fun markStopping(serviceId: String) {
        prefs.edit().putString("pending_$serviceId", "STOPPING").commit()
        // Optimistic cache update for immediate widget/app feedback
        updateSingleCachedStatus(serviceId, ServiceRuntime(RunStatus.STOPPING, "command sent"))
        Log.d(TAG, "[ServiceCtrl] markStopping: $serviceId (optimistic cache set)")
    }

    fun clearPending(serviceId: String) {
        prefs.edit().remove("pending_$serviceId").commit()
        Log.d(TAG, "[ServiceCtrl] clearPending: $serviceId")
    }

    fun getPendingState(serviceId: String): String? =
        prefs.getString("pending_$serviceId", null)

    fun isPending(serviceId: String): Boolean = getPendingState(serviceId) != null

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
        val names = listOf("fuel-intel", "elpris", "dashboard", "autosort", "runfull")
        
        return names.mapNotNull { name ->
            val template = TemplateRegistry.find(name) ?: return@mapNotNull null
            val scriptName = template.scriptName ?: "$name.sh"
            ServiceItem(
                id = name,
                name = name,
                displayName = template.displayName,
                scriptPath = "$base/$scriptName",
                stopFlagPath = template.stopFlagPath,
                ports = template.ports,
                killPatterns = template.killPatterns,
                notificationIds = template.notificationIds,
                isEnabledOnWidget = template.showInWidget,
                type = template.type,
                group = template.group,
                checkMode = template.checkMode,
                processMatch = template.processMatch,
                canOpen = template.canOpen,
                openUrl = template.openUrl,
                canStart = template.canStart,
                canStop = template.canStop,
                contractPath = template.contractPath
            )
        }
    }

    // ── Status checks ─────────────────────────────────────────────────────────

    suspend fun checkStatus(item: ServiceItem): ServiceRuntime = when (item.checkMode) {
        StatusCheckMode.ACTION  -> ServiceRuntime(RunStatus.UNKNOWN, "manual action")
        StatusCheckMode.PORT    -> checkTcpPorts(item.ports)
        StatusCheckMode.PROCESS -> checkProcess(item)
    }

    /** Performs checkStatus and persists the result to the global cache. */
    suspend fun checkAndCacheStatus(item: ServiceItem): ServiceRuntime {
        val runtime = checkStatus(item)
        
        // Sync isManuallyStopped from filesystem if flag exists
        val flag = item.stopFlagPath ?: "$termuxHome/STOP_${item.name.replace('-', '_').uppercase()}"
        val flagExists = withContext(Dispatchers.IO) { File(flag).exists() }
        if (flagExists && !item.isManuallyStopped) {
            Log.d(TAG, "[ServiceCtrl] checkAndCacheStatus: flag found for ${item.id}, syncing isManuallyStopped=true")
            updateManualStopState(item.id, true)
        } else if (!flagExists && item.isManuallyStopped && runtime.status == RunStatus.RUNNING) {
             // If flag is gone but app thinks it's stopped, and it's actually running, sync back
             Log.d(TAG, "[ServiceCtrl] checkAndCacheStatus: flag GONE and service RUNNING for ${item.id}, syncing isManuallyStopped=false")
             updateManualStopState(item.id, false)
        }

        updateSingleCachedStatus(item.id, runtime)
        syncUptime(item.id, runtime)
        return runtime
    }

    private fun updateManualStopState(serviceId: String, isStopped: Boolean) {
        val services = getSavedServices().toMutableList()
        val idx = services.indexOfFirst { it.id == serviceId }
        if (idx != -1) {
            services[idx] = services[idx].copy(isManuallyStopped = isStopped)
            saveServices(services)
        }
    }

    private fun updateSingleCachedStatus(id: String, runtime: ServiceRuntime) {
        val current = getCachedStatuses().toMutableMap()
        current[id] = runtime
        saveCachedStatuses(current)
    }

    private suspend fun checkTcpPorts(ports: List<Int>): ServiceRuntime {
        if (ports.isEmpty()) return ServiceRuntime(RunStatus.UNKNOWN, "no ports configured")
        
        return withContext(Dispatchers.IO) {
            var upCount = 0
            ports.forEach { port ->
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                        upCount++
                    }
                } catch (e: Exception) {
                    // Port is down
                }
            }
            
            val status = when {
                upCount == 0 -> RunStatus.STOPPED
                upCount == ports.size -> RunStatus.RUNNING
                else -> RunStatus.DEGRADED
            }
            
            Log.d(TAG, "[ServiceCtrl] checkTcpPorts: ports=$ports → $status ($upCount/${ports.size} up)")
            ServiceRuntime(status, "ports $upCount/${ports.size} up (${ports.joinToString(", ")})")
        }
    }

    private suspend fun checkProcess(item: ServiceItem): ServiceRuntime {
        val match = item.processMatch
        if (!match.isNullOrBlank()) {
            val isUp = withContext(Dispatchers.IO) {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", match))
                    proc.waitFor() == 0
                } catch (e: Exception) {
                    false
                }
            }
            if (isUp) return ServiceRuntime(RunStatus.RUNNING, "process match: $match")
        }
        // Fallback to port check
        return checkTcpPorts(item.ports)
    }

    fun hasRunCommandPermission(): Boolean =
        context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun collectStats(item: ServiceItem): ServiceStats = withContext(Dispatchers.IO) {
        val patterns = statsPatterns(item)
        if (patterns.isEmpty()) {
            return@withContext ServiceStats(detail = "no process patterns")
        }

        val rows = mutableListOf<ProcessStatRow>()
        patterns.forEach { pattern ->
            rows += readProcessRows(pattern)
        }

        val uniqueRows = rows.distinctBy { it.pid }
        var cpu = 0f
        var rssKb = 0L
        uniqueRows.forEach {
            cpu += it.cpuPercent
            rssKb += it.rssKb
        }
        val memoryMb = rssKb.toFloat() / 1024f
        val impact = estimateImpact(cpu, memoryMb, uniqueRows.size)
        val detail = if (uniqueRows.isEmpty()) {
            "no matching processes"
        } else {
            "${uniqueRows.size} proc, ${formatOneDecimal(cpu)}% cpu, ${formatOneDecimal(memoryMb)} MB ram"
        }

        ServiceStats(
            processCount = uniqueRows.size,
            cpuPercent = cpu,
            memoryMb = memoryMb,
            impact = impact,
            detail = detail
        )
    }

    suspend fun collectAllStats(services: List<ServiceItem>): Map<String, ServiceStats> =
        coroutineScope {
            services
                .filter { it.checkMode != StatusCheckMode.ACTION }
                .map { service -> async { service.id to collectStats(service) } }
                .awaitAll()
                .toMap()
        }

    private fun statsPatterns(item: ServiceItem): List<String> {
        val all = mutableListOf<String>()
        item.processMatch?.takeIf { it.isNotBlank() }?.let { all += it }
        all += item.killPatterns.filter { it.isNotBlank() }
        if (item.contractPath != null) all += item.name
        return all.distinct()
    }

    private fun readProcessRows(pattern: String): List<ProcessStatRow> {
        return try {
            val proc = ProcessBuilder("sh", "-c", "ps -A -o PID,PCPU,RSS,ARGS 2>/dev/null | grep -F ${shellQuote(pattern)} | grep -v grep")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.lines().mapNotNull { parseProcessRow(it) }
                .ifEmpty { readProcessRowsFromProc(pattern) }
        } catch (e: Exception) {
            Log.w(TAG, "readProcessRows: failed for pattern=$pattern", e)
            readProcessRowsFromProc(pattern)
        }
    }

    private fun readProcessRowsFromProc(pattern: String): List<ProcessStatRow> {
        return try {
            val proc = ProcessBuilder("sh", "-c", "pgrep -f ${shellQuote(pattern)} 2>/dev/null")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.lines()
                .mapNotNull { it.trim().toIntOrNull() }
                .map { pid -> ProcessStatRow(pid, 0f, readRssKb(pid)) }
        } catch (e: Exception) {
            Log.w(TAG, "readProcessRowsFromProc: failed for pattern=$pattern", e)
            emptyList()
        }
    }

    private fun readRssKb(pid: Int): Long {
        return try {
            File("/proc/$pid/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.firstOrNull { it.toLongOrNull() != null }
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseProcessRow(line: String): ProcessStatRow? {
        val parts = line.trim().split(Regex("\\s+"), limit = 4)
        if (parts.size < 3) return null
        val pid = parts[0].toIntOrNull() ?: return null
        val cpu = parts[1].toFloatOrNull() ?: 0f
        val rss = parts[2].toLongOrNull() ?: 0L
        return ProcessStatRow(pid, cpu, rss)
    }

    private fun estimateImpact(cpu: Float, memoryMb: Float, processCount: Int): ResourceImpact = when {
        processCount == 0 -> ResourceImpact.UNKNOWN
        cpu >= 15f || memoryMb >= 700f -> ResourceImpact.HIGH
        cpu >= 4f || memoryMb >= 250f || processCount >= 4 -> ResourceImpact.MEDIUM
        else -> ResourceImpact.LOW
    }

    private fun formatOneDecimal(value: Float): String =
        (value * 10f).roundToInt().let { "${it / 10}.${it % 10}" }

    private data class ProcessStatRow(
        val pid: Int,
        val cpuPercent: Float,
        val rssKb: Long
    )

    // ── Actions ───────────────────────────────────────────────────────────────

    fun runTermuxScript(scriptPath: String): Boolean {
        Log.d(TAG, "runTermuxScript: $scriptPath")
        return sendTermuxShell("bash ${shellQuote(scriptPath)}")
    }

    fun stopService(item: ServiceItem): Boolean {
        // Pilot: om tjänsten har ett kontraktsstopp, delegera dit
        val contractPath = item.contractPath
        if (contractPath != null) {
            Log.d(TAG, "[ServiceCtrl] STOP via contract: id=${item.id} script=$contractPath/stop.sh")
            return sendTermuxShell("bash ${shellQuote("$contractPath/stop.sh")}")
        }

        // Legacy: bygg kommandot från template-data
        val flag = item.stopFlagPath ?: "$termuxHome/STOP_${item.name.replace('-', '_').uppercase()}"
        val cmds = mutableListOf<String>()
        cmds.add("touch ${shellQuote(flag)}")
        item.ports.forEach { port -> cmds.add("fuser -k $port/tcp 2>/dev/null || true") }
        item.killPatterns.forEach { pattern -> cmds.add("pkill -f ${shellQuote(pattern)} 2>/dev/null || true") }
        item.notificationIds.forEach { nid ->
            cmds.add("termux-notification-remove ${shellQuote(nid)} 2>/dev/null || termux-notification --remove ${shellQuote(nid)} 2>/dev/null || true")
        }

        val fullCmd = cmds.joinToString("; ")
        Log.d(TAG, "[ServiceCtrl] STOP ACTION (legacy): id=${item.id} name=${item.name}")
        Log.d(TAG, "[ServiceCtrl]   Ports: ${item.ports}")
        Log.d(TAG, "[ServiceCtrl]   Patterns: ${item.killPatterns}")
        Log.d(TAG, "[ServiceCtrl]   Notifs: ${item.notificationIds}")
        Log.d(TAG, "[ServiceCtrl]   Bash: $fullCmd")
        return sendTermuxShell(fullCmd)
    }

    fun startService(item: ServiceItem): Boolean {
        // Pilot: om tjänsten har ett kontraktsstart, delegera dit
        val contractPath = item.contractPath
        if (contractPath != null) {
            Log.d(TAG, "[ServiceCtrl] START via contract: id=${item.id} script=$contractPath/start.sh")
            return sendTermuxShell("bash ${shellQuote("$contractPath/start.sh")}")
        }

        // Legacy: ta bort STOP-flagga och kör shortcut-skript
        val flag = item.stopFlagPath ?: "$termuxHome/STOP_${item.name.replace('-', '_').uppercase()}"
        val fullCmd = "rm -f ${shellQuote(flag)}; bash ${shellQuote(item.scriptPath)}"
        Log.d(TAG, "[ServiceCtrl] START ACTION (legacy): id=${item.id} name=${item.name}")
        Log.d(TAG, "[ServiceCtrl]   Flag: $flag")
        Log.d(TAG, "[ServiceCtrl]   Script: ${item.scriptPath}")
        Log.d(TAG, "[ServiceCtrl]   Bash: $fullCmd")
        return sendTermuxShell(fullCmd)
    }

    fun toggleMute(serviceId: String) {
        val services = getSavedServices().toMutableList()
        val idx = services.indexOfFirst { it.id == serviceId }
        if (idx != -1) {
            services[idx] = services[idx].copy(isMuted = !services[idx].isMuted)
            saveServices(services)
        }
    }

    fun togglePower(serviceId: String, isRunning: Boolean): Boolean {
        val services = getSavedServices().toMutableList()
        val idx = services.indexOfFirst { it.id == serviceId }
        if (idx == -1) {
            Log.w(TAG, "[ServiceCtrl] togglePower: service NOT FOUND: $serviceId")
            return false
        }
        val item = services[idx]
        Log.d(TAG, "[ServiceCtrl] togglePower: id=$serviceId isRunningIn=$isRunning isManuallyStoppedBefore=${item.isManuallyStopped}")
        
        if (isRunning) {
            if (!item.canStop) {
                Log.w(TAG, "[ServiceCtrl] togglePower: stop blocked by service config: $serviceId")
                return false
            }
            markStopping(serviceId)
            services[idx] = item.copy(isManuallyStopped = true)
            saveServices(services)
            Log.d(TAG, "[ServiceCtrl]   Decided: STOP")
            val commandStarted = stopService(item)
            if (!commandStarted) {
                clearPending(serviceId)
                services[idx] = item
                saveServices(services)
            }
            return commandStarted
        } else {
            if (!item.canStart) {
                Log.w(TAG, "[ServiceCtrl] togglePower: start blocked by service config: $serviceId")
                return false
            }
            markStarting(serviceId)
            services[idx] = item.copy(isManuallyStopped = false)
            saveServices(services)
            Log.d(TAG, "[ServiceCtrl]   Decided: START")
            val commandStarted = startService(item)
            if (!commandStarted) {
                clearPending(serviceId)
                services[idx] = item
                saveServices(services)
            }
            return commandStarted
        }
    }

    suspend fun waitForToggleCompletion(serviceId: String, isRunning: Boolean): Boolean {
        val service = getSavedServices().find { it.id == serviceId } ?: return false
        repeat(10) {
            kotlinx.coroutines.delay(1000)
            val runtime = checkAndCacheStatus(service)
            val reached = if (isRunning) {
                runtime.status == RunStatus.STOPPED
            } else {
                runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.DEGRADED
            }
            if (reached) {
                clearPending(serviceId)
                return true
            }
        }

        Log.w(TAG, "[ServiceCtrl] waitForToggleCompletion: timeout waiting for $serviceId")
        clearPending(serviceId)
        return false
    }

    suspend fun togglePowerAndWait(serviceId: String, isRunning: Boolean): Boolean {
        val commandStarted = togglePower(serviceId, isRunning)
        if (!commandStarted) return false
        return waitForToggleCompletion(serviceId, isRunning)
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
            if (!item.canStart) {
                Log.d(TAG, "startAllEligible: skipping ${item.label} (start disabled)")
                continue
            }
            Log.d(TAG, "startAllEligible: starting ${item.label}")
            startService(item)
        }
        sendTermuxShell("termux-toast ${shellQuote("Native app-start slutförd")} 2>/dev/null || true")
    }

    fun stopAll() {
        Log.d(TAG, "stopAll: Stopping all services")
        
        val services = getSavedServices().toMutableList()
        var changed = false
        for (i in services.indices) {
            val item = services[i]
            if (item.type == ServiceType.ACTION_SCRIPT) continue
            if (!item.canStop) {
                Log.d(TAG, "stopAll: skipping ${item.label} (stop disabled)")
                continue
            }
            
            services[i] = item.copy(isManuallyStopped = true)
            changed = true
            stopService(item)
        }
        if (changed) saveServices(services)
        sendTermuxShell("termux-toast ${shellQuote("Alla tjänster stoppade")} 2>/dev/null || true")
    }

    private fun sendTermuxShell(command: String): Boolean {
        if (context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "sendTermuxShell: missing $TERMUX_RUN_COMMAND_PERMISSION")
            showToast("Missing Termux command permission. Open app and allow it.")
            return false
        }

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            setClassName("com.termux", "com.termux.app.RunCommandService")
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra("com.termux.RUN_COMMAND_PATH", termuxBash)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", termuxHome)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        Log.d(TAG, "sendTermuxShell: bash -lc $command")
        return try {
            context.applicationContext.startService(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendTermuxShell: startService failed. Check Termux RUN_COMMAND permission and allow-external-apps.", e)
            showToast("Termux command failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    // ── Parallel status check ─────────────────────────────────────────────────

    /** Check all services in parallel — much faster than sequential when some are offline. */
    suspend fun checkAllStatuses(services: List<ServiceItem>): Map<String, ServiceRuntime> =
        coroutineScope {
            val results = services.map { service ->
                async { service.id to checkStatus(service) }
            }.awaitAll().toMap()
            saveCachedStatuses(results)
            results.forEach { (id, runtime) -> syncUptime(id, runtime) }
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

    private fun syncUptime(serviceId: String, runtime: ServiceRuntime) {
        when (runtime.status) {
            RunStatus.RUNNING, RunStatus.DEGRADED -> {
                if (prefs.getLong("up_since_$serviceId", 0L) == 0L) recordStartTime(serviceId)
            }
            RunStatus.STOPPED -> clearStartTime(serviceId)
            else -> Unit
        }
    }

    // ── Uptime tracking ───────────────────────────────────────────────────────
    // Called when we confirm a service transitioned STOPPED → RUNNING.

    fun recordStartTime(serviceId: String) {
        prefs.edit().putLong("up_since_$serviceId", System.currentTimeMillis()).commit()
        Log.d(TAG, "recordStartTime: $serviceId")
    }

    fun clearStartTime(serviceId: String) {
        if (prefs.getLong("up_since_$serviceId", 0L) == 0L) return
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

    // ── Network usage tracking ────────────────────────────────────────────────

    fun getAppNetworkUsage(): String {
        val uid = context.applicationInfo.uid
        val rxBytes = android.net.TrafficStats.getUidRxBytes(uid)
        val txBytes = android.net.TrafficStats.getUidTxBytes(uid)
        
        if (rxBytes == android.net.TrafficStats.UNSUPPORTED.toLong() || txBytes == android.net.TrafficStats.UNSUPPORTED.toLong()) {
            return "net N/A"
        }
        
        val rxKb = rxBytes / 1024f
        val txKb = txBytes / 1024f
        
        return if (rxKb > 1024 || txKb > 1024) {
            "${formatOneDecimal(rxKb / 1024f)}↓ ${formatOneDecimal(txKb / 1024f)}↑ MB"
        } else {
            "${rxKb.roundToInt()}↓ ${txKb.roundToInt()}↑ KB"
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
                    displayName = template.displayName, scriptPath = path,
                    stopFlagPath = template.stopFlagPath,
                    ports = template.ports,
                    killPatterns = template.killPatterns,
                    notificationIds = template.notificationIds,
                    isEnabledOnWidget = template.showInWidget,
                    type = template.type, group = template.group,
                    checkMode = template.checkMode, processMatch = template.processMatch,
                    canOpen = template.canOpen, openUrl = template.openUrl,
                    canStart = template.canStart, canStop = template.canStop,
                    contractPath = template.contractPath
                ) else ServiceItem(id = path.hashCode().toString(), name = name, scriptPath = path))
            } else if (template != null) {
                val e = current[existing]
                current[existing] = e.copy(
                    displayName = template.displayName, scriptPath = path,
                    stopFlagPath = template.stopFlagPath,
                    ports = template.ports,
                    killPatterns = template.killPatterns,
                    notificationIds = template.notificationIds,
                    isEnabledOnWidget = template.showInWidget, type = template.type,
                    group = template.group, checkMode = template.checkMode,
                    processMatch = template.processMatch, canOpen = template.canOpen,
                    openUrl = template.openUrl, canStart = template.canStart, canStop = template.canStop,
                    contractPath = template.contractPath
                )
            }
        }
        saveServices(current)
        return current
    }
}
