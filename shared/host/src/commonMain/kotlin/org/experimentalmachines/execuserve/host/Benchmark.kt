package org.experimentalmachines.execuserve.host

import kotlinx.coroutines.flow.first
import org.experimentalmachines.execuserve.engine.ClientId
import org.experimentalmachines.execuserve.engine.Engine
import org.experimentalmachines.execuserve.engine.GenerationRequest
import org.experimentalmachines.execuserve.engine.JobEvent
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.PromptInput

/**
 * A short, repeatable measurement of one model, through the same engine path as any request.
 *
 * Built so that every number it shows is one the runtime reported. The prompt fits in one
 * runtime call, so its token count is the tokenizer's, not an estimate. It is a raw prompt,
 * which never continues the KV cache, so every repetition reads it cold. And the reply is a
 * sequence the model keeps extending, so it rarely stops before the budget, but the count
 * shown is what was generated, never the budget. Runs are recorded with the API
 * [API] and kept apart from ordinary traffic.
 */
object Benchmark {
    const val API = "benchmark"
    const val REPEATS = 3
    const val DECODE_TOKENS = 128
    private const val PROMPT_NUMBERS = 180

    private val CLIENT = ClientId("benchmark", "Benchmark")

    /** Numbers to continue: about 700 characters, one runtime call for every export we ship. */
    val prompt: String = "Continue the list without stopping.\n" + (1..PROMPT_NUMBERS).joinToString(", ") + ","

    /** Runs [model] [REPEATS] times; [onRun] hears each record as it lands. */
    suspend fun run(engine: Engine, model: String, onRun: (JobRecord) -> Unit = {}): List<JobRecord> {
        val records = mutableListOf<JobRecord>()
        repeat(REPEATS) {
            val job = engine.submit(
                GenerationRequest(
                    model = model,
                    input = PromptInput.Raw(prompt),
                    maxTokens = DECODE_TOKENS,
                    temperature = 0f,
                    client = CLIENT,
                    api = API,
                ),
            )
            for (event in job.stream) Unit
            val end = job.outcome.await()
            val record = engine.status.first { status -> status.recent.any { it.id == job.id } }.recent.first { it.id == job.id }
            records += record
            onRun(record)
            if (end is JobEvent.Failed) return records
        }
        return records
    }
}
