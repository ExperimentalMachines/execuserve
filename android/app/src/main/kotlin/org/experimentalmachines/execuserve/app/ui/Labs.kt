package org.experimentalmachines.execuserve.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.catalog.Labs

/**
 * The lab's own picture, as it publishes it on the Hub. The slot is held while the picture
 * loads, so rows do not shift when it lands; a model with no known lab has no slot at all,
 * because a tile of initials reads as a contact card, not a lab (learned in OpenWeights).
 */
@Composable
fun LabMark(model: MainViewModel, lab: String?, size: Dp = 40.dp) {
    lab ?: return
    val images by model.labImages.collectAsState()
    LaunchedEffect(lab) { model.showLab(lab) }
    val shape = RoundedCornerShape(size / 5)
    val bitmap = images[lab]
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // Labs upload logos on white, which on a white panel has no edge without this.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, contentDescription = Labs.displayName(lab), Modifier.size(size), contentScale = ContentScale.Crop)
        }
    }
}
