package org.experimentalmachines.execuserve.app.ui

import androidx.annotation.StringRes
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.models.DownloadState
import org.experimentalmachines.execuserve.host.Heat
import org.experimentalmachines.execuserve.host.NetworkKind
import org.experimentalmachines.execuserve.host.Outcome
import org.experimentalmachines.execuserve.host.ServerLook
import org.experimentalmachines.execuserve.host.ThemeMode
import org.experimentalmachines.execuserve.host.ThinkingDefault
import org.experimentalmachines.execuserve.host.WakePolicy
import org.experimentalmachines.execuserve.server.BindMode

/*
 * The words for the shared states. The meaning of each (and so its colour) lives in
 * :shared:host; only the wording is Android's.
 */

@get:StringRes
val ServerLook.words: Int get() = when (this) {
    ServerLook.SERVING -> R.string.look_serving
    ServerLook.WORKING -> R.string.look_working
    ServerLook.PAUSED_HOT -> R.string.look_paused_hot
    ServerLook.PAUSED_BATTERY -> R.string.look_paused_battery
    ServerLook.NOT_RESPONDING -> R.string.look_not_responding
    ServerLook.STARTING -> R.string.look_starting
    ServerLook.STOPPING -> R.string.look_stopping
    ServerLook.STOPPED -> R.string.look_stopped
    ServerLook.FAILED_TO_START -> R.string.look_failed_to_start
}

@get:StringRes
val Outcome.words: Int get() = when (this) {
    Outcome.DONE -> R.string.outcome_done
    Outcome.TOOL_CALL -> R.string.outcome_tool_call
    Outcome.CUT_OFF -> R.string.outcome_cut_off
    Outcome.CANCELLED -> R.string.outcome_cancelled
    Outcome.CLIENT_LEFT -> R.string.outcome_client_left
    Outcome.TOO_LONG -> R.string.outcome_too_long
    Outcome.TIMED_OUT -> R.string.outcome_timed_out
    Outcome.REFUSED -> R.string.outcome_refused
    Outcome.FAILED -> R.string.outcome_failed
}

@get:StringRes
val Heat.words: Int get() = when (this) {
    Heat.COOL -> R.string.heat_cool
    Heat.WARM -> R.string.heat_warm
    Heat.HOT -> R.string.heat_hot
    Heat.TOO_HOT -> R.string.heat_too_hot
    Heat.CRITICAL -> R.string.heat_critical
}

@get:StringRes
val NetworkKind.words: Int get() = when (this) {
    NetworkKind.THIS_DEVICE -> R.string.net_this_device
    NetworkKind.WIFI -> R.string.net_wifi
    NetworkKind.TAILSCALE -> R.string.net_tailscale
    NetworkKind.VPN -> R.string.net_vpn
    NetworkKind.HOTSPOT -> R.string.net_hotspot
    NetworkKind.USB -> R.string.net_usb
    NetworkKind.ETHERNET -> R.string.net_ethernet
}

@get:StringRes
val BindMode.words: Int get() = when (this) {
    BindMode.LOOPBACK -> R.string.bind_loopback
    BindMode.NETWORK -> R.string.bind_network
}

@get:StringRes
val WakePolicy.words: Int get() = when (this) {
    WakePolicy.ALWAYS -> R.string.wake_always
    WakePolicy.WHILE_BUSY -> R.string.wake_busy
}

@get:StringRes
val ThinkingDefault.words: Int get() = when (this) {
    ThinkingDefault.MODEL -> R.string.thinking_model
    ThinkingDefault.ON -> R.string.thinking_on
    ThinkingDefault.OFF -> R.string.thinking_off
}

@get:StringRes
val ThemeMode.words: Int get() = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@get:StringRes
val DownloadState.Phase.words: Int get() = when (this) {
    DownloadState.Phase.QUEUED -> R.string.phase_queued
    DownloadState.Phase.DOWNLOADING -> R.string.phase_downloading
    DownloadState.Phase.VERIFYING -> R.string.phase_verifying
    DownloadState.Phase.DONE -> R.string.phase_done
    DownloadState.Phase.FAILED -> R.string.phase_failed
    DownloadState.Phase.CANCELLED -> R.string.phase_cancelled
}
