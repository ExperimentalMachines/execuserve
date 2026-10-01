package org.experimentalmachines.execuserve.app.serve

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.experimentalmachines.execuserve.app.BuildConfig
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.graph
import org.experimentalmachines.execuserve.app.models.DownloadState
import org.experimentalmachines.execuserve.app.settings.Recovery
import org.experimentalmachines.execuserve.app.ui.MainActivity
import org.experimentalmachines.execuserve.catalog.HfCatalog
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.LaneState
import org.experimentalmachines.execuserve.host.Choices
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.WakePolicy
import org.experimentalmachines.execuserve.server.BindMode

/**
 * What keeps the server alive with the screen off: a foreground service of type
 * `specialUse`, the only type that fits a server (`dataSync` is capped at six hours a day on
 * Android 15 and may not start at boot). It also carries model downloads, so they survive
 * the app leaving the screen.
 *
 * While it runs, the process sits in the foreground-service state, which AOSP exempts from
 * Doze's network block and wake-lock suspension (see ARCHITECTURE.md, "Backgrounding"). OEM
 * freezers are another matter and are surfaced on the console.
 */
class ServeService : LifecycleService() {

    private val graph get() = applicationContext.graph
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var advertiser: Advertiser
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNotifiedAt = 0L

    /**
     * Whether the latest start command has been acted on. Until it has, the service must not
     * stop itself for being idle: the observer below runs synchronously in onCreate, and its
     * first reading ("stopped, nothing downloading") stopped the service before
     * onStartCommand could call startForeground, which Android punishes by killing the app
     * (found on the emulator, ForegroundServiceDidNotStartInTimeException).
     */
    private var settled = false

    private var warnedRestricted = false

    override fun onCreate() {
        super.onCreate()
        advertiser = Advertiser(this)
        createChannels()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Within five seconds of startForegroundService, whatever else happens.
        promote(buildNotification(null, null))
        settled = false
        when (intent?.action) {
            ACTION_STOP -> lifecycleScope.launch {
                graph.settings.setWasServing(false)
                graph.host.stop()
                settle()
            }
            // One command, so the start cannot overtake the stop (two intents could; codex review).
            ACTION_RESTART -> lifecycleScope.launch {
                graph.host.stop()
                serve()
                settle()
            }
            ACTION_KEEP_ALIVE -> settle()
            ACTION_PULL -> lifecycleScope.launch {
                pull(intent.getStringExtra(EXTRA_REPO).orEmpty(), intent.getStringExtra(EXTRA_FILE).orEmpty())
                settle()
            }
            ACTION_START -> lifecycleScope.launch {
                applyOverrides(intent)
                // Someone asked for this start: whatever happened before is not news now.
                graph.settings.setRecovery(null)
                serve()
                settle()
            }
            // A sticky restart after the process died: the intent is gone, the settings are not.
            null -> lifecycleScope.launch {
                if (graph.settings.wasServing()) {
                    graph.settings.setRecovery(Recovery(System.currentTimeMillis(), afterWedge = graph.settings.wedged()))
                    graph.settings.setWedged(false)
                    serve()
                }
                settle()
            }
        }
        return START_STICKY
    }

    private suspend fun serve() {
        graph.settings.setWasServing(true)
        graph.host.start(onWedged = ::onWedged)
        val state = graph.host.state.value
        if (state is ServeHost.State.Running && state.settings.bind == BindMode.NETWORK) {
            advertiser.start(state.settings.port, BuildConfig.VERSION_NAME)
        } else {
            advertiser.stop()
        }
        if (state is ServeHost.State.Stopped) graph.settings.setWasServing(false)
    }

    private fun settle() {
        settled = true
        stopIfIdle()
    }

    /** A catalog download asked for over adb; the same downloader the Models screen uses. */
    private suspend fun pull(repo: String, file: String) {
        runCatching { graph.catalog.variant(repo, file) }
            .onSuccess { graph.downloader.enqueue(HfCatalog.plan(it, System.currentTimeMillis())) }
            .onFailure { failure ->
                getSystemService(NotificationManager::class.java).notify(
                    ALERT_ID,
                    NotificationCompat.Builder(this, CHANNEL_ALERTS)
                        .setSmallIcon(R.drawable.ic_stat_serve)
                        .setContentTitle(getString(R.string.alert_download_failed, file))
                        .setContentText(failure.message ?: getString(R.string.unknown_error))
                        .setAutoCancel(true)
                        .build(),
                )
            }
    }

