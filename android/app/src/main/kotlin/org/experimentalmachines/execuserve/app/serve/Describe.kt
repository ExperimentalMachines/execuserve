package org.experimentalmachines.execuserve.app.serve

import android.content.Context
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.models.DownloadState
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.LaneState
import org.experimentalmachines.execuserve.host.ServeHost

/** The notification's words for what the server is doing. */
object Describe {

    fun notification(context: Context, state: ServeHost.State?, status: EngineStatus?, download: DownloadState?): Pair<String, String> {
        val downloading = download?.let { context.getString(R.string.notif_downloading, it.id, Format.percent(it.bytes, it.total)) }
        return when (state) {
            is ServeHost.State.Running -> {
                val where = state.endpoints.firstOrNull()?.url?.removePrefix("http://")?.removeSuffix("/v1") ?: state.settings.port.toString()
                context.getString(R.string.notif_serving, where) to (downloading ?: activity(context, status))
            }
            ServeHost.State.Starting -> context.getString(R.string.notif_starting) to (downloading ?: context.getString(R.string.status_starting_hint))
            ServeHost.State.Stopping -> context.getString(R.string.notif_stopping) to context.getString(R.string.status_stopping_hint)
            else -> context.getString(R.string.app_name) to (downloading ?: context.getString(R.string.notif_idle))
        }
    }

    /** One line: what the lane is doing right now, and how many wait behind it. */
    private fun activity(context: Context, status: EngineStatus?): String {
        if (status == null) return context.getString(R.string.status_engine_starting)
        val running = status.running
        val now = when (status.lane) {
            LaneState.IDLE -> status.resident.firstOrNull()?.id
                ?.let { context.getString(R.string.notif_ready_model, it) }
                ?: context.getString(R.string.notif_ready_no_model)
            LaneState.LOADING -> context.getString(R.string.status_loading, running?.model ?: context.getString(R.string.status_a_model))
            LaneState.PREFILLING -> context.getString(R.string.status_reading, running?.client.orEmpty())
            LaneState.GENERATING -> context.resources.getQuantityString(
                R.plurals.notif_answering,
                running?.generatedTokens ?: 0,
                running?.generatedTokens ?: 0,
                running?.client.orEmpty(),
            )
            LaneState.WEDGED -> context.getString(R.string.notif_wedged)
            LaneState.STOPPED -> context.getString(R.string.look_stopped)
        }
        return if (status.queued > 0) context.resources.getQuantityString(R.plurals.notif_waiting, status.queued, now, status.queued) else now
    }
}
