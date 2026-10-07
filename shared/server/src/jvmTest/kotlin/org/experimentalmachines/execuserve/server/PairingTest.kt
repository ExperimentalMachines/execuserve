package org.experimentalmachines.execuserve.server

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.experimentalmachines.execuserve.engine.Engine
import org.experimentalmachines.execuserve.engine.StaticModelSource
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private var now = 1_000_000L
    private val pairings = Pairings(clock = { now })

    @Test
    fun anApprovedKeyGoesOnlyToThePageHoldingThePollTokenAndOnlyOnce() = runBlocking {
        val started =
            assertNotNull(pairings.start("192.168.1.5", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_0) AppleWebKit/605.1.15 Chrome/140.0 Safari/537.36"))
        assertEquals("Chrome on macOS", started.request.client)
        val found = assertNotNull(pairings.find(Pairings.SCHEME + started.request.id))
        assertEquals(started.request, found)
        // The poll token is not what the QR code or the phone sees.
        assertTrue(started.pollToken !in Pairings.SCHEME + started.request.id && started.pollToken != started.request.id)
        assertEquals(Pairings.Outcome.WrongToken, pairings.await(started.request.id, "guess", 10))
        assertEquals(Pairings.Outcome.Pending, pairings.await(started.request.id, started.pollToken, 10))
        assertTrue(pairings.approve(started.request.id, "es-secret"))
        assertEquals(Pairings.Outcome.Approved("es-secret"), pairings.await(started.request.id, started.pollToken, 10))
        assertEquals(Pairings.Outcome.Gone, pairings.await(started.request.id, started.pollToken, 10))
        assertNull(pairings.find(Pairings.SCHEME + started.request.id))
    }

    @Test
    fun aWaitingPageHearsTheAnswerAtOnce() = runBlocking {
        val started = assertNotNull(pairings.start("10.0.0.2", null))
        val waiting = async { pairings.await(started.request.id, started.pollToken, 5_000) }
        delay(50)
        assertTrue(pairings.decline(started.request.id))
        assertEquals(Pairings.Outcome.Declined, waiting.await())
    }

    @Test
    fun typedCodesFindTheirPairingAndExpiredOrCancelledOnesAreGone() = runBlocking {
        val started = assertNotNull(pairings.start("10.0.0.2", null))
        val shown = Pairings.shown(started.request.code)
        assertEquals(started.request.id, pairings.find(shown.lowercase())?.id)
        assertEquals(started.request.id, pairings.find(" " + shown.replace("-", " ") + " ")?.id)
        assertNull(pairings.find("EXECUSERVE-PAIR:nope"))
        assertNull(pairings.find("http://example.com/"))
        // Cancelling needs the token; then it is gone.
        assertTrue(!pairings.cancel(started.request.id, "guess"))
        val waiting = async { pairings.await(started.request.id, started.pollToken, 5_000) }
        delay(50)
        assertTrue(pairings.cancel(started.request.id, started.pollToken))
        assertEquals(Pairings.Outcome.Gone, waiting.await())
        val later = assertNotNull(pairings.start("10.0.0.2", null))
        now += Pairings.TTL_MS
        assertNull(pairings.find(Pairings.SCHEME + later.request.id))
        assertEquals(false, pairings.approve(later.request.id, "es-x"))
        assertEquals(Pairings.Outcome.Gone, pairings.await(later.request.id, later.pollToken, 10))
    }

    @Test
    fun oneWaitAtATimeAndNoKeyAfterExpiry() = runBlocking {
        val started = assertNotNull(pairings.start("10.0.0.3", null))
        val first = async { pairings.await(started.request.id, started.pollToken, 5_000) }
        delay(50)
        assertEquals(Pairings.Outcome.Busy, pairings.await(started.request.id, started.pollToken, 10))
        // Approved, but collected only after the pairing's three minutes: nothing is handed out.
        now += Pairings.TTL_MS
        assertTrue(!pairings.approve(started.request.id, "es-late"))
        first.cancel()
        val second = assertNotNull(pairings.start("10.0.0.3", null))
        assertTrue(pairings.approve(second.request.id, "es-late"))
        now += Pairings.TTL_MS
        val late = pairings.await(second.request.id, second.pollToken, 10)
        assertEquals(Pairings.Outcome.Gone, late)
        // The wait ended, so the next one is not refused as busy.
        val third = assertNotNull(pairings.start("10.0.0.3", null))
        assertEquals(Pairings.Outcome.Pending, pairings.await(third.request.id, third.pollToken, 10))
        assertEquals(Pairings.Outcome.Pending, pairings.await(third.request.id, third.pollToken, 10))
    }

    @Test
    fun oneAddressCannotCrowdOutTheRest() = runBlocking {
        repeat(Pairings.MAX_PER_ADDRESS) { assertNotNull(pairings.start("10.0.0.9", null)) }
        assertNull(pairings.start("10.0.0.9", null))
        assertNotNull(pairings.start("10.0.0.10", null))
        val codes = (0 until 20).mapNotNull { pairings.start("10.1.0.$it", null)?.request?.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { Pairings.normalCode(it) == it })
    }

    @Test
    fun userAgentsBecomeShortNames() {
        assertEquals("Firefox on Linux", Pairings.describe("Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"))
        assertEquals(
            "Safari on iOS",
            Pairings.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1"),
        )
        assertEquals("Edge on Windows", Pairings.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0 Safari/537.36 Edg/140.0"))
        assertEquals("A browser", Pairings.describe(null))
        assertEquals("A browser", Pairings.describe("<script>alert(1)</script>"))
    }

    // HTTP -----------------------------------------------------------------------------------

    private val laneExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        laneExecutor.shutdownNow()
    }

    @Test
    fun thePageStartsWaitsAndCollectsOverHttp() = testApplication {
        val engine = Engine(FakeRuntime(), StaticModelSource(emptyList()), laneExecutor.asCoroutineDispatcher(), scope)
        engine.start()
        val ctx = ServerContext(engine, ServerSettings(), StaticKeys(emptyList()), { emptySet() }, "test", { now / 1000 }, pairings = pairings)
        application { execuServe(ctx) }
        val http = createClient { defaultRequest { headers.append(HttpHeaders.Host, "localhost:8080") } }
        // Without the page's own header (what a form on another site would send): refused.
        assertEquals(HttpStatusCode.BadRequest, http.post("/pair").status)
        val start = http.post("/pair") { header("x-execuserve-pair", "1") }
        assertEquals(HttpStatusCode.OK, start.status)
        assertEquals("no-store", start.headers[HttpHeaders.CacheControl])
        val body = Json.parseToJsonElement(start.bodyAsText()).jsonObject
        val id = body["pairing"]!!.jsonPrimitive.content
        val poll = body["poll"]!!.jsonPrimitive.content
        assertEquals(Pairings.SCHEME + id, body["scan"]!!.jsonPrimitive.content)
        assertNotEquals(poll, id)
        assertEquals(HttpStatusCode.BadRequest, http.post("/pair/$id/wait").status)
        assertEquals(HttpStatusCode.Forbidden, http.post("/pair/$id/wait") { header("x-execuserve-pair-poll", "guess") }.status)
        assertTrue(pairings.approve(id, "es-paired"))
        val done = http.post("/pair/$id/wait") { header("x-execuserve-pair-poll", poll) }
        assertEquals("es-paired", Json.parseToJsonElement(done.bodyAsText()).jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Gone, http.post("/pair/$id/wait") { header("x-execuserve-pair-poll", poll) }.status)
        // The routes are only the pairing's: they open no door to the API.
        assertEquals(HttpStatusCode.Unauthorized, http.post("/v1/chat/completions") { header(HttpHeaders.Authorization, "Bearer $poll") }.status)
    }
}
