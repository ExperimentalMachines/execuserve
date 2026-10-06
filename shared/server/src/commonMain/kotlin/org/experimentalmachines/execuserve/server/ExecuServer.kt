package org.experimentalmachines.execuserve.server

import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler

/** The server could not start; [message] says why in words a person can act on. */
class ServerStartFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The HTTP listener's lifecycle. The engine's is separate and longer: the listener can be
 * restarted on a new port or bind mode without unloading the model.
 */
class ExecuServer(private val ctx: ServerContext) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Where the server is listening, as `host:port`, once started. */
    var endpoints: List<String> = emptyList()
        private set

    /**
     * Starts listening. Loopback binds both `127.0.0.1` and `::1`, because clients that
     * resolve `localhost` to `::1` first would otherwise be refused; a phone without IPv6
     * loopback falls back to IPv4 alone. Network mode binds `::`, which is dual-stack.
     */
    suspend fun start() {
        check(server == null) { "Already started" }
        val settings = ctx.settings
        val attempts = when (settings.bind) {
            BindMode.LOOPBACK -> listOf(listOf("127.0.0.1", "::1"), listOf("127.0.0.1"))
            BindMode.NETWORK -> listOf(listOf("::"), listOf("0.0.0.0"))
        }
        var last: Throwable? = null
        for (hosts in attempts) {
            // CIO accepts in a coroutine of its own, which rethrows a failed bind after
            // startSuspend has already reported it here: with no handler, that second copy was
            // uncaught and took the app down whenever another app held the port.
            val config = serverConfig {
                parentCoroutineContext = CoroutineExceptionHandler { _, _ -> }
                module { execuServe(ctx) }
            }
            val candidate = embeddedServer(
                CIO,
                config,
                configure = {
                    hosts.forEach { h ->
                        connector {
                            host = h
                            port = settings.port
                        }
                    }
                    connectionIdleTimeoutSeconds = settings.idleTimeoutSeconds
                    shutdownGracePeriod = GRACE_MS
                    shutdownTimeout = TIMEOUT_MS
                },
            )
            try {
                candidate.startSuspend(wait = false)
                server = candidate
                endpoints = candidate.engine.resolvedConnectors().map { "${it.host}:${it.port}" }
                return
            } catch (failure: Throwable) {
                last = failure
                runCatching { candidate.stopSuspend(0, 0) }
            }
        }
        // CIO wraps the bind failure in its own cancellation: read the whole chain, causes and
        // suppressed alike, not the wrapper's "is cancelling".
        val chain = generateSequence(listOfNotNull(last)) { level ->
            level.flatMap { listOfNotNull(it.cause) + it.suppressedExceptions }.takeIf { it.isNotEmpty() }
        }
            .take(CAUSE_DEPTH).flatten().toList()
        val inUse = chain.any {
            "BindException" == it::class.simpleName || "in use" in it.message.orEmpty().lowercase() || "EADDRINUSE" in it.message.orEmpty()
        }
        val reason = chain.lastOrNull { !it.message.isNullOrBlank() }?.message.orEmpty()
        throw ServerStartFailure(
            if (inUse) {
                "Port ${settings.port} is already in use by another app."
            } else {
                "Could not listen on port ${settings.port}: $reason"
            },
            last,
        )
    }

    suspend fun stop() {
        server?.stopSuspend(GRACE_MS, TIMEOUT_MS)
        server = null
        endpoints = emptyList()
    }

    private companion object {
        const val GRACE_MS = 500L
        const val TIMEOUT_MS = 2_000L
        const val CAUSE_DEPTH = 8
    }
}
