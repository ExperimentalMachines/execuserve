package org.experimentalmachines.execuserve.app.text

import org.experimentalmachines.execuserve.engine.Units
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/**
 * How numbers, sizes and times are written, in one place and in the person's locale. The
 * words around them are string resources; this is only the figures.
 */
object Format {
    private val locale: Locale get() = Locale.getDefault()

    fun count(n: Number): String = NumberFormat.getIntegerInstance(locale).format(n)

    /** One decimal below 100, none above: a rate reads at a glance. */
    fun rate(perSecond: Double): String = String.format(locale, if (perSecond < RATE_DECIMALS_BELOW) "%.1f" else "%.0f", perSecond)

    fun percent(part: Long, whole: Long): String = if (whole > 0) "${(part * PERCENT / whole).coerceIn(0, PERCENT)}%" else "0%"

    fun bytes(n: Long): String = when {
        n >= GB -> String.format(locale, "%.1f GB", n / GB.toDouble())
        n >= MB -> String.format(locale, "%.0f MB", n / MB.toDouble())
        else -> String.format(locale, "%d KB", n / KB)
    }

    /** A context window as exporters name it: 2k, 4k, 32k. */
    fun window(tokens: Int): String = if (tokens >= KB) "${tokens / KB}k" else "$tokens"

    /** Milliseconds below a second, seconds with two decimals above. */
    fun duration(ms: Long): String = if (ms < MS_PER_SECOND) "$ms ms" else String.format(locale, "%.2f s", ms / MS_PER_SECOND.toDouble())

    fun time(epochMs: Long, withSeconds: Boolean = false): String =
        DateFormat.getTimeInstance(if (withSeconds) DateFormat.MEDIUM else DateFormat.SHORT, locale).format(Date(epochMs))

    private const val KB = Units.BYTES_PER_KIB
    private const val MB = Units.BYTES_PER_MIB
    private const val GB = MB * KB
    private const val PERCENT = 100L
    private const val MS_PER_SECOND = Units.MS_PER_SECOND
    private const val RATE_DECIMALS_BELOW = 100.0
}
