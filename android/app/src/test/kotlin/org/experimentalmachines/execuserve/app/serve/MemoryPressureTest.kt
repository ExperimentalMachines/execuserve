package org.experimentalmachines.execuserve.app.serve

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class MemoryPressureTest {
    @Test
    fun switchingToAClientPreservesModelsButRealPressureReleasesThem() {
        assertFalse(shouldEvictModelsForTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(shouldEvictModelsForTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(shouldEvictModelsForTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(shouldEvictModelsForTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(shouldEvictModelsForTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(shouldEvictModelsForTrim(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertTrue(shouldEvictModelsForTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
