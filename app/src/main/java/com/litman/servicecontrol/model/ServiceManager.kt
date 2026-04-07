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

data class ServiceItem(
    val id: String,
    val name: String,
    var port: Int? = null,
    var icon: String = "📄",
    val scriptPath: String,
    var isEnabledOnWidget: Boolean = false
)

// Returneras från probe/scan så UI kan visa exakt kommando + eventuellt fel
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

    // Publik plats som både Termux och appen kan nå
    private val downloadsDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun getSavedServices(): List<ServiceItem> {
        val json = prefs.getString("services_list", "[]")
        val type = object : TypeToken<List<ServiceItem>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveServices(services: List<ServiceItem>) {
        val json = gson.toJson(services)
        prefs.edit().putString("services_list", json).apply()
    }

    // Probe: steg-för-steg debug — visar hur långt Termux-kommandot faktiskt når
    fun runProbe(): TermuxResult {
        val probeFile = "${downloadsDir.absolutePath}/service_control_probe.txt"
        val command = buildString {
            append("echo 'steg1: start' > \"$probeFile\"; ")
            append("echo 'steg2: shortcuts finns:' >> \"$probeFile\"; ")
            append("ls /data/data/com.termux/files/home/.shortcuts/ >> \"$probeFile\" 2>&1; ")
            append("echo 'steg3: klar' >> \"$probeFile\"")
        }
        return startTermuxCommand(command)
    }

    fun probeFileContent(): String? {
        val file = File(downloadsDir, "service_control_probe.txt")
        return if (file.exists()) file.readText() else null
    }

    // Scan: listar .sh-filer i .shortcuts och skriver till Downloads
    fun triggerDiscoveryScan(): TermuxResult {
        val scanFile = "${downloadsDir.absolutePath}/discovered_scripts.txt"
        val command = buildString {
            append("echo 'scan start' > \"$scanFile\"; ")
            append("ls /data/data/com.termux/files/home/.shortcuts/*.sh >> \"$scanFile\" 2>&1; ")
            append("echo 'scan klar' >> \"$scanFile\"")
        }
        return startTermuxCommand(command)
    }

    fun syncDiscoveredScripts(): List<ServiceItem> {
        val scanFile = File(downloadsDir, "discovered_scripts.txt")
        if (!scanFile.exists()) return getSavedServices()

        val lines = scanFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("scan") }
            .filter { it.endsWith(".sh") }

        val currentServices = getSavedServices().toMutableList()
        lines.forEach { path ->
            val trimmed = path.trim()
            if (currentServices.none { it.scriptPath == trimmed }) {
                val name = trimmed.substringAfterLast("/").removeSuffix(".sh")
                currentServices.add(ServiceItem(
                    id = trimmed.hashCode().toString(),
                    name = name,
                    scriptPath = trimmed
                ))
            }
        }
        saveServices(currentServices)
        return currentServices
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
        try {
            context.startService(intent)
        } catch (e: Exception) {
            // Termux ej tillgängligt
        }
    }
}
