package com.litman.servicecontrol.model

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class ServiceItem(
    val id: String,
    val name: String,
    var port: Int? = null,
    var icon: String = "📄",
    val scriptPath: String,
    var isEnabledOnWidget: Boolean = false
)

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

    private val downloadsDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private val debugLogFile: File
        get() = File(downloadsDir, "service_control_debug.txt")

    fun debugLog(msg: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            debugLogFile.appendText("[$ts] $msg\n")
        } catch (_: Exception) {}
    }

    fun clearDebugLog() {
        try { debugLogFile.writeText("=== debug log ===\n") } catch (_: Exception) {}
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

    fun runProbe(): TermuxResult {
        val probeFile = File(downloadsDir, "service_control_probe.txt").absolutePath
        val command = "echo 'steg1:start' > \"$probeFile\" && " +
            "ls /data/data/com.termux/files/home/.shortcuts/ >> \"$probeFile\" 2>&1 && " +
            "echo 'steg3:klar' >> \"$probeFile\""
        return startTermuxCommand(command)
    }

    // Returnerar rå filinnehåll eller kastar — låter anroparen hantera exception
    fun readProbeFileRaw(): String {
        val file = File(downloadsDir, "service_control_probe.txt")
        debugLog("probe: exists=${file.exists()} path=${file.absolutePath}")
        if (!file.exists()) return "(filen finns inte)"
        val content = file.readText()
        debugLog("probe: read ${content.length} bytes")
        return content.ifBlank { "(filen är tom)" }
    }

    fun triggerDiscoveryScan(): TermuxResult {
        val scanFile = File(downloadsDir, "discovered_scripts.txt").absolutePath
        val command = "ls /data/data/com.termux/files/home/.shortcuts/*.sh > \"$scanFile\" 2>&1"
        return startTermuxCommand(command)
    }

    // Returnerar rå filinnehåll — anroparen hanterar exception
    fun readScanFileRaw(): String {
        val file = File(downloadsDir, "discovered_scripts.txt")
        debugLog("scan: exists=${file.exists()} path=${file.absolutePath}")
        if (!file.exists()) return "(scanfil finns inte)"
        val content = file.readText()
        debugLog("scan: read ${content.length} bytes, content=[$content]")
        return content.ifBlank { "(scanfil är tom)" }
    }

    fun parseScanContent(raw: String): List<ServiceItem> {
        debugLog("parse: start, input=[$raw]")
        val lines = raw.lines().filter { it.trim().endsWith(".sh") }
        debugLog("parse: ${lines.size} rader matchar .sh")
        val current = getSavedServices().toMutableList()
        lines.forEach { path ->
            val trimmed = path.trim()
            if (current.none { it.scriptPath == trimmed }) {
                val name = trimmed.substringAfterLast("/").removeSuffix(".sh")
                current.add(ServiceItem(id = trimmed.hashCode().toString(), name = name, scriptPath = trimmed))
                debugLog("parse: lade till '$name'")
            }
        }
        saveServices(current)
        debugLog("parse: klar, ${current.size} tjänster totalt")
        return current
    }

    suspend fun checkStatus(port: Int): Boolean {
        return try {
            val response = withTimeoutOrNull(1000) {
                client.get("http://127.0.0.1:$port")
            }
            response?.status?.value == 200 || response?.status?.value == 404
        } catch (e: Exception) {
            false
        }
    }

    fun runTermuxScript(scriptPath: String) {
        sendTermuxCommand(arrayOf(scriptPath))
    }

    fun stopService(port: Int) {
        sendTermuxCommand(arrayOf("-c", "fuser -k $port/tcp"))
    }

    private fun startTermuxCommand(command: String): TermuxResult {
        val intent = Intent("com.termux.RUN_COMMAND")
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
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
