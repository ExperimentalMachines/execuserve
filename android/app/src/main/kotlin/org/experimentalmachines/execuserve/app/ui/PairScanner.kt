package org.experimentalmachines.execuserve.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.server.Pairings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Signs in a browser that shows a pairing code: a laptop's browser cannot use its own camera
 * on a plain-HTTP page, so this phone reads the code instead. The camera runs only while this
 * screen is open, and nothing it sees leaves the phone. Every sign-in is confirmed first, with
 * the browser, its address and the code it shows: a code read by mistake signs in nobody.
 */
@Composable
internal fun PairScanner(model: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPermission()
    var message by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<Pairings.Request?>(null) }
    var typed by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    // While a sign-in is being confirmed, frames are ignored rather than read again.
    val paused = remember { AtomicBoolean(false) }
    val notWaiting = stringResource(R.string.pair_not_waiting)
    val notPairing = stringResource(R.string.pair_not_a_code)
    val cameraFailed = stringResource(R.string.pair_camera_failed)

    // One lookup at a time, and none while a browser is being confirmed: frames are paused
    // until the owner answers, so the request on screen is the one that gets the key.
    val lookup = remember { LookupJob() }
    suspend fun find(text: String, fromCamera: Boolean) {
        val request = model.findPairing(text)
        found = request
        message = missWords(request, text, fromCamera)?.let { if (it == R.string.pair_not_a_code) notPairing else notWaiting }
        if (request == null) {
            // The same wrong code stays in view for a while: read it again after a pause.
            if (fromCamera) delay(RESCAN_MS)
            paused.set(false)
        }
    }
    fun lookUp(text: String, fromCamera: Boolean) {
        if (!fromCamera) {
            lookup.job?.cancel()
            paused.set(true)
        }
        lookup.job = scope.launch { find(text, fromCamera) }
    }

    LaunchedEffect(done) {
        if (done) {
            delay(CLOSE_AFTER_MS)
            onClose()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScannerHeader(onClose)
                ScanFrame(
                    state = frameState(done, camera.granted, camera.asked),
                    paused = paused,
                    onAsk = camera.ask,
                    onText = { text -> if (found == null && paused.compareAndSet(false, true)) lookUp(text, fromCamera = true) },
                    onCameraFailed = { message = cameraFailed },
                )
                ScanMessage(message)
                if (!done) TypeCode(typed, onTyped = { typed = it.take(TYPED_MAX) }, onFind = { lookUp(typed, fromCamera = false) })
            }
        }
    }

    found?.let { request ->
        ConfirmPairing(
            request,
            onAllow = {
                found = null
                model.approvePairing(request) { ok ->
                    if (ok) done = true else message = notWaiting
                    paused.set(false)
                }
            },
            onDecline = {
                found = null
                model.declinePairing(request)
                paused.set(false)
            },
            onDismiss = {
                found = null
                paused.set(false)
            },
        )
    }
}

@Composable
private fun ScannerHeader(onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.pair_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        Action(stringResource(R.string.qr_close), onClick = onClose)
    }
    Text(stringResource(R.string.pair_steps), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ScanMessage(message: String?) {
    message ?: return
    Text(message, Modifier.fillMaxWidth(), color = LocalTones.current.attention.color, style = MaterialTheme.typography.bodyMedium)
}

/** Whether the camera may be used, whether Android has asked, and a way to ask. */
private class CameraPermission(val granted: Boolean, val asked: Boolean, val ask: () -> Unit)

/**
 * The camera permission, asked for once on opening, and read again on every return to the
 * app: someone sent to Android's settings comes back with it allowed.
 */
@Composable
private fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = allowed() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    return CameraPermission(granted, asked) { launcher.launch(Manifest.permission.CAMERA) }
}

/** Why a lookup found nothing, or null when it found the browser. */
private fun missWords(request: Pairings.Request?, text: String, fromCamera: Boolean): Int? = when {
    request != null -> null
    fromCamera && !text.startsWith(Pairings.SCHEME) -> R.string.pair_not_a_code
    else -> R.string.pair_not_waiting
}

/** The lookup in flight, if any. */
private class LookupJob {
    var job: kotlinx.coroutines.Job? = null
}

