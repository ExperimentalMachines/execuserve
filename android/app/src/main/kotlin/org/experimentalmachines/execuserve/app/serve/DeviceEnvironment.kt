package org.experimentalmachines.execuserve.app.serve

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.experimentalmachines.execuserve.engine.Environment
import org.experimentalmachines.execuserve.engine.ThermalLevel

/** Android's thermal and battery readings as the engine's [Environment]. */
class DeviceEnvironment(private val context: Context) {

    private val _state = MutableStateFlow(Environment())
    val state: StateFlow<Environment> = _state.asStateFlow()

    private val power = context.getSystemService(PowerManager::class.java)

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _state.update { it.copy(thermal = thermalLevel(status)) }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
    }

    /** Listens for the life of the process: the console shows these readings whether or not the server runs. */
    fun start() {
        power.addThermalStatusListener(context.mainExecutor, thermalListener)
        _state.update { it.copy(thermal = thermalLevel(power.currentThermalStatus)) }
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::readBattery)
    }


    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        _state.update {
            it.copy(
                batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
                charging = plugged != 0,
            )
        }
    }

    private fun thermalLevel(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN
        else -> ThermalLevel.NONE
    }
}
