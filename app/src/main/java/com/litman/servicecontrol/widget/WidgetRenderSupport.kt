package com.litman.servicecontrol.widget

import android.graphics.Color
import com.litman.servicecontrol.model.ResourceImpact
import com.litman.servicecontrol.model.RunStatus
import com.litman.servicecontrol.model.ServiceRuntime
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun formatOneDecimal(value: Float): String {
    val scaled = (value * 10f).roundToInt()
    return "${scaled / 10}.${abs(scaled % 10)}"
}

internal fun formatMemory(memoryMb: Float): String =
    if (memoryMb >= 1024f) "${formatOneDecimal(memoryMb / 1024f)} GB" else "${memoryMb.roundToInt()} MB"

internal fun formatCheckedAge(checkedAt: Long): String {
    if (checkedAt <= 0L) return "now"
    val ageSeconds = ((System.currentTimeMillis() - checkedAt).coerceAtLeast(0L) / 1000L)
    return when {
        ageSeconds < 5L -> "now"
        ageSeconds < 60L -> "${ageSeconds}s"
        else -> "${ageSeconds / 60L}m"
    }
}

internal fun impactColor(impact: ResourceImpact): Int = when (impact) {
    ResourceImpact.LOW -> Color.rgb(73, 230, 138)
    ResourceImpact.MEDIUM -> Color.rgb(255, 184, 77)
    ResourceImpact.HIGH -> Color.rgb(255, 95, 109)
    ResourceImpact.UNKNOWN -> Color.rgb(120, 132, 142)
}

internal fun impactLabel(impact: ResourceImpact): String = when (impact) {
    ResourceImpact.LOW -> "low"
    ResourceImpact.MEDIUM -> "medium"
    ResourceImpact.HIGH -> "high"
    ResourceImpact.UNKNOWN -> "unknown"
}

internal fun statusSummary(runtime: ServiceRuntime, isPending: Boolean): String = when {
    isPending -> "pending"
    runtime.status == RunStatus.RUNNING -> "online"
    runtime.status == RunStatus.DEGRADED -> "partial"
    runtime.status == RunStatus.STOPPED -> "offline"
    else -> "unknown"
}
