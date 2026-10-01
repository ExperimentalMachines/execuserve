package org.experimentalmachines.execuserve.server

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Public shell; model discovery and inference retain the API's authentication. */
internal fun Route.webChat() {
    val assets = mapOf(
        "/" to (WebAssets.html to ContentType.Text.Html),
        "/chat/assets/chat.css" to (WebAssets.css to ContentType.Text.CSS),
        "/chat/assets/chat.js" to (WebAssets.js to ContentType.Application.JavaScript),
        "/chat/assets/mark.svg" to (WebAssets.mark to ContentType.parse("image/svg+xml")),
    )
    assets.forEach { (path, asset) ->
        get(path) {
            call.response.header("Cache-Control", "no-cache")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
            call.respondText(asset.first, asset.second)
        }
    }
}
