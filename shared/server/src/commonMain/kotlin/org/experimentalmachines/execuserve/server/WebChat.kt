package org.experimentalmachines.execuserve.server

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
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
        "/chat/assets/qr.js" to (WebAssets.qr to ContentType.Application.JavaScript),
        "/chat/assets/mark.svg" to (WebAssets.mark to ContentType.parse("image/svg+xml")),
    )
    assets.forEach { (path, asset) ->
        get(path) {
            call.webHeaders()
            call.respondText(asset.first, asset.second)
        }
    }
}

/** Assets stay at their absolute shared URLs; the frontend derives its API mount from the pathname. */
internal fun Route.modelChat(ctx: ServerContext) {
    get("/") {
        call.handle {
            hostedModel(ctx)
            webHeaders()
            respondText(WebAssets.html, ContentType.Text.Html)
        }
    }
}

private fun ApplicationCall.webHeaders() {
    response.header("Cache-Control", "no-cache")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Frame-Options", "DENY")
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    )
}
