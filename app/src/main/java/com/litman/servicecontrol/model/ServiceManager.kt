package com.litman.servicecontrol.model

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import kotlinx.coroutines.withTimeoutOrNull
import com.litman.servicecontrol.model.LoadLevel
import com.litman.servicecontrol.model.RunStatus
import com.litman.servicecontrol.model.ServiceRuntime
import java.text.SimpleDateFormat
import java.util.*

data class ServiceItem(
    val id: String,
    val name: String,
    val displayName: String = "",
    var port: Int? = null,
    var icon: String = "📄",
    val scriptPath: String,
    var isEnabledOnWidget: Boolean = false
) {
    val label: String get() = if (displayName.isNotBlank()) displayName else name
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

    fun clearDebugLog() {
        try { logFile.writeText("=== debug log ===\n") } catch (_: Exception) {}
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

    // Probe: listar .shortcuts-mappen, resultatet kommer via pendingIntent
    fun runProbe(pendingIntent: PendingIntent): TermuxResult {
        val command = "ls /data/data/com.termux/files/home/.shortcuts/ 2>&1 && echo '__probe_ok__'"
        return startTermuxCommand(command, pendingIntent)
    }

    // Scan: listar .sh-filer, resultatet (stdout) kommer via pendingIntent
    fun triggerDiscoveryScan(pendingIntent: PendingIntent): TermuxResult {
        val command = "ls /data/data/com.termux/files/home/.shortcuts/*.sh 2>&1"
        return startTermuxCommand(command, pendingIntent)
    }

    // Parsar stdout från scan-kommandot till ServiceItem-lista
    fun parseScanResult(stdout: String): List<ServiceItem> {
        debugLog("parse: input=[$stdout]")
        val lines = stdout.lines().map { it.trim() }.filter { it.endsWith(".sh") }
        debugLog("parse: ${lines.size} .sh-rader")
        val current = getSavedServices().toMutableList()
        lines.forEach { path ->
            if (current.none { it.scriptPath == path }) {
                val name = path.substringAfterLast("/").removeSuffix(".sh")
                current.add(ServiceItem(id = path.hashCode().toString(), name = name, scriptPath = path))
                debugLog("parse: lade till '$name' ($path)")
            }
        }
        saveServices(current)
        return current
    }

    suspend fun checkStatus(port: Int?): Boolean =
        checkStatusWithLoad(port).status == RunStatus.RUNNING

    suspend fun checkStatusWithLoad(port: Int?): ServiceRuntime {
        if (port == null) return ServiceRuntime.NO_PORT
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
                ServiceRuntime(RunStatus.STOPPED, LoadLevel.UNKNOWN, null)
            } else {
                val ok = response.status.value in 200..499
                val load = when {
                    ms < 300  -> LoadLevel.LOW
                    ms < 700  -> LoadLevel.MEDIUM
                    else      -> LoadLevel.HIGH
                }
                ServiceRuntime(
                    status = if (ok) RunStatus.RUNNING else RunStatus.STOPPED,
                    load   = if (ok) load else LoadLevel.UNKNOWN,
                    responseMs = ms
                )
            }
        } catch (e: Exception) {
            debugLog("checkStatus error: ${e.javaClass.simpleName}: ${e.message}")
            ServiceRuntime(RunStatus.UNKNOWN, LoadLevel.UNKNOWN, null)
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
            debugLog("startService OK: $command")
            TermuxResult(command)
        } catch (e: Exception) {
            val err = "${e.javaClass.simpleName}: ${e.message}"
            debugLog("startService FEL: $err")
            TermuxResult(command, err)
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
