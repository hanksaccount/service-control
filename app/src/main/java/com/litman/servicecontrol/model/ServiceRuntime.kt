package com.litman.servicecontrol.model

enum class RunStatus { 
    RUNNING,         // Port svarar
    STOPPED,         // Port svarar inte
    ACTIVE,          // Körs (för non-web services)
    NOT_CONFIGURED,  // Saknar port/konfig
    UNKNOWN 
}

enum class ServiceType {
    WEB_PANEL,
    NOTIFIER,
    HYBRID,
    ACTION_SCRIPT,
    UNKNOWN
}

enum class ImpactLevel {
    LOW,
    MEDIUM,
    HIGH,
    BURST,
    IDLE
}

enum class ServiceGroup {
    PANELS,
    ACTIONS,
    UNKNOWN
}

data class ServiceRuntime(
    val status: RunStatus,
    val impact: ImpactLevel,
    val responseMs: Long? = null
) {
    companion object {
        val UNKNOWN = ServiceRuntime(RunStatus.UNKNOWN, ImpactLevel.IDLE, null)
        val NO_PORT = ServiceRuntime(RunStatus.NOT_CONFIGURED, ImpactLevel.IDLE, null)
    }
}

fun systemLoad(runtimes: Collection<ServiceRuntime>): String {
    val running = runtimes.filter { it.status == RunStatus.RUNNING || it.status == RunStatus.ACTIVE }
    if (running.isEmpty()) return "inget kör"
    return when {
        running.any { it.impact == ImpactLevel.HIGH || it.impact == ImpactLevel.BURST } -> "tungt"
        running.any { it.impact == ImpactLevel.MEDIUM } -> "jobbar"
        else -> "lugnt"
    }
}

fun systemLoadColor(load: String) = when (load) {
    "tungt"      -> 0xFFFF6B35.toInt()
    "jobbar"     -> 0xFFFFD166.toInt()
    "lugnt"      -> 0xFF00FF88.toInt()
    else         -> 0xFF888888.toInt()
}

fun statusDotColor(runtime: ServiceRuntime): Int = when (runtime.status) {
    RunStatus.RUNNING -> when (runtime.impact) {
        ImpactLevel.LOW    -> 0xFF00FF88.toInt()
        ImpactLevel.MEDIUM -> 0xFFFFD166.toInt()
        ImpactLevel.HIGH   -> 0xFFFF6B35.toInt()
        else               -> 0xFF00FF88.toInt()
    }
    RunStatus.ACTIVE -> 0xFF00AAFF.toInt()
    RunStatus.STOPPED -> 0xFFFF4444.toInt()
    RunStatus.NOT_CONFIGURED -> 0xFF444444.toInt()
    else -> 0xFF666666.toInt()
}

fun statusLabel(runtime: ServiceRuntime) = when (runtime.status) {
    RunStatus.RUNNING -> "Kör"
    RunStatus.ACTIVE -> "Aktiv"
    RunStatus.STOPPED -> "Stoppad"
    RunStatus.NOT_CONFIGURED -> "Ej konfigurerad"
    RunStatus.UNKNOWN -> "Okänd"
}
