package com.litman.servicecontrol.model

enum class RunStatus { 
    RUNNING,         // Port svarar (PORT-mode)
    ACTIVE,          // Process hittad (PROCESS-mode)
    STARTING,        // Startkommando skickat, verifiering pagaar
    STOPPING,        // Stoppkommando skickat, verifiering pagaar
    STOPPED,         // Ingen port/process hittad
    FAILED,          // Senaste action kunde inte verifieras
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
    SAFE_STREAM,
    UNKNOWN
}

enum class ServiceGroup {
    PANELS,
    ACTIONS,
    UNKNOWN
}

enum class PendingAction {
    START,
    STOP
}

data class ServiceImpact(
    val checkedAtMillis: Long = System.currentTimeMillis(),
    val checkDurationMs: Long? = null,
    val pendingForMs: Long? = null,
    val signal: ImpactSignal = ImpactSignal.IDLE,
    val detail: String = ""
)

enum class ImpactSignal {
    IDLE,
    LOW,
    MEDIUM,
    HIGH,
    WAITING,
    ERROR
}

data class ServiceRuntime(
    val status: RunStatus,
    val impact: ServiceImpact = ServiceImpact()
) {
    companion object {
        val UNKNOWN = ServiceRuntime(RunStatus.UNKNOWN)
        val NO_PORT = ServiceRuntime(RunStatus.NOT_CONFIGURED)
    }
}

fun isServiceActive(runtime: ServiceRuntime): Boolean =
    runtime.status == RunStatus.RUNNING || runtime.status == RunStatus.ACTIVE

fun isServicePending(runtime: ServiceRuntime): Boolean =
    runtime.status == RunStatus.STARTING || runtime.status == RunStatus.STOPPING

fun statusDotColor(runtime: ServiceRuntime): Int = when (runtime.status) {
    RunStatus.RUNNING, RunStatus.ACTIVE -> 0xFF00FF88.toInt() // Grön för båda
    RunStatus.STARTING, RunStatus.STOPPING -> 0xFFFFC857.toInt()
    RunStatus.FAILED -> 0xFFFF4444.toInt()
    RunStatus.STOPPED -> 0xFFFF4444.toInt() // Röd
    RunStatus.NOT_CONFIGURED -> 0xFF444444.toInt()
    else -> 0xFF666666.toInt()
}

fun statusLabel(runtime: ServiceRuntime) = when (runtime.status) {
    RunStatus.RUNNING, RunStatus.ACTIVE -> "Kör"
    RunStatus.STARTING -> "Startar"
    RunStatus.STOPPING -> "Stoppar"
    RunStatus.STOPPED -> "Stoppad"
    RunStatus.FAILED -> "Fel"
    RunStatus.NOT_CONFIGURED -> "Ej konfigurerad"
    else -> "Okänd"
}

fun actionLabel(runtime: ServiceRuntime) = when (runtime.status) {
    RunStatus.RUNNING, RunStatus.ACTIVE -> "Stoppa"
    RunStatus.STARTING -> "..."
    RunStatus.STOPPING -> "..."
    else -> "Starta"
}

fun impactLabel(impact: ServiceImpact) = when (impact.signal) {
    ImpactSignal.IDLE -> "Vilande"
    ImpactSignal.LOW -> "Låg"
    ImpactSignal.MEDIUM -> "Mellan"
    ImpactSignal.HIGH -> "Hög"
    ImpactSignal.WAITING -> "Väntar"
    ImpactSignal.ERROR -> "Fel"
}
