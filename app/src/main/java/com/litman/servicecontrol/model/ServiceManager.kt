package com.litman.servicecontrol.model

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

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
        ServiceTemplate("stopall", "Stoppa alla", ServiceType.ACTION_SCRIPT, null, ServiceGroup.ACTIONS, StatusCheckMode.ACTION, null, false, false, canStop = false),
        ServiceTemplate("playimdb", "Safe Stream: PlayIMDb", ServiceType.SAFE_STREAM, null, ServiceGroup.ACTIONS, StatusCheckMode.ACTION, null, false, true, "https://www.playimdb.com/title/tt0371746/")
    )

    fun find(name: String) = templates.find { it.name == name }
}

class ServiceManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("services_config", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val useLocalAgent = prefs.getBoolean("use_local_agent", false)
    private val agentBaseUrl = prefs.getString("agent_base_url", "http://127.0.0.1:5317") ?: "http://127.0.0.1:5317"
    private val executor: ServiceExecutor =
        if (useLocalAgent) {
            LocalAgentServiceExecutor(agentBaseUrl)
        } else {
            TermuxServiceExecutor(context)
        }
    private data class PendingState(
        val action: PendingAction,
        val startedAtMillis: Long
    )

    data class CommandResult(
        val accepted: Boolean,
        val message: String = ""
    )

    interface ServiceExecutor {
        suspend fun start(item: ServiceItem): CommandResult
        suspend fun stop(item: ServiceItem): CommandResult
        suspend fun run(scriptPath: String): CommandResult
        fun backendLabel(): String
    }

    class TermuxServiceExecutor(private val context: Context) : ServiceExecutor {
        override suspend fun start(item: ServiceItem): CommandResult = run(item.scriptPath)

        override suspend fun stop(item: ServiceItem): CommandResult {
            return if (item.checkMode == StatusCheckMode.PORT && item.port != null) {
                sendTermuxCommand(arrayOf("-c", "fuser -k ${item.port}/tcp"))
            } else if (item.checkMode == StatusCheckMode.PROCESS && !item.processMatch.isNullOrBlank()) {
                sendTermuxCommand(arrayOf("-c", "pkill -f \"${item.processMatch}\""))
            } else {
                CommandResult(false, "Saknar stoppmetod")
            }
        }

        override suspend fun run(scriptPath: String): CommandResult = sendTermuxCommand(arrayOf(scriptPath))

        override fun backendLabel(): String = "Termux bridge"

        private fun sendTermuxCommand(args: Array<String>): CommandResult {
            val intent = Intent("com.termux.RUN_COMMAND")
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            return try {
                context.startService(intent)
                CommandResult(true)
            } catch (e: Exception) {
                CommandResult(false, e.message ?: "Kunde inte skicka kommando")
            }
        }
    }

    class LocalAgentServiceExecutor(private val baseUrl: String) : ServiceExecutor {
        override suspend fun start(item: ServiceItem): CommandResult =
            postServiceAction(item.id, "start")

        override suspend fun stop(item: ServiceItem): CommandResult =
            postServiceAction(item.id, "stop")

        override suspend fun run(scriptPath: String): CommandResult =
            CommandResult(false, "Agent kräver service-id, inte direkt scriptPath")

        override fun backendLabel(): String = "Local agent"

        private suspend fun postServiceAction(serviceId: String, action: String): CommandResult =
            withContext(Dispatchers.IO) {
                try {
                    val url = URL("${baseUrl.trimEnd('/')}/services/$serviceId/$action")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 1000
                        readTimeout = 1500
                    }
                    val status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    if (status in 200..299) {
                        val json = JSONObject(body)
                        val state = json.optString("state", "accepted")
                        CommandResult(state != "failed", json.optString("message", ""))
                    } else {
                        val message = try {
                            JSONObject(body).optString("error", "Agent HTTP $status")
                        } catch (_: Exception) {
                            "Agent HTTP $status"
                        }
                        CommandResult(false, message)
                    }
                } catch (e: Exception) {
                    CommandResult(false, e.message ?: "Agent svarade inte")
                }
            }
    }

    fun getSavedServices(): List<ServiceItem> {
        return try {
            val json = prefs.getString("services_list", "[]")
            val type = object : TypeToken<List<ServiceItem>>() {}.type
            gson.fromJson<List<ServiceItem>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveServices(services: List<ServiceItem>) {
        val json = gson.toJson(services)
        prefs.edit().putString("services_list", json).apply()
    }

    fun backendLabel(): String = executor.backendLabel()

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

    suspend fun checkStatuses(services: List<ServiceItem>): Map<String, ServiceRuntime> = coroutineScope {
        services.associate { service ->
            service.id to async { checkStatus(service) }
        }.mapValues { it.value.await() }
    }

    suspend fun checkStatus(item: ServiceItem): ServiceRuntime {
        val raw = if (useLocalAgent) {
            checkAgentStatus(item) ?: checkLocalStatus(item)
        } else {
            checkLocalStatus(item)
        }
        return applyPendingState(item.id, raw)
    }

    private suspend fun checkLocalStatus(item: ServiceItem): ServiceRuntime {
        return when (item.checkMode) {
            StatusCheckMode.ACTION -> ServiceRuntime.UNKNOWN
            StatusCheckMode.PORT -> checkPortStatus(item.port)
            StatusCheckMode.PROCESS -> checkProcessStatus(item.processMatch)
        }
    }

    private suspend fun checkAgentStatus(item: ServiceItem): ServiceRuntime? = withContext(Dispatchers.IO) {
        try {
            val url = URL("${agentBaseUrl.trimEnd('/')}/services/${item.id}/status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 750
                readTimeout = 1000
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            ServiceRuntime(
                status = runStatusFromAgent(json.optString("state", "unknown")),
                impact = ServiceImpact(
                    checkDurationMs = json.optLong("checkDurationMs", 0L),
                    signal = impactFromAgent(json.optString("impact", "idle")),
                    detail = agentImpactDetail(json)
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun checkPortStatus(port: Int?): ServiceRuntime {
        if (port == null) return ServiceRuntime.NO_PORT
        val start = System.currentTimeMillis()
        return try {
            val responseCode = try {
                val url = URL("http://127.0.0.1:$port")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 750
                    readTimeout = 750
                }
                val code = conn.responseCode
                conn.disconnect()
                code
            } catch (_: Exception) { null }
            val duration = System.currentTimeMillis() - start
            
            if (responseCode != null && responseCode in 200..499) {
                ServiceRuntime(
                    RunStatus.RUNNING,
                    ServiceImpact(
                        checkDurationMs = duration,
                        signal = signalFromLatency(duration),
                        detail = "${duration}ms"
                    )
                )
            } else {
                ServiceRuntime(
                    RunStatus.STOPPED,
                    ServiceImpact(checkDurationMs = duration, signal = ImpactSignal.IDLE, detail = "${duration}ms")
                )
            }
        } catch (e: Exception) {
            ServiceRuntime(RunStatus.UNKNOWN, ServiceImpact(signal = ImpactSignal.ERROR, detail = e.message ?: "Okänt fel"))
        }
    }

    private suspend fun checkProcessStatus(match: String?): ServiceRuntime {
        if (match.isNullOrBlank()) return ServiceRuntime.NO_PORT
        return withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val process = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", match))
                val exitValue = process.waitFor()
                val duration = System.currentTimeMillis() - start
                if (exitValue == 0) {
                    ServiceRuntime(
                        RunStatus.ACTIVE,
                        ServiceImpact(
                            checkDurationMs = duration,
                            signal = ImpactSignal.LOW,
                            detail = "process"
                        )
                    )
                } else {
                    ServiceRuntime(
                        RunStatus.STOPPED,
                        ServiceImpact(checkDurationMs = duration, signal = ImpactSignal.IDLE, detail = "ingen process")
                    )
                }
            } catch (e: Exception) {
                ServiceRuntime(RunStatus.UNKNOWN, ServiceImpact(signal = ImpactSignal.ERROR, detail = e.message ?: "Okänt fel"))
            }
        }
    }

    suspend fun runTermuxScript(scriptPath: String): CommandResult {
        return executor.run(scriptPath)
    }

    suspend fun runAction(item: ServiceItem): CommandResult {
        return executor.start(item)
    }

    suspend fun stopService(item: ServiceItem): CommandResult {
        markPending(item.id, PendingAction.STOP)
        val result = executor.stop(item)
        if (!result.accepted) markFailed(item.id, result.message)
        return result
    }

    fun toggleMute(serviceId: String) {
        val services = getSavedServices().toMutableList()
        val idx = services.indexOfFirst { it.id == serviceId }
        if (idx != -1) {
            services[idx] = services[idx].copy(isMuted = !services[idx].isMuted)
            saveServices(services)
        }
    }

    suspend fun togglePower(serviceId: String) {
        val services = getSavedServices()
        val item = services.find { it.id == serviceId } ?: return
        val runtime = checkStatus(item)
        if (runtime.status == RunStatus.STARTING || runtime.status == RunStatus.STOPPING) return

        if (isServiceActive(runtime)) {
            stopService(item)
        } else {
            markPending(item.id, PendingAction.START)
            val result = executor.start(item)
            if (!result.accepted) markFailed(item.id, result.message)
        }
    }

    private fun applyPendingState(serviceId: String, raw: ServiceRuntime): ServiceRuntime {
        val failed = prefs.getString(failedMessageKey(serviceId), null)
        if (failed != null) {
            if (raw.status == RunStatus.RUNNING || raw.status == RunStatus.ACTIVE || raw.status == RunStatus.STOPPED) {
                clearFailed(serviceId)
            } else {
                return ServiceRuntime(RunStatus.FAILED, ServiceImpact(signal = ImpactSignal.ERROR, detail = failed))
            }
        }

        val pending = readPending(serviceId) ?: return raw
        val pendingFor = System.currentTimeMillis() - pending.startedAtMillis
        val timeoutMs = 45_000L

        if (pending.action == PendingAction.START && isServiceActive(raw)) {
            clearPending(serviceId)
            return raw
        }
        if (pending.action == PendingAction.STOP && raw.status == RunStatus.STOPPED) {
            clearPending(serviceId)
            return raw
        }
        if (pendingFor > timeoutMs) {
            clearPending(serviceId)
            val message = "Verifiering tog mer än ${(timeoutMs / 1000f).roundToInt()}s"
            markFailed(serviceId, message)
            return ServiceRuntime(RunStatus.FAILED, ServiceImpact(signal = ImpactSignal.ERROR, detail = message))
        }

        val pendingStatus = if (pending.action == PendingAction.START) RunStatus.STARTING else RunStatus.STOPPING
        return ServiceRuntime(
            pendingStatus,
            raw.impact.copy(
                pendingForMs = pendingFor,
                signal = ImpactSignal.WAITING,
                detail = "${pendingFor / 1000}s"
            )
        )
    }

    private fun signalFromLatency(durationMs: Long): ImpactSignal = when {
        durationMs < 120 -> ImpactSignal.LOW
        durationMs < 500 -> ImpactSignal.MEDIUM
        else -> ImpactSignal.HIGH
    }

    private fun runStatusFromAgent(state: String): RunStatus = when (state.lowercase()) {
        "running" -> RunStatus.RUNNING
        "active" -> RunStatus.ACTIVE
        "starting" -> RunStatus.STARTING
        "stopping" -> RunStatus.STOPPING
        "stopped" -> RunStatus.STOPPED
        "failed", "error" -> RunStatus.FAILED
        "not_configured" -> RunStatus.NOT_CONFIGURED
        else -> RunStatus.UNKNOWN
    }

    private fun impactFromAgent(impact: String): ImpactSignal = when (impact.lowercase()) {
        "low" -> ImpactSignal.LOW
        "medium" -> ImpactSignal.MEDIUM
        "high" -> ImpactSignal.HIGH
        "waiting" -> ImpactSignal.WAITING
        "error" -> ImpactSignal.ERROR
        else -> ImpactSignal.IDLE
    }

    private fun agentImpactDetail(json: JSONObject): String {
        val memoryKb = json.optLong("memoryKb", 0L)
        val pidCount = json.optInt("pidCount", 0)
        val uptime = if (json.isNull("uptimeSeconds")) null else json.optLong("uptimeSeconds")
        val parts = mutableListOf<String>()
        if (pidCount > 0) parts.add("$pidCount pid")
        if (memoryKb > 0) parts.add("${memoryKb}KB")
        if (uptime != null) parts.add("${uptime}s")
        if (parts.isEmpty()) parts.add("${json.optLong("checkDurationMs", 0L)}ms")
        return parts.joinToString(" · ")
    }

    private fun markPending(serviceId: String, action: PendingAction) {
        prefs.edit()
            .putString(pendingActionKey(serviceId), action.name)
            .putLong(pendingStartedKey(serviceId), System.currentTimeMillis())
            .remove(failedMessageKey(serviceId))
            .apply()
    }

    private fun readPending(serviceId: String): PendingState? {
        val actionName = prefs.getString(pendingActionKey(serviceId), null) ?: return null
        val action = try { PendingAction.valueOf(actionName) } catch (_: Exception) { return null }
        val started = prefs.getLong(pendingStartedKey(serviceId), 0L)
        if (started <= 0L) return null
        return PendingState(action, started)
    }

    private fun clearPending(serviceId: String) {
        prefs.edit()
            .remove(pendingActionKey(serviceId))
            .remove(pendingStartedKey(serviceId))
            .apply()
    }

    private fun markFailed(serviceId: String, message: String) {
        clearPending(serviceId)
        prefs.edit().putString(failedMessageKey(serviceId), message.ifBlank { "Action misslyckades" }).apply()
    }

    private fun clearFailed(serviceId: String) {
        prefs.edit().remove(failedMessageKey(serviceId)).apply()
    }

    private fun pendingActionKey(serviceId: String) = "pending_action_$serviceId"
    private fun pendingStartedKey(serviceId: String) = "pending_started_$serviceId"
    private fun failedMessageKey(serviceId: String) = "failed_message_$serviceId"
}
