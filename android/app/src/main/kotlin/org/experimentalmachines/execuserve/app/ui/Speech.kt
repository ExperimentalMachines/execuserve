package org.experimentalmachines.execuserve.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.experimentalmachines.execuserve.app.R
import java.util.Locale

/** What dictation is doing, and what it has heard so far. */
data class DictationState(
    val listening: Boolean = false,
    /** Words heard so far, shown where they will land. */
    val partial: String = "",
    val error: String? = null,
)

/**
 * Speech to text, on this phone only: the on-device recogniser and nothing else. Android's
 * default one streams audio to Google, and this app's models answer on the phone; if no
 * offline language pack is installed, dictation says so instead of quietly going online.
 * Ported from OpenWeights' Dictation. Main thread only, as SpeechRecognizer is.
 */
class Dictation(private val context: Context) {
    private val _state = MutableStateFlow(DictationState())
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private val mainThread = Handler(Looper.getMainLooper())

    // Which listening session is current: a recogniser can still deliver after it was told
    // to stop, and a stale callback must not write over a newer session.
    private var session = 0

    /** Whether this phone can transcribe without a network (asked of Android 13+). */
    val available: Boolean by lazy { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }

    /** Starts listening; [onFinal] gets the finished transcript once. */
    fun start(onFinal: (String) -> Unit) {
        if (_state.value.listening) return
        if (!available) {
            _state.value = DictationState(error = context.getString(R.string.dictation_unavailable))
            return
        }
        stop()
        val token = ++session
        // Permission can be revoked after the UI checked it, and a recogniser can fail to
        // start: either would otherwise leave the microphone looking live for good.
        val speech = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { speech ->
                speech.setRecognitionListener(listener(token, onFinal))
                speech.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
                )
            }
        }.getOrElse {
            _state.value = DictationState(error = context.getString(R.string.dictation_failed))
            return
        }
        recognizer = speech
        _state.value = DictationState(listening = true)
    }

    /**
     * Stops and releases the recogniser; safe when idle. The release is posted: this is
     * usually called from inside a recogniser callback, and destroying it there crashes
     * several devices.
     */
    fun stop() {
        session++
        val speech = recognizer
        recognizer = null
        speech?.let {
            mainThread.post {
                runCatching {
                    it.stopListening()
                    it.destroy()
                }
            }
        }
        if (_state.value.listening || _state.value.partial.isNotEmpty()) _state.value = DictationState(error = _state.value.error)
    }

    private fun listener(token: Int, onFinal: (String) -> Unit) = object : RecognitionListener {
        override fun onPartialResults(results: Bundle?) {
            if (token != session) return
            results.firstTranscript()?.let { heard -> _state.value = _state.value.copy(partial = heard) }
        }

        override fun onResults(results: Bundle?) {
            if (token != session) return
            val heard = results.firstTranscript().orEmpty()
            stop()
            _state.value = DictationState()
            if (heard.isNotBlank()) onFinal(heard)
        }

        override fun onError(error: Int) {
            if (token != session) return
            stop()
            _state.value = DictationState(error = readable(error))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle?.firstTranscript(): String? = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** Silence is not a failure: tapping the mic and saying nothing ends quietly. */
    private fun readable(code: Int): String? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(R.string.dictation_permission)
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> context.getString(R.string.dictation_language)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.dictation_busy)
        else -> context.getString(R.string.dictation_failed)
    }
}

/**
 * Reads a reply aloud with an installed voice Android says needs no network; without one it
 * says so rather than use a network voice. Ported from OpenWeights' SpeechReader. Main thread.
 */
class SpeechReader(private val context: Context) {
    private val _speaking = MutableStateFlow<Long?>(null)

    /** The message being read, or null; either can be stopped from the UI. */
    val speaking: StateFlow<Long?> = _speaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val mainThread = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var generation = 0
    private var utterance = 0L
    private var active: String? = null
    private val initTimeout = Runnable { fail(R.string.speech_failed) }

