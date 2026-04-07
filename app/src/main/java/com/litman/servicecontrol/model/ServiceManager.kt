package com.litman.servicecontrol.model

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

class ServiceManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("services_config", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val client = HttpClient(Android) {
        engine {
            connectTimeout = 1000
            socketTimeout = 1000
        }
    }

    // Hämtar alla sparade tjänster från mobilen
    fun getSavedServices(): List<ServiceItem> {
        val json = prefs.getString("services_list", "[]")
        val type = object : TypeToken<List<ServiceItem>>() {}.type
        return gson.fromJson(json, type)
    }

    // Sparar listan till mobilen
    fun saveServices(services: List<ServiceItem>) {
        val json = gson.toJson(services)
        prefs.edit().putString("services_list", json).apply()
    }

    // Denna körs när vi vill leta efter nya script i Termux
    // Returnerar true om lyckades, false om Termux-permission saknas
    fun triggerDiscoveryScan(): Boolean {
        val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: return false
        val outputPath = "$outputDir/discovered_scripts.txt"
        val scanCommand = "ls /data/data/com.termux/files/home/.shortcuts/*.sh > \"$outputPath\" 2>/dev/null || echo ''"

        val intent = Intent("com.termux.RUN_COMMAND")
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", scanCommand))
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)

        return try {
            context.startService(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    // Läser in resultatet från scanningen och uppdaterar listan
    fun syncDiscoveredScripts(): List<ServiceItem> {
        val outputDir = context.getExternalFilesDir(null) ?: return getSavedServices()
        val scanFile = File(outputDir, "discovered_scripts.txt")
        if (!scanFile.exists()) return getSavedServices()

        val discoveredPaths = scanFile.readLines().filter { it.isNotBlank() }
        val currentServices = getSavedServices().toMutableList()

        discoveredPaths.forEach { path ->
            if (currentServices.none { it.scriptPath == path }) {
                val fileName = path.substringAfterLast("/").substringBeforeLast(".sh")
                currentServices.add(ServiceItem(
                    id = path.hashCode().toString(),
                    name = fileName,
                    scriptPath = path
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
        val intent = Intent("com.termux.instance.execute_script")
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptPath))
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        context.startService(intent)
    }

    fun stopService(port: Int) {
        val intent = Intent("com.termux.instance.execute_script")
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/fuser")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-k", "$port/tcp"))
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        context.startService(intent)
    }
}
