package org.experimentalmachines.execuserve.app.serve

import android.content.ComponentCallbacks2

/** Trim levels describe different lifecycle states, not one continuous severity scale. */
@Suppress("DEPRECATION")
internal fun shouldEvictModelsForTrim(level: Int): Boolean =
    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
