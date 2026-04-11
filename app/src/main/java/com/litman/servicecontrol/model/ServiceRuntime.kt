package com.litman.servicecontrol.model

enum class RunStatus { 
    STOPPED,    // Ingen port svarar
    STARTING,   // Visuellt läge: Väntar på portar
    RUNNING,    // Samtliga hälsoportar svarar
    DEGRADED,   // Vissa portar uppe, men inte alla
    STOPPING,   // Visuellt läge: Väntar på att portar ska dö
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
        val STOPPED = ServiceRuntime(RunStatus.STOPPED)
    }
}

fun statusDotColor(runtime: ServiceRuntime): Int = when (runtime.status) {
    RunStatus.RUNNING  -> 0xFF00FF88.toInt() // Grön
    RunStatus.DEGRADED -> 0xFFFFAA00.toInt() // Orange
    RunStatus.STARTING -> 0xFFFFFF00.toInt() // Gul
    RunStatus.STOPPING -> 0xFFFF6600.toInt() // Mörkorange
    RunStatus.STOPPED  -> 0xFFFF4444.toInt() // Röd
    else               -> 0xFF444444.toInt()
}

fun statusLabel(runtime: ServiceRuntime) = when (runtime.status) {
    RunStatus.RUNNING  -> "ONLINE"
    RunStatus.DEGRADED -> "PARTIAL"
    RunStatus.STARTING -> "STARTING"
    RunStatus.STOPPING -> "STOPPING"
    RunStatus.STOPPED  -> "OFFLINE"
    else               -> "UNKNOWN"
}
