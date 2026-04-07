package com.litman.servicecontrol.model

enum class RunStatus { 
    RUNNING,         // Port svarar (PORT-mode)
    ACTIVE,          // Process hittad (PROCESS-mode)
    STOPPED,         // Ingen port/process hittad
    NOT_CONFIGURED,  // Saknar konfiguration
    UNKNOWN 
}

enum class StatusCheckMode {
    PORT,
    PROCESS,
    ACTION
}

enum class ServiceType {
    WEB_PANEL,
    HYBRID,
    ACTION_SCRIPT,
    UNKNOWN
}

enum class ServiceGroup {
    PANELS,
    ACTIONS,
    UNKNOWN
}

data class ServiceRuntime(
    val status: RunStatus
) {
    companion object {
        val UNKNOWN = ServiceRuntime(RunStatus.UNKNOWN)
        val NO_PORT = ServiceRuntime(RunStatus.NOT_CONFIGURED)
    }
}

fun statusDotColor(runtime: ServiceRuntime): Int = when (runtime.status) {
    RunStatus.RUNNING, RunStatus.ACTIVE -> 0xFF00FF88.toInt() // Grön för båda
    RunStatus.STOPPED -> 0xFFFF4444.toInt() // Röd
    RunStatus.NOT_CONFIGURED -> 0xFF444444.toInt()
    else -> 0xFF666666.toInt()
}

fun statusLabel(runtime: ServiceRuntime) = when (runtime.status) {
    RunStatus.RUNNING, RunStatus.ACTIVE -> "Kör"
    RunStatus.STOPPED -> "Stoppad"
    RunStatus.NOT_CONFIGURED -> "Ej konfigurerad"
    else -> "Okänd"
}
