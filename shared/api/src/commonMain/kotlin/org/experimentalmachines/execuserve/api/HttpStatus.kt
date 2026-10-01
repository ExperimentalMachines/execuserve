package org.experimentalmachines.execuserve.api

/**
 * The HTTP statuses the wire contract uses, by name. This module stays free of Ktor, so it
 * keeps its own short list rather than borrowing `HttpStatusCode`.
 */
object HttpStatus {
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val REQUEST_TIMEOUT = 408
    const val PAYLOAD_TOO_LARGE = 413
    const val TOO_MANY_REQUESTS = 429
    const val INTERNAL_SERVER_ERROR = 500
    const val SERVICE_UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504
}
