package org.experimentalmachines.execuserve.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.experimentalmachines.execuserve.api.ApiError
import org.experimentalmachines.execuserve.api.HttpStatus

/**
 * The browser chat's sign-in by the phone's camera ([Pairings]). Open to callers with no key,
 * as the page has none yet, but each route needs a header of its own: a page on another site
 * cannot send one without the server's CORS approval, which these routes never give, so only
 * the chat page itself can start, wait on or cancel a pairing.
 */
internal fun Route.pairingRoutes(ctx: ServerContext) {
    post("/pair") {
        call.handle {
            requireHeader(START_HEADER)
            val started = ctx.pairings.start(request.local.remoteAddress, request.headers[HttpHeaders.UserAgent])
                ?: throw ApiError(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limit_error",
                    "Too many browsers are waiting to sign in. Try again in a few minutes.",
                    "pairing_busy",
                )
            noStore()
            respondJson(
                buildJsonObject {
                    put("pairing", started.request.id)
                    put("poll", started.pollToken)
                    put("code", Pairings.shown(started.request.code))
                    put("scan", Pairings.SCHEME + started.request.id)
                    put("expires_in", (started.request.expiresAtMs - ctx.nowSeconds() * MS) / MS)
                },
            )
        }
    }
    post("/pair/{id}/wait") {
        call.handle {
            val token = requireHeader(POLL_HEADER)
            noStore()
            respondOutcome(ctx.pairings.await(routeParameters["id"].orEmpty(), token, WAIT_MS))
        }
    }
    post("/pair/{id}/cancel") {
        call.handle {
            val token = requireHeader(POLL_HEADER)
            ctx.pairings.cancel(routeParameters["id"].orEmpty(), token)
            noStore()
            respondJson(buildJsonObject { put("status", "cancelled") })
        }
    }
}

/** The page's answer: the key once, still waiting, declined, or gone; refusals as errors. */
private suspend fun ApplicationCall.respondOutcome(outcome: Pairings.Outcome) {
    val status = when (outcome) {
        is Pairings.Outcome.Approved -> "approved"
        Pairings.Outcome.Pending -> "pending"
        Pairings.Outcome.Declined -> "declined"
        Pairings.Outcome.Gone -> "expired"
        Pairings.Outcome.WrongToken -> throw ApiError(HttpStatus.FORBIDDEN, "permission_error", "This pairing belongs to another page.", "pairing_forbidden")
        Pairings.Outcome.Busy -> throw ApiError(HttpStatus.CONFLICT, "invalid_request_error", "This pairing is already being waited on.", "pairing_busy")
    }
    respondJson(
        buildJsonObject {
            put("status", status)
            if (outcome is Pairings.Outcome.Approved) put("key", outcome.key)
        },
        if (outcome == Pairings.Outcome.Gone) HttpStatusCode.Gone else HttpStatusCode.OK,
    )
}

private fun ApplicationCall.requireHeader(name: String): String = request.headers[name]?.takeIf { it.isNotBlank() && it.length <= MAX_HEADER }
    ?: throw ApiError.badRequest("Missing the $name header.", code = "pairing_header")

private fun ApplicationCall.noStore() = response.header(HttpHeaders.CacheControl, "no-store")

private const val START_HEADER = "x-execuserve-pair"
private const val POLL_HEADER = "x-execuserve-pair-poll"
private const val MAX_HEADER = 128

/** Under CIO's idle timeout, so a quiet wait never looks like a dead connection. */
private const val WAIT_MS = 25_000L
private const val MS = 1000L
