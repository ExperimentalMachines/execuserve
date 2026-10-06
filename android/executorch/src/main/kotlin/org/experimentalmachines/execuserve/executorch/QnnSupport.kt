package org.experimentalmachines.execuserve.executorch

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.system.Os
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Whether this phone can run a Qualcomm NPU (QNN HTP) export, and the setup that needs.
 *
 * A QNN file is compiled for one chip and loads only on it, so the catalog offers exactly
 * this phone's folder (`qnn/<soc>`, from `Build.SOC_MODEL`) and only on a Qualcomm SoC. The
 * HTP drivers come with the app (Qualcomm's `qnn-runtime`), one skeleton per Hexagon
 * generation; the DSP loads its skeleton by path, so the libraries are extracted to disk and
 * [prepare] points `ADSP_LIBRARY_PATH` at them.
 *
 * As with [VulkanSupport], a phone that cannot start the delegate after all says so in the
 * runtime's own words when a model opens, and that is recorded per runtime release so NPU
 * files stop being offered until an update.
 */
object QnnSupport {
    /** The runtime release the record belongs to. */
    private const val RUNTIME = "1.5.1"
    private const val PREFS = "qnn_support"
    private const val KEY_REFUSED = "refused:$RUNTIME"

    /** The HTP library every QNN model loads; its presence says the app carries the drivers. */
    private const val HTP_LIBRARY = "libQnnHtp.so"

    @Volatile private var nativeDir: String? = null

    @Volatile private var hardware = false

    @Volatile private var refusal: String? = null

    @Volatile private var prefs: SharedPreferences? = null

    private val _usable = MutableStateFlow(false)

    /** [usable] as a flow, so a screen listing NPU builds drops them the moment a refusal lands. */
    val usableState: StateFlow<Boolean> = _usable.asStateFlow()

    /** This phone's chip as Android names it (`SM8850`), or null when it does not say. */
    val soc: String? get() = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }

    /** Reads the chip, the bundled drivers and any earlier refusal. Called once at startup. */
    fun init(context: Context) {
        val dir = context.applicationInfo.nativeLibraryDir
        nativeDir = dir
        hardware = Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true) && soc != null && File(dir, HTP_LIBRARY).isFile
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = stored
        refusal = stored.getString(KEY_REFUSED, null)
        _usable.value = usable
    }

    /** True when this phone's NPU files should be offered. */
    val usable: Boolean get() = hardware && refusal == null

    /** Why this phone stopped being offered NPU files, or null if it has not. */
    val refusedBecause: String? get() = refusal

    /**
     * Environment the QNN runner reads, set before each model opens: where the DSP finds its
     * skeleton, and the model family, which picks the runner's stop tokens (a patched runtime
     * reads it; the stock one assumes Llama 3 for every model).
     */
    fun prepare(family: String?) {
        val dir = checkNotNull(nativeDir) { "QnnSupport.init was not called" }
        Os.setenv("ADSP_LIBRARY_PATH", "$dir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true)
        Os.setenv(DECODER_ENV, decoderModel(family), true)
    }

    /** Records that the NPU would not start here, so NPU files stop being offered. */
    fun markUnusable(reason: String) {
        refusal = reason
        _usable.value = false
        prefs?.edit()?.putString(KEY_REFUSED, reason)?.apply()
    }

    /**
     * What the QNN delegate says when this phone cannot run it at all: the backend missing
     * from the build, or the HTP refusing to start. A file for another chip, a full window or a
     * bad file are not on the list; those cost one model, not the phone's NPU.
     */
    private val INCOMPATIBLE = listOf(
        "QnnBackend is not available",
        "QnnBackend is not registered",
        "Failed to get Qnn interface",
        "Fail to create Qnn backend",
        "Failed to create Qnn device",
    )

    /** Records [failure] as a refusal when it is an NPU incompatibility; returns the reason, or null. */
    fun recordIfIncompatible(failure: Throwable?): String? = generateSequence(failure) { it.cause }.take(MAX_CAUSES)
        .mapNotNull { it.message }
        .firstNotNullOfOrNull { message -> INCOMPATIBLE.firstOrNull { it in message }?.let { marker -> message.lineContaining(marker) } }
        ?.also(::markUnusable)

    /** The env var the patched JNI layer reads for the decoder family. */
    const val DECODER_ENV = "EXECUTORCH_QNN_DECODER_MODEL"

    /**
     * ExecuServe's family to the name the Qualcomm runner knows (runner.cpp's
     * DecoderModelVersion). Unknown families keep the runtime's own default.
     */
    fun decoderModel(family: String?): String = when (family) {
        "qwen3" -> "qwen3"
        "qwen2.5" -> "qwen2_5"
        "llama3.2" -> "llama3"
        "gemma3" -> "gemma3"
        "smollm2" -> "smollm2_135m"
        "smollm3" -> "smollm3"
        "phi4-mini" -> "phi_4_mini"
        else -> "llama3"
    }

    private const val MAX_CAUSES = 8

    private fun String.lineContaining(marker: String): String = lineSequence().firstOrNull { marker in it }?.trim()?.take(MAX_REASON) ?: marker

    private const val MAX_REASON = 240
}