    /** What `tools/execuserve` passes over adb, persisted so a restart serves the same. */
    private suspend fun applyOverrides(intent: Intent) {
        val model = intent.getStringExtra(EXTRA_MODEL)
        // Stored as the installed id, which every screen compares against; the CLI passes an
        // alias, and a value stored as one before this was resolved is corrected here too.
        val startModel = (model ?: graph.settings.settings.first().defaultModel)?.let { installedId(it) }
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val bind = intent.getStringExtra(EXTRA_BIND)?.let { runCatching { BindMode.valueOf(it.uppercase()) }.getOrNull() }
        val threads = intent.getIntExtra(EXTRA_THREADS, -1)
        graph.settings.update { s ->
            s.copy(
                defaultModel = startModel,
                port = if (port in Choices.PORTS) port else s.port,
                bind = bind ?: s.bind,
                threads = if (threads >= 0) threads else s.threads,
            )
        }
        intent.getStringExtra(EXTRA_KEY)?.takeIf { it.length >= MIN_KEY_CHARS }?.let { graph.settings.putKey("adb", it) }
        if (graph.host.state.value is ServeHost.State.Running && (model != null || port > 0 || bind != null)) {
            graph.host.stop()
        }
    }

    /** The installed model [name] means, looking again once in case the CLI has just pushed it; else [name] as given. */
    private suspend fun installedId(name: String): String =
        (graph.models.resolve(name) ?: graph.models.rescan().let { graph.models.resolve(name) })?.id ?: name

    private fun observe() {
        lifecycleScope.launch {
            // The live settings, not the ones the server started with: the wake policy applies
            // without a restart, as the Settings screen implies.
            combine(graph.host.state, graph.host.status, graph.downloader.state, graph.settings.settings) { state, status, downloads, settings ->
                Observed(state, status, downloads.values.firstOrNull { it.active }, settings.wake)
            }.collect { seen ->
                applyLocks(seen.state, seen.status, seen.wake)
                notifyThrottled(buildNotification(seen.state, seen.status, seen.download))
                if (seen.state is ServeHost.State.Stopped && seen.download == null) stopIfIdle()
            }
        }
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = graph.host.refreshEndpoints()
            override fun onLost(network: Network) = graph.host.refreshEndpoints()
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
        lifecycleScope.launch { watchRestriction() }
    }

    /**
     * Android strips the foreground state of an app whose battery usage is set to
     * Restricted, silently, and the server then goes deaf in Doze (measured on the POCO:
     * `FGS stop call` 62 ms after the setting changed, then a 13.8 s timeout). The service
     * cannot win that back from the background, so it says so, once.
     */
    private suspend fun watchRestriction() {
        val activity = getSystemService(android.app.ActivityManager::class.java)
        while (true) {
            val restricted = activity.isBackgroundRestricted
            if (restricted && !warnedRestricted && graph.host.state.value is ServeHost.State.Running) {
                warnedRestricted = true
                getSystemService(NotificationManager::class.java).notify(
                    RESTRICTED_ID,
                    NotificationCompat.Builder(this, CHANNEL_ALERTS)
                        .setSmallIcon(R.drawable.ic_stat_serve)
                        .setContentTitle(getString(R.string.alert_restricted_title))
                        .setContentText(getString(R.string.alert_restricted_text))
                        .setContentIntent(
                            PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                        )
                        .setAutoCancel(true)
                        .build(),
                )
            }
            if (!restricted) warnedRestricted = false
            kotlinx.coroutines.delay(RESTRICTION_CHECK_MS)
        }
    }