    fun speak(id: Long, text: String) {
        // Some engines drop an overlong request without a callback.
        val spoken = text.forSpeech().take(TextToSpeech.getMaxSpeechInputLength())
        if (spoken.isBlank()) return
        _error.value = null
        _speaking.value = id
        val current = engine
        when {
            current == null -> {
                pending = spoken
                start()
            }
            !ready -> pending = spoken
            else -> speakReady(current, spoken)
        }
    }

    private fun speakReady(current: TextToSpeech, spoken: String) {
        try {
            val locale = Locale.getDefault()
            val voice = current.voices.orEmpty().asSequence()
                .filter { it.offlineFor(locale) }
                .maxWithOrNull(compareBy<Voice> { it.locale == locale }.thenBy { it.locale.country == locale.country }.thenBy { it.name })
            // Never setLanguage: it can swap an offline voice for the engine's network default.
            if (voice == null || current.setVoice(voice) != TextToSpeech.SUCCESS || current.voice?.name != voice.name) {
                fail(R.string.speech_no_voice)
                return
            }
            val id = "execuserve-reply-${++utterance}"
            active = id
            if (current.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) fail(R.string.speech_failed)
        } catch (_: RuntimeException) {
            fail(R.string.speech_failed)
        }
    }

    private fun Voice.offlineFor(target: Locale): Boolean = !isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty() &&
        target.language.isNotEmpty() &&
        locale.language == target.language

    fun stop() {
        pending = null
        active = null
        _speaking.value = null
        mainThread.removeCallbacks(initTimeout)
        runCatching { engine?.stop() }
        // A cancelled startup must not speak its queued reply later.
        if (!ready) discard()
    }

    fun release() {
        stop()
        discard()
    }

    private fun discard() {
        generation++
        val previous = engine
        engine = null
        ready = false
        runCatching { previous?.shutdown() }
    }

    private fun fail(message: Int) {
        stop()
        discard()
        _error.value = context.getString(message)
    }

    private fun start() {
        val token = ++generation
        mainThread.postDelayed(initTimeout, INIT_TIMEOUT_MS)
        try {
            engine = TextToSpeech(context) { status ->
                // Init can arrive before the constructor returns, or on a binder thread.
                mainThread.post {
                    if (token != generation) return@post
                    mainThread.removeCallbacks(initTimeout)
                    val current = engine
                    if (status != TextToSpeech.SUCCESS || current == null || current.setOnUtteranceProgressListener(listener) != TextToSpeech.SUCCESS) {
                        fail(R.string.speech_failed)
                        return@post
                    }
                    ready = true
                    pending?.let { speakReady(current, it) }
                    pending = null
                }
            }
        } catch (_: RuntimeException) {
            fail(R.string.speech_failed)
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            mainThread.post {
                if (active == null || utteranceId != active) return@post
                active = null
                _speaking.value = null
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) = onDone(utteranceId)

        @Deprecated("Required by the framework; the newer overload delegates to it.")
        override fun onError(utteranceId: String?) {
            mainThread.post { if (active != null && utteranceId == active) fail(R.string.speech_failed) }
        }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 10_000L
    }
}

/** A reply as it should be heard: code, link targets and Markdown marks are not read out. */
internal fun String.forSpeech(): String = this
    .replace(FENCED_CODE, " ")
    .replace(INLINE_CODE, "$1")
    .replace(LINK, "$1")
    .replace(EMPHASIS, "$1")
    .replace(HEADING, "")
    .replace(BULLET, "")
    .trim()

private val FENCED_CODE = Regex("```[\\s\\S]*?```")
private val INLINE_CODE = Regex("`([^`]*)`")
private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val EMPHASIS = Regex("""\*{1,2}([^*]+)\*{1,2}""")
private val HEADING = Regex("^#{1,6}\\s*", RegexOption.MULTILINE)
private val BULLET = Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE)
