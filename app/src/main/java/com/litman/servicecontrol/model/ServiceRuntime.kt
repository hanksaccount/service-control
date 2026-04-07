package com.litman.servicecontrol.model

enum class RunStatus { RUNNING, STOPPED, UNKNOWN }

// Belastningsnivå baserad på HTTP-svarstid — utbyggbar med CPU/RAM senare
enum class LoadLevel {
    LOW,     // < 300ms
    MEDIUM,  // 300–700ms
    HIGH,    // > 700ms
    UNKNOWN  // inget svar / port saknas
}

data class ServiceRuntime(
    val status: RunStatus,
    val load: LoadLevel,
    val responseMs: Long?
) {
    companion object {
        val UNKNOWN = ServiceRuntime(RunStatus.UNKNOWN, LoadLevel.UNKNOWN, null)
        val NO_PORT = ServiceRuntime(RunStatus.UNKNOWN, LoadLevel.UNKNOWN, null)
    }
}

fun systemLoad(runtimes: Collection<ServiceRuntime>): String {
    val running = runtimes.filter { it.status == RunStatus.RUNNING }
    if (running.isEmpty()) return "inget kör"
    return when {
        running.any { it.load == LoadLevel.HIGH }   -> "tungt"
        running.any { it.load == LoadLevel.MEDIUM } -> "jobbar"
        else                                         -> "lugnt"
    }
}

fun systemLoadColor(load: String) = when (load) {
    "tungt"      -> 0xFFFF6B35.toInt()
    "jobbar"     -> 0xFFFFD166.toInt()
    "lugnt"      -> 0xFF00FF88.toInt()
    else         -> 0xFF888888.toInt()
}

fun statusDotColor(runtime: ServiceRuntime): Int = when {
    runtime.status == RunStatus.RUNNING && runtime.load == LoadLevel.LOW    -> 0xFF00FF88.toInt()
    runtime.status == RunStatus.RUNNING && runtime.load == LoadLevel.MEDIUM -> 0xFFFFD166.toInt()
    runtime.status == RunStatus.RUNNING && runtime.load == LoadLevel.HIGH   -> 0xFFFF6B35.toInt()
    runtime.status == RunStatus.STOPPED                                      -> 0xFFFF4444.toInt()
    else                                                                     -> 0xFF666666.toInt()
}

fun statusLabel(runtime: ServiceRuntime) = when (runtime.status) {
    RunStatus.RUNNING -> when (runtime.load) {
        LoadLevel.LOW    -> "Kör · Låg"
        LoadLevel.MEDIUM -> "Kör · Medel"
        LoadLevel.HIGH   -> "Kör · Hög"
        LoadLevel.UNKNOWN -> "Kör"
    }
    RunStatus.STOPPED -> "Stoppad"
    RunStatus.UNKNOWN -> "Okänd"
}