private enum class FrameState { ASK, REFUSED, CAMERA, DONE }

private fun frameState(done: Boolean, granted: Boolean, asked: Boolean) = when {
    done -> FrameState.DONE
    granted -> FrameState.CAMERA
    asked -> FrameState.REFUSED
    else -> FrameState.ASK
}

/** The code typed instead, for a phone with no camera or a screen the camera cannot read. */
@Composable
private fun TypeCode(typed: String, onTyped: (String) -> Unit, onFind: () -> Unit) {
    val valid = Pairings.normalCode(typed) != null
    OutlinedTextField(
        value = typed,
        onValueChange = onTyped,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        label = { Text(stringResource(R.string.pair_type_label)) },
        placeholder = { Text("K7P-4QX") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (valid) onFind() }),
    )
    InkButton(stringResource(R.string.pair_type_find), onClick = onFind, enabled = valid)
}

/** The square above the code field: the camera, the reason to allow it, or the sign-in done. */
@Composable
private fun ScanFrame(state: FrameState, paused: AtomicBoolean, onAsk: () -> Unit, onText: (String) -> Unit, onCameraFailed: () -> Unit) {
    val context = LocalContext.current
    val frame = Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
    when (state) {
        FrameState.DONE -> Box(frame.background(LocalTones.current.good.container), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.pair_done), Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
        }
        FrameState.CAMERA -> Box(frame.background(Color.Black).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))) {
            CameraPreview(paused, onText, onCameraFailed)
        }
        else -> Column(frame.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.pair_camera_needed), style = MaterialTheme.typography.bodyMedium)
            if (state == FrameState.REFUSED) {
                Action(stringResource(R.string.pair_camera_settings), onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                })
            } else {
                Action(stringResource(R.string.pair_camera_allow), onClick = onAsk)
            }
        }
    }
}

/** The owner's check before a browser gets a key: which browser, from where, and its code. */
@Composable
private fun ConfirmPairing(request: Pairings.Request, onAllow: () -> Unit, onDecline: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_confirm_title)) },
        text = { Text(stringResource(R.string.pair_confirm_body, request.client, request.address, Pairings.shown(request.code))) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.pair_allow)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.pair_decline)) } },
    )
}

/** The back camera's picture, with every frame offered to [onText] once a QR code is read in it. */
@Composable
private fun CameraPreview(paused: AtomicBoolean, onText: (String) -> Unit, onFailed: () -> Unit) {
    val failed by rememberUpdatedState(onFailed)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    // A TextureView: a SurfaceView sits behind the dialog's own window and shows only black.
    val preview = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    // The first frame can take seconds on a cold camera: until then the frame says so.
    var streaming by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        preview.previewStreamState.observe(lifecycle, observer)
        onDispose { preview.previewStreamState.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        if (!streaming) Text(stringResource(R.string.pair_camera_starting), color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
    DisposableEffect(lifecycle) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            // Closed before the camera service answered: nothing to start.
            if (disposed) return@addListener
            val cameras = runCatching { future.get() }.getOrNull() ?: return@addListener failed()
            provider = cameras
            val useCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                        .build(),
                )
                .build()
                .also { it.setAnalyzer(executor, QrAnalyzer(paused) { text -> ContextCompat.getMainExecutor(context).execute { latest(text) } }) }
            val selector = when {
                runCatching { cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false) -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> CameraSelector.DEFAULT_FRONT_CAMERA
            }
            runCatching {
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycle, selector, useCase, analysis)
            }.onFailure { failed() }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
            executor.shutdown()
        }
    }
}

/** Reads a QR code from each frame's brightness plane; frames are skipped while [paused]. */
private class QrAnalyzer(private val paused: AtomicBoolean, private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QrFrames()

    override fun analyze(image: ImageProxy) {
        image.use { frame ->
            if (paused.get()) return
            val plane = frame.planes[0]
            val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            reader.read(data, plane.rowStride, frame.width, frame.height)?.let(onText)
        }
    }
}

private const val ANALYSIS_WIDTH = 1280
private const val ANALYSIS_HEIGHT = 720
private const val TYPED_MAX = 12
private const val CLOSE_AFTER_MS = 1_500L
private const val RESCAN_MS = 1_000L