    private fun applyLocks(state: ServeHost.State, status: EngineStatus?, wake: WakePolicy) {
        val running = state as? ServeHost.State.Running
        val busy = status != null && (status.lane != LaneState.IDLE || status.queued > 0)
        val wantCpu = running != null && (wake == WakePolicy.ALWAYS || busy)
        val wantWifi = running != null && running.settings.bind == BindMode.NETWORK
        if (wantCpu && wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ExecuServe:serve")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!wantCpu && wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (wantWifi && wifiLock?.isHeld != true) {
            // Honoured only while the screen is on and the app is in front; screen-off radio
            // behaviour is the platform's to decide.
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ExecuServe:serve")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!wantWifi && wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        if (running == null) advertiser.stop()
    }

    /** A native call that never returned: nothing else can run in this process until it does. */
    private fun onWedged() {
        lifecycleScope.launch {
            getSystemService(NotificationManager::class.java).notify(
                ALERT_ID,
                NotificationCompat.Builder(this@ServeService, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_serve)
                    .setContentTitle(getString(R.string.alert_wedged_title))
                    .setContentText(getString(R.string.alert_wedged_text))
                    .setAutoCancel(true)
                    .build(),
            )
            graph.settings.setWasServing(true)
            graph.settings.setWedged(true)
            // The lane thread is stuck in native code and cannot be interrupted; a new
            // process is the only clean state. START_STICKY brings the service back.
            Process.killProcess(Process.myPid())
        }
    }

    private fun stopIfIdle() {
        if (settled && graph.host.state.value is ServeHost.State.Stopped && !graph.downloader.busy) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private data class Observed(val state: ServeHost.State, val status: EngineStatus?, val download: DownloadState?, val wake: WakePolicy)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN means the user switched to a client app, not that memory is scarce.
        // Actual pressure is handled on the lane, never by closing a running native call.
        if (shouldEvictModelsForTrim(level)) {
            lifecycleScope.launch { graph.host.evictAll() }
        }
    }

    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        advertiser.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun promote(notification: Notification) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun notifyThrottled(notification: Notification) {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAt < NOTIFY_INTERVAL_MS) return
        lastNotifiedAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        state: ServeHost.State?,
        status: EngineStatus?,
        download: DownloadState? = null,
    ): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = Describe.notification(this, state, status, download)
        return NotificationCompat.Builder(this, CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_stat_serve)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (download != null && download.total > 0) {
                    setProgress(PROGRESS_MAX, (download.bytes * PROGRESS_MAX / download.total).toInt(), false)
                }
                if (state is ServeHost.State.Running) addAction(0, getString(R.string.action_stop), stop)
            }
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVER, getString(R.string.channel_server), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_server_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val ACTION_START = "org.experimentalmachines.execuserve.action.START"
        const val ACTION_STOP = "org.experimentalmachines.execuserve.action.STOP"
        const val ACTION_KEEP_ALIVE = "org.experimentalmachines.execuserve.action.KEEP_ALIVE"
        const val ACTION_PULL = "org.experimentalmachines.execuserve.action.PULL"
        const val ACTION_RESTART = "org.experimentalmachines.execuserve.action.RESTART"
        const val EXTRA_REPO = "repo"
        const val EXTRA_FILE = "file"
        const val EXTRA_MODEL = "model"
        const val EXTRA_PORT = "port"
        const val EXTRA_BIND = "bind"
        const val EXTRA_KEY = "key"
        const val EXTRA_THREADS = "threads"

        private const val CHANNEL_SERVER = "server"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2
        private const val RESTRICTED_ID = 3
        private const val RESTRICTION_CHECK_MS = 60_000L
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private const val PROGRESS_MAX = 1_000
        private const val MIN_KEY_CHARS = 16

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ServeService::class.java).setAction(ACTION_START))

        /** Stops and starts again in one command, on the settings as they are now. */
        fun restart(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ServeService::class.java).setAction(ACTION_RESTART))

        fun stop(context: Context) =
            context.startService(Intent(context, ServeService::class.java).setAction(ACTION_STOP))

        /** Keeps the process in the foreground while downloads run with the app off screen. */
        fun keepAlive(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ServeService::class.java).setAction(ACTION_KEEP_ALIVE))
    }
}
