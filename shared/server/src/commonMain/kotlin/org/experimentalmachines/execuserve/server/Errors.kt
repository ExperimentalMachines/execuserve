package org.experimentalmachines.execuserve.server

import org.experimentalmachines.execuserve.api.ApiError
import org.experimentalmachines.execuserve.api.HttpStatus
import org.experimentalmachines.execuserve.api.Timings
import org.experimentalmachines.execuserve.api.ToolCallOut
import org.experimentalmachines.execuserve.api.Usage
import org.experimentalmachines.execuserve.engine.Failure
import org.experimentalmachines.execuserve.engine.FailureKind
import org.experimentalmachines.execuserve.engine.GenerationResult
import org.experimentalmachines.execuserve.engine.Refusal
import org.experimentalmachines.execuserve.engine.Units
import org.experimentalmachines.execuserve.prompt.ToolCall

/**
 * Refusals happen before anything is queued. The codes are OpenAI's where OpenAI has one,
 * so an SDK's retry and error classes do the right thing unmodified.
 */
internal fun refusalError(refusal: Refusal): ApiError {
    val message = refusal.message ?: "Refused"
    return when (refusal) {
        // The server is full, not this client over its share: 503, so a client does not read
        // it as its own quota (codex review, 2026-09-30). 429 stays for per-client limits.
        is Refusal.QueueFull -> ApiError.overloaded(message, seconds(refusal.retryAfterMs))
        is Refusal.ClientLimit -> ApiError.rateLimited(message, seconds(refusal.retryAfterMs))
        is Refusal.UnknownModel -> ApiError.modelNotFound(refusal.requested, refusal.installed)
        is Refusal.Unsupported -> ApiError.unsupported(refusal.param, message)
        is Refusal.Invalid -> ApiError.badRequest(message, refusal.param)
        is Refusal.TooLong -> ApiError.contextLength(message)
        is Refusal.Paused -> ApiError.overloaded(message, seconds(refusal.retryAfterMs))
        is Refusal.Unavailable -> ApiError.overloaded(message)
    }
}

/** A job that was accepted and then failed. */
internal fun failureError(failure: Failure): ApiError = when (failure.kind) {
    FailureKind.CONTEXT_OVERFLOW -> ApiError.contextLength(failure.message)
    FailureKind.QUEUE_TIMEOUT -> ApiError.overloaded(failure.message, QUEUE_RETRY_SECONDS)
    FailureKind.DEADLINE -> ApiError(HttpStatus.GATEWAY_TIMEOUT, "timeout_error", failure.message, "timeout")
    FailureKind.MODEL_UNAVAILABLE -> ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", failure.message, "model_load_failed")
    FailureKind.OVERHEATED -> ApiError.overloaded(failure.message, THERMAL_RETRY_SECONDS)
    FailureKind.SHUTTING_DOWN -> ApiError.overloaded(failure.message)
    FailureKind.CANCELLED -> ApiError(HttpStatus.SERVICE_UNAVAILABLE, "server_error", failure.message, "cancelled")
    FailureKind.CLIENT_GONE, FailureKind.SLOW_CLIENT -> ApiError(HttpStatus.REQUEST_TIMEOUT, "timeout_error", failure.message, "client_disconnected")
    FailureKind.RUNTIME -> ApiError.internal(failure.message)
}

internal fun usageOf(result: GenerationResult) = Usage(result.promptTokens, result.completionTokens, result.cachedTokens)

internal fun timingsOf(result: GenerationResult) = Timings(
    queueMs = result.timings.queueMs,
    loadMs = result.timings.loadMs,
    promptTokens = result.promptTokens - result.cachedTokens,
    promptMs = result.timings.prefillMs,
    predictedTokens = result.completionTokens,
    predictedMs = result.timings.decodeMs,
    cachedTokens = result.cachedTokens,
    firstTokenMs = result.timings.firstTokenMs,
)

internal fun toolCallOut(call: ToolCall) = ToolCallOut(call.id, call.name, call.argumentsJson)

/** Whole seconds, rounded up: a Retry-After of 0 would invite an immediate retry. */
private fun seconds(ms: Long): Int = ((ms + Units.MS_PER_SECOND - 1) / Units.MS_PER_SECOND).toInt().coerceAtLeast(1)

private const val QUEUE_RETRY_SECONDS = 5
private const val THERMAL_RETRY_SECONDS = 60
