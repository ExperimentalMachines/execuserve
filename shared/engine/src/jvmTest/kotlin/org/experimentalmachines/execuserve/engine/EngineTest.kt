package org.experimentalmachines.execuserve.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.experimentalmachines.execuserve.prompt.ChatMessage
import org.experimentalmachines.execuserve.prompt.ChatRole
import org.experimentalmachines.execuserve.prompt.ToolDefinition
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineTest {

    private val laneExecutor = Executors.newSingleThreadExecutor { Thread(it, "lane") }
    private val lane = laneExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val env = MutableStateFlow(Environment())
    private var wedgedCalls = 0

    @AfterTest
    fun tearDown() {
        scope.cancel()
        laneExecutor.shutdownNow()
    }

    private fun model(id: String, family: String = "lfm2.5", window: Int? = 4096) =
        ModelEntry(id, ModelFiles("/models/$id.pte", "/models/$id.json"), family = family, contextLength = window)

    private fun engine(
        runtime: FakeRuntime,
        models: List<ModelEntry> = listOf(model("lfm")),
        config: EngineConfig = EngineConfig(),
    ) = Engine(runtime, StaticModelSource(models), lane, scope, config, env, onWedged = { wedgedCalls++ })
        .also { it.start() }

    private fun user(text: String) = ChatMessage.text(ChatRole.USER, text)

    private fun assistant(text: String) = ChatMessage.text(ChatRole.ASSISTANT, text)

    private fun chat(
        vararg messages: ChatMessage,
        model: String = "lfm",
        client: String = "c1",
        maxTokens: Int? = null,
        stop: List<String> = emptyList(),
        tools: List<ToolDefinition> = emptyList(),
        thinking: Boolean? = false,
    ) = GenerationRequest(
        model = model,
        input = PromptInput.Chat(messages.toList(), tools, thinking),
        maxTokens = maxTokens,
        stop = stop,
        client = ClientId(client, client),
    )

    private data class Collected(val events: List<JobEvent>, val end: JobEvent) {
        val content get() = events.filterIsInstance<JobEvent.Delta>().joinToString("") { it.content }
        val reasoning get() = events.filterIsInstance<JobEvent.Delta>().joinToString("") { it.reasoning }
        val result get() = (end as JobEvent.Finished).result
        val failure get() = (end as JobEvent.Failed).failure
    }

    private suspend fun Job.collect(): Collected {
        val events = mutableListOf<JobEvent>()
        for (event in stream) events += event
        return Collected(events, outcome.await())
    }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun streamsTextAndNeverShowsTheEndMarker() = test {
        val runtime = FakeRuntime(reply = { listOf("Hel", "lo", "<|im_end|>") })
        val engine = engine(runtime)
        val run = engine.submit(chat(user("Hi"))).collect()
        assertEquals("Hello", run.content)
        assertFalse(run.events.any { it is JobEvent.Delta && "<|" in it.content })
        assertEquals(FinishReason.STOP, run.result.finishReason)
        assertEquals(3, run.result.completionTokens)
        assertIs<JobEvent.Started>(run.events.first())
    }

    @Test
    fun aSecondTurnFeedsOnlyItsSuffix() = test {
        val runtime = FakeRuntime(reply = { listOf("Hello", "<|im_end|>") })
        val engine = engine(runtime)
        engine.submit(chat(user("Hi"))).collect()
        runtime.log.clear()
        val second = engine.submit(chat(user("Hi"), assistant("Hello"), user("Again"))).collect()
        val generates = runtime.log.filter { it.startsWith("generate ") }
        assertEquals(1, generates.size)
        assertTrue(generates.single().removePrefix("generate ").startsWith("<|im_end|>"), generates.single())
        assertFalse("reset" in runtime.log)
        assertTrue(second.result.cachedTokens > 0)
        // The runtime holds exactly what a fresh render of the whole conversation would.
        val session = runtime.sessions.single()
        assertTrue(session.held.toString().endsWith("<|im_start|>assistant\nHello"))
    }

    @Test
    fun anotherKeyNeverContinuesACachedTurn() = test {
        val runtime = FakeRuntime(reply = { listOf("Hello", "<|im_end|>") })
        val engine = engine(runtime)
        engine.submit(chat(user("Hi"), client = "alice")).collect()
        // Bob sends a conversation that begins exactly as Alice's did.
        val bob = engine.submit(chat(user("Hi"), assistant("Hello"), user("Again"), client = "bob")).collect()
        assertEquals(0, bob.result.cachedTokens, "bob would learn what alice asked from cached_tokens")
        // Bob's turn is now what the runtime holds, so Alice starts over too; her own follow-up
        // to her own turn is the case reuse is for, and it works when nobody came between.
        engine.submit(chat(user("Hi"), client = "alice")).collect()
        val alice = engine.submit(chat(user("Hi"), assistant("Hello"), user("Again"), client = "alice")).collect()
        assertTrue(alice.result.cachedTokens > 0)
    }

    @Test
    fun aReplyCutByTheBudgetIsNeverExtended() = test {
        val runtime = FakeRuntime(reply = { listOf("One", " two", " three", "<|im_end|>") })
        val engine = engine(runtime)
        val first = engine.submit(chat(user("Count"), maxTokens = 2)).collect()
        assertEquals(FinishReason.LENGTH, first.result.finishReason)
        assertEquals("One two", first.content)
        runtime.log.clear()
        engine.submit(chat(user("Count"), assistant("One two"), user("More"))).collect()
        assertTrue("reset" in runtime.log, runtime.log.toString())
    }

    @Test
    fun aStopStringEndsTheReplyAndIsNotReused() = test {
        val runtime = FakeRuntime(reply = { listOf("alpha", " END", " beta", "<|im_end|>") })
        val engine = engine(runtime)
        val run = engine.submit(chat(user("Go"), stop = listOf(" END"))).collect()
        assertEquals("alpha", run.content)
        assertEquals(FinishReason.STOP, run.result.finishReason)
        runtime.log.clear()
        engine.submit(chat(user("Go"), assistant("alpha"), user("More"))).collect()
        assertTrue("reset" in runtime.log)
    }

    @Test
    fun reasoningIsSplitFromTheAnswer() = test {
        val runtime = FakeRuntime(reply = { listOf("<think>", "\nLet me", " think.", "\n</th", "ink>", "\n\nAnswer", "<|im_end|>") })
        val engine = engine(runtime, models = listOf(model("qwen", family = "qwen3")))
        val run = engine.submit(chat(user("Q"), model = "qwen", thinking = true)).collect()
        assertEquals("Let me think.\n", run.reasoning)
        assertEquals("Answer", run.content)
        assertEquals("Answer", run.result.content)
    }

    @Test
    fun toolCallsAreParsedAndNeverStreamedAsContent() = test {
        val runtime = FakeRuntime(
            reply = {
                listOf(
                    "Sure. ", "<tool_call>", "\n{\"name\": \"search\", ", "\"arguments\": {\"q\": \"tides\"}}\n", "</tool_call>",
                    "<|im_end|>",
                )
            },
        )
        val engine = engine(runtime, models = listOf(model("qwen", family = "qwen3")))
        val tool = ToolDefinition("search", "Search the web", """{"type":"object","properties":{"q":{"type":"string"}}}""")
        val run = engine.submit(chat(user("Tides?"), model = "qwen", tools = listOf(tool))).collect()
        assertEquals("Sure. ", run.content)
        assertEquals(FinishReason.TOOL_CALLS, run.result.finishReason)
        val call = run.result.toolCalls.single()
        assertEquals("search", call.name)
        assertEquals("""{"q": "tides"}""", call.argumentsJson)
        assertTrue(call.id.startsWith("call_"))
    }

    @Test
    fun aCallThatDoesNotParseIsReleasedAsContent() = test {
        val runtime = FakeRuntime(reply = { listOf("<tool_call>", "not json", "</tool_call>", "<|im_end|>") })
        val engine = engine(runtime, models = listOf(model("qwen", family = "qwen3")))
        val tool = ToolDefinition("search", "Search", """{"type":"object"}""")
        val run = engine.submit(chat(user("x"), model = "qwen", tools = listOf(tool))).collect()
        assertEquals("<tool_call>not json</tool_call>", run.content)
        assertEquals(FinishReason.STOP, run.result.finishReason)
    }

    @Test
    fun aLongPromptIsFedAheadInPiecesUnderThePrefillBound() = test {
        val runtime = FakeRuntime(prefillLength = 101)
        val engine = engine(runtime)
        val long = (1..120).joinToString(" ") { "word$it" }
        engine.submit(chat(user(long))).collect()
        val calls = runtime.log.filter { it.startsWith("prefill ") || it.startsWith("generate ") }
        assertTrue(calls.size > 2, calls.toString())
        calls.forEach { assertTrue(it.substringAfter(' ').length <= 100, it) }
        val fed = calls.joinToString("") { it.substringAfter(' ') }
        assertTrue(fed.contains(long))
    }

    @Test
    fun theQueueRefusesWhenFull() = test {
        val runtime = FakeRuntime()
        val gate = CountDownLatch(1)
        runtime.hang = gate
        val engine = engine(runtime, config = EngineConfig(maxQueued = 1, maxPerClient = 10))
        val running = engine.submit(chat(user("a")))
        engine.status.first { it.running != null }
        val waiting = engine.submit(chat(user("b")))
        val refusal = assertFailsWith<Refusal.QueueFull> { engine.submit(chat(user("c"))) }
        assertTrue(refusal.retryAfterMs >= 1_000)
        gate.countDown()
        running.collect()
        waiting.collect()
        assertEquals(1, engine.status.value.totals.refused)
    }

    @Test
    fun oneClientCannotHoldEverySlot() = test {
        val runtime = FakeRuntime()
        val gate = CountDownLatch(1)
        runtime.hang = gate
        val engine = engine(runtime, config = EngineConfig(maxPerClient = 2))
        engine.submit(chat(user("a"), client = "greedy"))
        engine.submit(chat(user("b"), client = "greedy"))
        assertFailsWith<Refusal.ClientLimit> { engine.submit(chat(user("c"), client = "greedy")) }
        // Another client is still admitted.
        val other = engine.submit(chat(user("d"), client = "polite"))
        gate.countDown()
        assertIs<JobEvent.Finished>(other.collect().end)
    }

    @Test
    fun aQueuedRequestTimesOutWithoutReachingTheLane() = test {
        val runtime = FakeRuntime()
        val gate = CountDownLatch(1)
        runtime.hang = gate
        val engine = engine(runtime, config = EngineConfig(queueTimeoutMs = 300))
        engine.submit(chat(user("a")))
        engine.status.first { it.running != null }
        val waiting = engine.submit(chat(user("b"), client = "c2")).collect()
        assertEquals(FailureKind.QUEUE_TIMEOUT, waiting.failure.kind)
        assertTrue(waiting.events.isEmpty())
        gate.countDown()
    }

    @Test
    fun aRequestCancelledWhileQueuedNeverRuns() = test {
        val runtime = FakeRuntime()
        val gate = CountDownLatch(1)
        runtime.hang = gate
        val engine = engine(runtime)
        val first = engine.submit(chat(user("a")))
        engine.status.first { it.running != null }
        val second = engine.submit(chat(user("never"), client = "c2"))
        second.cancel(FailureKind.CLIENT_GONE)
        gate.countDown()
        first.collect()
        assertEquals(FailureKind.CLIENT_GONE, second.collect().failure.kind)
        assertFalse(runtime.log.any { "never" in it })
    }

    @Test
    fun cancellingMidGenerationStopsWithinAToken() = test {
        val runtime = FakeRuntime(reply = { List(500) { " t$it" } })
        runtime.tokenDelayMs = 5
        val engine = engine(runtime)
        val job = engine.submit(chat(user("go")))
        var seen = 0
        for (event in job.stream) {
            if (event is JobEvent.Delta) {
                seen++
                if (seen == 3) job.cancel(FailureKind.CLIENT_GONE)
            }
        }
        val end = job.outcome.await()
        assertEquals(FailureKind.CLIENT_GONE, (end as JobEvent.Failed).failure.kind)
        assertTrue(seen < 10, "saw $seen deltas")
        // The next request starts clean.
        runtime.log.clear()
        engine.submit(chat(user("go"), assistant(" t0 t1"), user("again"))).collect()
        assertTrue("reset" in runtime.log)
    }

    @Test
    fun theLanePrefersAResidentModelWithinTheAffinityWindow() = test {
        val runtime = FakeRuntime()
        val gate = CountDownLatch(1)
        runtime.hang = gate
        val engine = engine(
            runtime,
            models = listOf(model("a"), model("b")),
            config = EngineConfig(maxAffinityWaitMs = 60_000, maxPerClient = 10),
        )
        val first = engine.submit(chat(user("1"), model = "a"))
        engine.status.first { it.running != null }
        val onB = engine.submit(chat(user("2"), model = "b"))
        val onA = engine.submit(chat(user("3"), model = "a"))
        gate.countDown()
        first.collect()
        onA.collect()
        onB.collect()
        val order = runtime.log.filter { it.startsWith("generate") }.map { it.substringAfter("user\n").take(1) }
        assertEquals(listOf("1", "3", "2"), order)
    }

    @Test
    fun affinityGivesWayOnceTheOldestHasWaitedLongEnough() = test {
        val runtime = FakeRuntime()
        val gate = CountDownLatch(1)
        runtime.hang = gate
        val engine = engine(
            runtime,
            models = listOf(model("a"), model("b")),
            config = EngineConfig(maxAffinityWaitMs = 0, maxPerClient = 10),
        )
        val first = engine.submit(chat(user("1"), model = "a"))
        engine.status.first { it.running != null }
        val onB = engine.submit(chat(user("2"), model = "b"))
        val onA = engine.submit(chat(user("3"), model = "a"))
        gate.countDown()
        first.collect()
        onB.collect()
        onA.collect()
        val order = runtime.log.filter { it.startsWith("generate") }.map { it.substringAfter("user\n").take(1) }
        assertEquals(listOf("1", "2", "3"), order)
        assertTrue("close /models/a.pte" in runtime.log, "one resident model at a time")
    }

    @Test
    fun aModelThatFailsToLoadIsReportedAndRemembered() = test {
        val runtime = FakeRuntime()
        runtime.broken["/models/lfm.pte"] = "Program version 9 is not supported"
        val engine = engine(runtime)
        val run = engine.submit(chat(user("x"))).collect()
        assertEquals(FailureKind.MODEL_UNAVAILABLE, run.failure.kind)
        assertTrue("version 9" in run.failure.message)
        assertTrue("lfm" in engine.status.value.broken)
        assertFailsWith<Refusal.Unavailable> { engine.submit(chat(user("y"))) }
        runtime.broken.clear()
        engine.forgetFailures()
        assertIs<JobEvent.Finished>(engine.submit(chat(user("z"))).collect().end)
    }

    @Test
    fun anOverflowBeforeAnyTokenIsAContextError() = test {
        val runtime = FakeRuntime(window = 60)
        val engine = engine(runtime, models = listOf(model("lfm", window = 60)))
        val run = engine.submit(chat(user("x".repeat(100)))).collect()
        assertEquals(FailureKind.CONTEXT_OVERFLOW, run.failure.kind)
    }

    @Test
    fun aPromptFarPastTheWindowIsRefusedUpFront() = test {
        val engine = engine(FakeRuntime(), models = listOf(model("lfm", window = 10)))
        assertFailsWith<Refusal.TooLong> { engine.submit(chat(user("x".repeat(400)))) }
    }

    @Test
    fun aClientThatStopsReadingIsCancelledNotBuffered() = test {
        val runtime = FakeRuntime(reply = { List(200) { " t$it" } })
        val engine = engine(runtime, config = EngineConfig(eventBuffer = 4))
        val job = engine.submit(chat(user("go")))
        val end = job.outcome.await()
        assertEquals(FailureKind.SLOW_CLIENT, (end as JobEvent.Failed).failure.kind)
    }

    @Test
    fun theDeadlineCancelsARunningJob() = test {
        val runtime = FakeRuntime(reply = { List(1_000) { " t$it" } })
        runtime.tokenDelayMs = 10
        val engine = engine(runtime, config = EngineConfig(requestTimeoutMs = 300))
        val t0 = System.currentTimeMillis()
        val run = engine.submit(chat(user("go"))).collect()
        if (run.end is JobEvent.Finished) println("DEBUG finished after ${System.currentTimeMillis() - t0} ms: ${run.result.finishReason} tokens=${run.result.completionTokens} content=${run.content.take(80)}")
        assertEquals(FailureKind.DEADLINE, run.failure.kind)
    }

    @Test
    fun aNativeCallThatNeverReturnsWedgesTheEngine() = test {
        val runtime = FakeRuntime()
        val gate = CountDownLatch(1)
        runtime.hang = gate
        val engine = engine(runtime, config = EngineConfig(wedgeGraceMs = 300))
        val job = engine.submit(chat(user("go")))
        engine.status.first { it.running != null }
        job.cancel(FailureKind.CLIENT_GONE)
        engine.status.first { it.lane == LaneState.WEDGED }
        assertEquals(1, wedgedCalls)
        assertFailsWith<Refusal.Unavailable> { engine.submit(chat(user("next"), client = "c2")) }
        gate.countDown()
    }

    @Test
    fun aHotPhoneRefusesNewWorkAndACriticalOneCancelsTheCurrent() = test {
        val runtime = FakeRuntime(reply = { List(1_000) { " t$it" } })
        runtime.tokenDelayMs = 5
        val engine = engine(runtime)
        val job = engine.submit(chat(user("go")))
        engine.status.first { it.running != null }
        env.value = Environment(thermal = ThermalLevel.SEVERE)
        engine.status.first { it.admission == Admission.PAUSED_THERMAL }
        assertFailsWith<Refusal.Paused> { engine.submit(chat(user("more"), client = "c2")) }
        env.value = Environment(thermal = ThermalLevel.CRITICAL)
        assertEquals(FailureKind.OVERHEATED, job.collect().failure.kind)
        env.value = Environment()
        engine.status.first { it.admission == Admission.OPEN }
    }

    @Test
    fun stoppingFailsWhatIsQueuedAndClosesEveryModel() = test {
        val runtime = FakeRuntime(reply = { List(1_000) { " t$it" } })
        runtime.tokenDelayMs = 5
        val engine = engine(runtime)
        val running = engine.submit(chat(user("a")))
        engine.status.first { it.running != null }
        val queued = engine.submit(chat(user("b"), client = "c2"))
        engine.stop(graceMs = 100)
        assertEquals(FailureKind.SHUTTING_DOWN, queued.collect().failure.kind)
        assertEquals(FailureKind.SHUTTING_DOWN, running.collect().failure.kind)
        assertTrue(runtime.sessions.all { it.closed })
        assertFailsWith<Refusal.Unavailable> { engine.submit(chat(user("c"))) }
    }

    @Test
    fun explicitLoadAndUnloadGoThroughTheLane() = test {
        val runtime = FakeRuntime()
        val engine = engine(runtime)
        engine.load("lfm")
        assertEquals(listOf("lfm"), engine.status.value.resident.map { it.id })
        engine.unload("lfm")
        assertTrue(engine.status.value.resident.isEmpty())
        assertTrue(runtime.sessions.single().closed)
    }

    @Test
    fun aModelIsFoundByAliasOrFileName() = test {
        val entry = model("qwen3-1.7b-8da4w-gptq-2k", family = "qwen3").copy(aliases = setOf("qwen3-1.7b"))
        val engine = engine(FakeRuntime(), models = listOf(entry))
        assertIs<JobEvent.Finished>(engine.submit(chat(user("x"), model = "qwen3-1.7b")).collect().end)
        assertIs<JobEvent.Finished>(engine.submit(chat(user("x"), model = "qwen3-1.7b-8da4w-gptq-2k.pte")).collect().end)
        assertFailsWith<Refusal.UnknownModel> { engine.submit(chat(user("x"), model = "llama")) }
    }

    @Test
    fun theLedgerTurnsAQwen3FollowUpIntoACacheHit() = test {
        // Reasoning off: the prompt ends in an empty think block that Qwen3's template never
        // writes back into history, so only the ledger can make the second turn a hit.
        val runtime = FakeRuntime(reply = { listOf("Blue", ".", "<|im_end|>") })
        val engine = engine(runtime, models = listOf(model("qwen", family = "qwen3")))
        val first = engine.submit(chat(user("Name a colour."), model = "qwen", thinking = false)).collect()
        assertEquals("Blue.", first.content)
        runtime.log.clear()
        val second = engine.submit(
            chat(user("Name a colour."), assistant("Blue."), user("Another."), model = "qwen", thinking = false),
        ).collect()
        assertFalse("reset" in runtime.log, runtime.log.toString())
        assertTrue(second.result.cachedTokens > 0)
        val fed = runtime.log.filter { it.startsWith("generate") || it.startsWith("prefill") }.joinToString("")
        assertTrue(fed.startsWith("generate <|im_end|>"), fed)
    }

    @Test
    fun aToolLoopFeedsOnlyTheToolResult() = test {
        var turn = 0
        val runtime = FakeRuntime(
            reply = {
                turn++
                if (turn == 1) {
                    listOf("<tool_call>", "\n{\"name\": \"search\", \"arguments\": {\"q\": \"tides\"}}\n", "</tool_call>", "<|im_end|>")
                } else {
                    listOf("High", " tide", " at", " six", ".", "<|im_end|>")
                }
            },
        )
        val engine = engine(runtime, models = listOf(model("qwen", family = "qwen3")))
        val tool = ToolDefinition("search", "Search", """{"type":"object","properties":{"q":{"type":"string"}}}""")
        val call = engine.submit(chat(user("Tides?"), model = "qwen", tools = listOf(tool), thinking = true)).collect()
        val made = call.result.toolCalls.single()
        runtime.log.clear()
        // What an OpenAI client sends back: the call as structure, and the tool's answer.
        val template = engine.templateFor(engine.resolve("qwen")!!)!!
        val history = org.experimentalmachines.execuserve.prompt.HistoryText.of("", listOf(made), template)
        val answer = engine.submit(
            chat(
                user("Tides?"), assistant(history), ChatMessage.toolResult(made.id, "High tide 06:12"),
                model = "qwen", tools = listOf(tool), thinking = true,
            ),
        ).collect()
        assertEquals("High tide at six.", answer.content)
        assertFalse("reset" in runtime.log, runtime.log.toString())
        val fed = runtime.log.filter { it.startsWith("generate") || it.startsWith("prefill") }.joinToString("")
        assertTrue("High tide 06:12" in fed && "<tools>" !in fed, fed)
    }

    @Test
    fun reasoningBeforeTheLatestQuestionIsNotWrittenBackByDefault() = test {
        val runtime = FakeRuntime(reply = { listOf("<think>", "\nhmm", "\n</think>", "\n\nBlue", "<|im_end|>") })
        val engine = engine(runtime, models = listOf(model("qwen", family = "qwen3")))
        engine.submit(chat(user("Colour?"), model = "qwen", thinking = true)).collect()
        runtime.log.clear()
        engine.submit(chat(user("Colour?"), assistant("Blue"), user("Another?"), model = "qwen", thinking = true)).collect()
        assertTrue("reset" in runtime.log, "the model must not read reasoning its template drops")
    }

    @Test
    fun aFailedExplicitLoadIsAnError() = test {
        val runtime = FakeRuntime()
        runtime.broken["/models/lfm.pte"] = "bad program"
        val engine = engine(runtime)
        assertFailsWith<RuntimeFailure> { engine.load("lfm") }
        assertEquals(LaneState.IDLE, engine.status.value.lane)
    }

    @Test
    fun textAroundAToolCallIsKept() = test {
        val runtime = FakeRuntime(
            reply = { listOf("Looking. ", "<tool_call>", "{\"name\": \"search\", \"arguments\": {}}", "</tool_call>", " Back soon.", "<|im_end|>") },
        )
        val engine = engine(runtime, models = listOf(model("qwen", family = "qwen3")))
        val tool = ToolDefinition("search", "Search", """{"type":"object"}""")
        val run = engine.submit(chat(user("x"), model = "qwen", tools = listOf(tool))).collect()
        assertEquals("Looking. \nBack soon.", run.content)
        assertEquals("Looking. \nBack soon.", run.result.content)
        assertEquals(1, run.result.toolCalls.size)
    }

    @Test
    fun aRawCompletionNeverExtendsTheCache() = test {
        val runtime = FakeRuntime(reply = { listOf(" world", "<|im_end|>") })
        val engine = engine(runtime)
        val request = GenerationRequest("lfm", PromptInput.Raw("Hello"), client = ClientId("c", "c"))
        assertEquals(" world", engine.submit(request).collect().content)
        runtime.log.clear()
        engine.submit(request.copy(input = PromptInput.Raw("Hello world, again"))).collect()
        assertTrue("reset" in runtime.log)
        delay(1)
    }
}

class RutGuardTest {
    @Test
    fun aModelRepeatingOneTokenIsCutAsLength() = runBlocking {
        val pipeline = TokenPipeline(listOf("<|im_end|>"), emptyList(), Int.MAX_VALUE, startsInThought = false, toolsOffered = false)
        var cutAt = -1
        for (i in 1..100) {
            pipeline.accept(" tok")
            if (pipeline.shouldStop) {
                cutAt = i
                break
            }
        }
        assertEquals(TokenPipeline.MAX_TOKEN_RUT + 1, cutAt)
        assertTrue(pipeline.cut)
    }
}
