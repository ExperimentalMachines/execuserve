package org.experimentalmachines.execuserve.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import org.experimentalmachines.execuserve.app.R
import kotlin.math.floor

/** Exact payload, four-module quiet zone, no networking, files or credentials in URLs. */
internal fun valueQr(value: String) = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, mapOf(
    EncodeHintType.CHARACTER_SET to "UTF-8",
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    EncodeHintType.MARGIN to 4,
))

@Composable
internal fun ValueQrDialog(value: String, label: String?, sensitive: Boolean, onClose: () -> Unit) {
    val matrix = remember(value) { runCatching { valueQr(value) }.getOrNull() }
    // No key is drawn until the user chooses where to reveal it.
    var protectedReveal by remember(value) { mutableStateOf<Boolean?>(if (sensitive) null else false) }
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(value, protectedReveal) { if (sensitive && protectedReveal != null) { delay(60_000); close() } }
    val description = stringResource(R.string.qr_title)
    val qrSize = (LocalConfiguration.current.screenHeightDp * .45f).coerceIn(96f, 280f).dp
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        securePolicy = if (protectedReveal == true) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
    )) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label ?: description, style = MaterialTheme.typography.titleLarge)
                if (sensitive && protectedReveal == null) {
                    Text(stringResource(R.string.qr_reveal_note), style = MaterialTheme.typography.bodyMedium)
                    Action(stringResource(R.string.qr_on_phone), onClick = { protectedReveal = true })
                    Action(stringResource(R.string.qr_in_viewer), onClick = { protectedReveal = false })
                } else if (matrix != null) {
                    Canvas(Modifier.size(qrSize).align(Alignment.CenterHorizontally).background(Color.White).semantics { contentDescription = description }) {
                        // Integer pixel modules preserve contrast and avoid fuzzy edges at any density.
                        val module = floor(size.minDimension / matrix.width).coerceAtLeast(1f)
                        val x0 = (size.width - module * matrix.width) / 2
                        val y0 = (size.height - module * matrix.height) / 2
                        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                            if (matrix[x, y]) drawRect(Color.Black, Offset(x0 + x * module, y0 + y * module), Size(module, module))
                        }
                    }
                    Text(stringResource(if (!sensitive) R.string.qr_local else if (protectedReveal == true) R.string.qr_secret else R.string.qr_secret_remote), style = MaterialTheme.typography.bodySmall)
                } else Text(stringResource(R.string.qr_unavailable))
                Action(stringResource(R.string.qr_close), onClick = onClose)
            }
        }
    }
}
