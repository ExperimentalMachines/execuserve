package org.experimentalmachines.execuserve.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.experimentalmachines.execuserve.prompt.ChatMessage
import org.experimentalmachines.execuserve.prompt.ChatRole
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsTest {
    private fun run(
        model: String = "m",
        prompt: Int = 100,
        cached: Int = 0,
        completion: Int = 11,
        prefillMs: Long = 1_000,
        decodeMs: Long = 1_000,
        decodeWallMs: Long = decodeMs,
        firstTokenMs: Long = 1_200,
        totalMs: Long = 2_300,
        queueMs: Long = 100,
        finish: FinishReason? = FinishReason.STOP,
        threads: Int? = 7,
    ) = JobRecord(
        id = "j", model = model, client = "c", finishedAtMs = 1, finish = finish,
        failure = if (finish == null) FailureKind.RUNTIME else null,
        promptTokens = prompt, completionTokens = completion, cachedTokens = cached,
        queueMs = queueMs, totalMs = totalMs, prefillMs = prefillMs, decodeMs = decodeMs,
        decodeWallMs = decodeWallMs, firstTokenMs = firstTokenMs, threads = threads,
    )

    @Test
    fun percentilesAreValuesThatWereMeasured() {
        val spread = Spread.of((1..10).map { it.toDouble() })!!
        assertEquals(5.0, spread.median)
        assertEquals(9.0, spread.p90)
        assertEquals(10, spread.count)
        assertNull(Spread.of(emptyList()))
    }

    @Test
    fun ratesAreDerivedFromTheirOwnCountsAndTimes() {
        val r = run(prompt = 300, cached = 100, completion = 21, prefillMs = 2_000, decodeMs = 1_000)
        assertEquals(100.0, r.prefillTokensPerSecond)
        assertEquals(20.0, r.decodeTokensPerSecond)
    }

    @Test
    fun aConsistentRunHasNoDiscrepancies() {
        assertEquals(emptyList(), Metrics.discrepancies(run()))
    }

    @Test
    fun eachDisagreementIsNamed() {
        assertEquals(listOf(Discrepancy.DECODE_CLOCKS), Metrics.discrepancies(run(decodeMs = 1_000, decodeWallMs = 1_400)))
        assertEquals(listOf(Discrepancy.PHASES_EXCEED_FIRST_TOKEN), Metrics.discrepancies(run(prefillMs = 5_000, totalMs = 9_000)))
        assertTrue(Discrepancy.FIRST_TOKEN_AFTER_END in Metrics.discrepancies(run(firstTokenMs = 5_000, prefillMs = 100, totalMs = 2_000)))
        assertEquals(listOf(Discrepancy.CACHE_EXCEEDS_PROMPT), Metrics.discrepancies(run(prompt = 10, cached = 20, prefillMs = 0)))
        // Millisecond clocks read a moment apart are not a disagreement.
        assertEquals(emptyList(), Metrics.discrepancies(run(decodeMs = 100, decodeWallMs = 130)))
    }

    @Test
    fun cacheHitsNeverCountAsPrefillSpeed() {
        val summary = Metrics.summarize(listOf(run(prompt = 100, cached = 90, prefillMs = 10), run(prompt = 100, prefillMs = 1_000))).single()
        // The hit read 10 tokens in 10 ms, "1 000 tok/s"; only the miss's 100 tok/s is a prefill rate.
        assertEquals(100.0, summary.prefill.getValue(ContextBucket.SHORT).median)
        assertEquals(1, summary.prefill.getValue(ContextBucket.SHORT).count)
        assertEquals(1, summary.cacheHits)
    }

    @Test
    fun ratesAreKeptPerContextSize() {
        val summary = Metrics.summarize(
            listOf(run(prompt = 100, decodeMs = 400), run(prompt = 3_000, decodeMs = 1_000, prefillMs = 10_000, firstTokenMs = 10_200, totalMs = 11_300)),
        ).single()
        assertEquals(25.0, summary.decode.getValue(ContextBucket.SHORT).median)
        assertEquals(10.0, summary.decode.getValue(ContextBucket.LONG).median)
    }

    @Test
    fun failuresCountButNeverContributeRates() {
        val summary = Metrics.summarize(listOf(run(finish = null, decodeMs = 1), run())).single()
        assertEquals(2, summary.runs)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.decode.getValue(ContextBucket.SHORT).count)
    }

    private val lane = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        lane.shutdownNow()
    }

    /** The engine's own figures for a real run, cross-checked against each other. */
    @Test
    fun aRunThroughTheEngineAgreesWithItself() = runBlocking {
        withTimeout(20_000) {
            val runtime = FakeRuntime(prefillLength = 64, reply = { listOf("One", " two", " three", "<|im_end|>") })
            runtime.prefillDelayPerCharMs = 0.5
            runtime.tokenDelayMs = 20
            val engine = Engine(
                runtime,
                StaticModelSource(listOf(ModelEntry("m", ModelFiles("/m.pte", "/m.json"), "lfm2.5", contextLength = 4096))),
                lane.asCoroutineDispatcher(),
                scope,
            ).also { it.start() }
            val job = engine.submit(
                GenerationRequest("m", PromptInput.Chat(listOf(ChatMessage.text(ChatRole.USER, "word ".repeat(100)))), client = ClientId("k1", "alice")),
            )
            for (ignored in job.stream) Unit
            job.outcome.await()
            val record = engine.status.first { it.recent.isNotEmpty() }.recent.single()
            assertEquals(emptyList(), Metrics.discrepancies(record), record.toString())
            assertTrue(record.loadMs >= 0 && record.prefillMs > 0 && record.decodeMs > 0, record.toString())
            assertTrue(record.queueMs + record.loadMs + record.prefillMs <= record.firstTokenMs + 50, record.toString())
            assertEquals("k1", record.clientId)
            // Text fed ahead of the last runtime call is counted by estimate, and says so.
            assertTrue(record.estimatedPromptTokens in 1 until record.promptTokens, record.toString())
            engine.stop(0)
        }
    }
}
