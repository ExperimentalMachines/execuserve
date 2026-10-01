package org.experimentalmachines.execuserve.engine

/** Conversions every layer above the engine uses, defined once. */
object Units {
    const val MS_PER_SECOND = 1_000L
    const val MS_PER_MINUTE = 60 * MS_PER_SECOND
    const val MS_PER_HOUR = 60 * MS_PER_MINUTE
    const val MS_PER_DAY = 24 * MS_PER_HOUR
    const val BYTES_PER_KIB = 1_024L
    const val BYTES_PER_MIB = 1_024 * BYTES_PER_KIB
}
