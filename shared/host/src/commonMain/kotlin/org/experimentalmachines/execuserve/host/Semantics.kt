package org.experimentalmachines.execuserve.host

import org.experimentalmachines.execuserve.engine.Admission
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.FailureKind
import org.experimentalmachines.execuserve.engine.FinishReason
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.LaneState
import org.experimentalmachines.execuserve.engine.ThermalLevel

/**
 * What a state means, independent of how a platform shows it: each console maps a mood to
 * its colours and a kind to its own words. Kept here so that the Android and iOS consoles
 * cannot disagree about what counts as healthy.
 */
enum class Mood { GOOD, WORKING, ATTENTION, FAILED, IDLE }

enum class ServerLook(val mood: Mood) {
    SERVING(Mood.GOOD),
    WORKING(Mood.WORKING),
    PAUSED_HOT(Mood.ATTENTION),
    PAUSED_BATTERY(Mood.ATTENTION),
    NOT_RESPONDING(Mood.FAILED),
    STARTING(Mood.ATTENTION),
    STOPPING(Mood.ATTENTION),
    STOPPED(Mood.IDLE),
    FAILED_TO_START(Mood.FAILED),
    ;

    companion object {
        fun of(state: ServeHost.State, status: EngineStatus?): ServerLook = when (state) {
            is ServeHost.State.Running -> when {
                status?.lane == LaneState.WEDGED -> NOT_RESPONDING
                status?.admission == Admission.PAUSED_THERMAL -> PAUSED_HOT
                status?.admission == Admission.PAUSED_BATTERY -> PAUSED_BATTERY
                status != null && status.lane != LaneState.IDLE -> WORKING
                else -> SERVING
            }
            ServeHost.State.Starting -> STARTING
            ServeHost.State.Stopping -> STOPPING
            is ServeHost.State.Stopped -> if (state.error != null) FAILED_TO_START else STOPPED
        }
    }
}

/** How one request ended, for the log. */
enum class Outcome(val mood: Mood) {
    DONE(Mood.GOOD),
    TOOL_CALL(Mood.WORKING),
    CUT_OFF(Mood.ATTENTION),
    CLIENT_LEFT(Mood.IDLE),
    TOO_LONG(Mood.FAILED),
    TIMED_OUT(Mood.FAILED),
    REFUSED(Mood.FAILED),
    FAILED(Mood.FAILED),
    ;

    companion object {
        fun of(finish: FinishReason): Outcome = when (finish) {
            FinishReason.STOP -> DONE
            FinishReason.TOOL_CALLS -> TOOL_CALL
            FinishReason.LENGTH -> CUT_OFF
        }

        fun of(job: JobRecord): Outcome {
            job.finish?.let { return of(it) }
            return when (job.failure) {
                FailureKind.CLIENT_GONE, FailureKind.SLOW_CLIENT, FailureKind.CANCELLED -> CLIENT_LEFT
                FailureKind.CONTEXT_OVERFLOW -> TOO_LONG
                FailureKind.QUEUE_TIMEOUT, FailureKind.DEADLINE -> TIMED_OUT
                FailureKind.OVERHEATED, FailureKind.SHUTTING_DOWN -> REFUSED
                FailureKind.MODEL_UNAVAILABLE, FailureKind.RUNTIME, null -> FAILED
            }
        }
    }
}

/** The phone's thermal status, in the steps a person cares about. */
enum class Heat(val mood: Mood) {
    COOL(Mood.GOOD),
    WARM(Mood.GOOD),
    HOT(Mood.ATTENTION),
    TOO_HOT(Mood.FAILED),
    CRITICAL(Mood.FAILED),
    ;

    companion object {
        fun of(level: ThermalLevel): Heat = when (level) {
            ThermalLevel.NONE -> COOL
            ThermalLevel.LIGHT -> WARM
            ThermalLevel.MODERATE -> HOT
            ThermalLevel.SEVERE -> TOO_HOT
            ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN -> CRITICAL
        }
    }
}

/** The kind of network an address is on, in the order a console lists them; each platform names it. */
enum class NetworkKind { THIS_DEVICE, WIFI, TAILSCALE, VPN, HOTSPOT, USB, ETHERNET }

/** One URL a client can use. */
data class Endpoint(val url: String, val network: NetworkKind)

/** The key the console's own chat uses, so the log shows its requests as what they are. */
const val CONSOLE_KEY = "Console"

/** Earlier names of [CONSOLE_KEY], taken over (same id and secret) rather than left behind. */
val FORMER_CONSOLE_KEYS = setOf("Console test")
