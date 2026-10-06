package org.experimentalmachines.execuserve.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Against what the POCO X8 Pro Max (12 GB) did on 2026-10-06 with 6.94 GB available. */
class NpuMemoryTest {
    private val available = 6_940_000_000L

    @Test
    fun theBuildThatRanFitsAndTheOneThatKilledThePhonesAppsDoesNot() {
        // LFM2.5-1.2B 2k: loaded and answered, 3.54 GB measured.
        val lfm = """{"cache_size":2048,"hidden_size":2048,"num_head":32,"num_layer":16,"head_dim":64,"cache_type":"fp32"}"""
        val lfmNeed = NpuMemory.needBytes(lfm, 2_562_760_000L)!!
        assertTrue(lfmNeed in 3_540_000_000L..5_000_000_000L, "$lfmNeed")
        assertTrue(NpuMemory.fits(lfmNeed, available))
        // Qwen3-0.6B 4k: the low-memory killer closed a dozen apps, then this one.
        val qwen = """{"cache_size":4096,"hidden_size":1024,"num_head":16,"num_layer":28,"head_dim":128,"cache_type":"fp32"}"""
        val qwenNeed = NpuMemory.needBytes(qwen, 1_854_580_000L)!!
        assertTrue(qwenNeed > 6_600_000_000L, "$qwenNeed")
        assertFalse(NpuMemory.fits(qwenNeed, available))
    }

    @Test
    fun aHalfPrecisionCacheNeedsHalfAndOptionsWithoutTheCacheGiveNoEstimate() {
        val fp32 = NpuMemory.needBytes("""{"cache_size":1024,"num_head":8,"num_layer":4,"head_dim":64}""", 0)!!
        val fp16 = NpuMemory.needBytes("""{"cache_size":1024,"num_head":8,"num_layer":4,"head_dim":64,"cache_type":"fp16"}""", 0)!!
        assertEquals(fp32 / 2, fp16)
        assertNull(NpuMemory.needBytes("""{"cache_size":1024}""", 0))
    }
}
