package org.experimentalmachines.execuserve.engine

/** Android's thermal status scale, which iOS's `ProcessInfo.ThermalState` maps onto. */
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/**
 * What the platform says about the device right now. Each platform fills this in; the
 * engine only reads it, so the policy that acts on it is written once.
 */
data class Environment(
    val thermal: ThermalLevel = ThermalLevel.NONE,
    val batteryPercent: Int? = null,
    val charging: Boolean = true,
)
