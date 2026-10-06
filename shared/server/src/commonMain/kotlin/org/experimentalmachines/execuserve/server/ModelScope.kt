package org.experimentalmachines.execuserve.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingPipelineCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.experimentalmachines.execuserve.api.ApiError
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.ModelEntry

/** A route selects a model, not another scheduler or security boundary. */
internal fun ApplicationCall.hostedModel(ctx: ServerContext): ModelEntry? {
    val name = routeParameters["hostedModel"] ?: return null
    return ctx.engine.resolve(name) ?: throw ApiError.modelNotFound(name, emptyList())
}

/** Ktor's merged parameters prefer query values; only route captures may select models. */
internal val ApplicationCall.routeParameters
    get() = when (this) {
        is RoutingCall -> pathParameters
        is RoutingPipelineCall -> pathParameters
        else -> error("Model routes require a routing call")
    }

internal fun ApplicationCall.hostedModels(ctx: ServerContext): List<ModelEntry> = hostedModel(ctx)?.let(::listOf) ?: ctx.engine.installed()

/** Resolve aliases before comparing, so a scoped endpoint accepts its model's aliases. */
internal fun ApplicationCall.resolveModel(ctx: ServerContext, name: String): ModelEntry {
    val scope = hostedModel(ctx)
    // On a model's own endpoint, a name this server does not know (an app's fixed "gpt-4o",
    // or nothing) means that model: the base URL already chose it. Naming another installed
    // model is still a mistake worth saying.
    val entry = ctx.engine.resolve(name)
        ?: scope
        ?: throw ApiError.modelNotFound(name, hostedModels(ctx).map { it.id })
    if (scope != null && entry.id != scope.id) {
        throw ApiError.badRequest(
            "This endpoint hosts '${scope.id}'. Use its model ID or another model's endpoint.",
            "model",
            "model_endpoint_mismatch",
        )
    }
    return entry
}

/**
 * Queue, lane and totals remain global: all routes share the phone's compute budget. Another
 * key's request shows that the phone is busy and how far along it is, but not whose it is or
 * how long its prompt was; a key sees its own recent requests only (agy review).
 */
internal fun ApplicationCall.visibleStatus(ctx: ServerContext, clientId: String): EngineStatus {
    val scope = hostedModel(ctx)?.id
    val status = ctx.engine.status.value
    return status.copy(
        running = status.running?.takeIf { scope == null || it.model == scope }
            ?.let { if (it.clientId == clientId) it else it.copy(client = "", promptChars = 0, prefilledChars = 0) },
        resident = status.resident.filter { scope == null || it.id == scope },
        broken = status.broken.filterKeys { scope == null || it == scope },
        recent = status.recent.filter { it.clientId == clientId && (scope == null || it.model == scope) },
    )
}

internal fun ApplicationCall.statusBody(ctx: ServerContext, clientId: String): JsonObject {
    val body = StatusJson.of(visibleStatus(ctx, clientId), ctx.version, ctx.threads())
    return JsonObject(
        body + buildMap {
            put("scheduler_scope", JsonPrimitive("server"))
            hostedModel(ctx)?.let { put("model_scope", JsonPrimitive(it.id)) }
        },
    )
}
