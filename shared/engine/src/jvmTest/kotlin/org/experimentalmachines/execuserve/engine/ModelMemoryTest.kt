package org.experimentalmachines.execuserve.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against Qwen3-0.6B's CPU builds as the POCO X8 Pro Max (12 GB) measured them on 2026-10-07. */
class ModelMemoryTest {
    private val gib = 1L shl 30
    private val file = 496_570_368L

    @Test
    fun theWindowsCacheIsMostOfWhatAModelTakesAndItMatchesWhatThePhoneMeasured() {
        // Measured: 1.06 GiB at 2k, 7.55 GiB at 32k, from files of the same size.
        val small = ModelMemory.needBytes("qwen3-0.6b-8da4w-gptq-2k", 2048, file)!!
        val large = ModelMemory.needBytes("experimentalmachines/Qwen3-0.6B-ExecuTorch", 32_768, file)!!
        // Never below what the phone measured, and not far above it: erring high is the point.
        assertTrue(small in (1.06 * gib).toLong()..(1.06 * 1.2 * gib).toLong(), "$small")
        assertTrue(large in (7.55 * gib).toLong()..(7.55 * 1.1 * gib).toLong(), "$large")
        // A 12 GB phone can spare about 7.3 GiB: the 32k build does not fit, the 2k one does.
        val total = 11L * gib
        assertEquals(ModelMemory.Fit.WONT_FIT, ModelMemory.fit(large, total, 6 * gib))
        assertEquals(ModelMemory.Fit.COMFORTABLE, ModelMemory.fit(small, total, 6 * gib))
    }

    @Test
    fun theLongestNameWinsAndUnknownModelsGetNoEstimate() {
        assertEquals(ModelMemory.Shape(28, 2, 128), ModelMemory.shapeFor("Qwen2.5-Math-1.5B-Instruct-ExecuTorch"))
        assertEquals(ModelMemory.Shape(6, 8, 64), ModelMemory.shapeFor("lfm2.5-1.2b-instruct-heretic-8da4w-2k"))
        assertNull(ModelMemory.needBytes("gemma-2b", 2048, file))
        assertNull(ModelMemory.needBytes("qwen3-0.6b", null, file))
    }
}
