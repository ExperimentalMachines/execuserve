package org.experimentalmachines.execuserve.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A refusal in OpenAI's error shape, with the HTTP status it travels under.
 *
 * The SDKs map the status to an exception class (`RateLimitError`, `BadRequestError`, ...)
 * and read `error.code` to tell cases apart, so both are chosen to match what OpenAI itself
 * returns for the same situation rather than what reads well.
 */
class ApiError(
    val status: Int,
    val type: String,
    override val message: String,
    val code: String? = null,
    val param: String? = null,
    /** Seconds, sent as `Retry-After` so clients back off by the server's estimate. */
    val retryAfterSeconds: Int? = null,
) : Exception(message) {

    fun toJson(): JsonObject = buildJsonObject {
        putJsonObject("error") {
            put("message", message)
            put("type", type)
            put("param", param)
            put("code", code)
        }
    }

    companion object {
        fun badRequest(message: String, param: String? = null, code: String? = null) =
            ApiError(400, "invalid_request_error", message, code, param)

        fun unsupported(param: String, message: String) =
            ApiError(400, "invalid_request_error", message, "unsupported_parameter", param)

        fun contextLength(message: String) =
            ApiError(400, "invalid_request_error", message, "context_length_exceeded", "messages")

        fun unauthorized() = ApiError(
            401,
            "authentication_error",
            "Missing or invalid API key. Send it as 'Authorization: Bearer <key>'.",
            "invalid_api_key",
        )

        fun modelNotFound(model: String, known: List<String>) = ApiError(
            404,
            "invalid_request_error",
            "The model '$model' is not installed on this server." +
                if (known.isEmpty()) " No models are installed." else " Installed: ${known.joinToString(", ")}.",
            "model_not_found",
            "model",
        )

        fun notFound(path: String) = ApiError(404, "invalid_request_error", "No route for $path", "not_found")

        fun tooLarge(limitBytes: Long) = ApiError(
            413,
            "invalid_request_error",
            "Request body is larger than this server accepts ($limitBytes bytes).",
            "request_too_large",
        )

        fun rateLimited(message: String, retryAfterSeconds: Int) =
            ApiError(429, "rate_limit_error", message, "rate_limit_exceeded", retryAfterSeconds = retryAfterSeconds)

        fun overloaded(message: String, retryAfterSeconds: Int? = null) =
            ApiError(503, "server_error", message, "server_overloaded", retryAfterSeconds = retryAfterSeconds)

        fun timeout(message: String) = ApiError(408, "timeout_error", message, "timeout")

        fun internal(message: String) = ApiError(500, "server_error", message, "internal_error")
    }
}
