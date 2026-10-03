package org.experimentalmachines.execuserve.host

import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelFiles
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelNamesTest {
    private fun entry(id: String, alias: String? = null) = ModelEntry(id, ModelFiles("$id.pte", "$id.json"), aliases = setOfNotNull(alias))

    private val lfm32k = entry("lfm2.5-1.2b-instruct-8da4w-gptq-32k", "lfm2.5-1.2b-instruct")
    private val lfm4k = entry("lfm2.5-1.2b-instruct-8da4w-gptq-4k", "lfm2.5-1.2b-instruct")
    private val qwen = entry("qwen3-1.7b-8da4w-gptq-4k", "qwen3-1.7b")
    private val bare = entry("custom-export")

    @Test
    fun anAliasIsShownUnlessTwoModelsShareIt() {
        val installed = listOf(lfm32k, lfm4k, qwen, bare)
        assertEquals("qwen3-1.7b", ModelNames.shown(qwen, installed))
        assertEquals(lfm32k.id, ModelNames.shown(lfm32k, installed))
        assertEquals(lfm4k.id, ModelNames.shown(lfm4k, installed))
        assertEquals("custom-export", ModelNames.shown(bare, installed))
        // Alone, the shared alias is fine again.
        assertEquals("lfm2.5-1.2b-instruct", ModelNames.shown(lfm4k, listOf(lfm4k, qwen)))
    }

    @Test
    fun historyIdsFollowTheSameRule() {
        val ids = listOf(lfm32k.id, lfm4k.id, qwen.id, bare.id)
        val aliases = listOf(lfm32k, lfm4k, qwen, bare).associate { it.id to it.aliases.firstOrNull() }
        assertEquals(
            mapOf(lfm32k.id to lfm32k.id, lfm4k.id to lfm4k.id, qwen.id to "qwen3-1.7b", bare.id to bare.id),
            ModelNames.shown(ids, aliases::get),
        )
        assertEquals(mapOf(lfm4k.id to "lfm2.5-1.2b-instruct"), ModelNames.shown(listOf(lfm4k.id), aliases::get))
    }

    @Test
    fun residentModelsComeFirstThenTheDefault() {
        val installed = listOf(lfm32k, lfm4k, qwen, bare)
        assertEquals(listOf(qwen, bare, lfm32k, lfm4k), ModelNames.hostedOrder(installed, resident = setOf(qwen.id), defaultModel = bare.id))
        assertEquals(installed, ModelNames.hostedOrder(installed, resident = emptySet(), defaultModel = null))
    }
}
