package org.experimentalmachines.execuserve.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** PyTorch's ember: the die keeps it in both themes; the body takes the theme's ink. */
private val Ember = Color(0xFFEE4C2C)

/**
 * The Block, ExecuServe's mark, from the same numbers as the launcher icon and the web chat
 * (tools/design/mark.py writes [MarkPaths]). Below 40 dp it draws the small version, whose
 * cuts and legs stay open at that size. Decorative: the name beside it says what it is.
 */
@Composable
fun Mark(size: Dp, modifier: Modifier = Modifier) {
    val body = MaterialTheme.colorScheme.onBackground
    val small = size < 40.dp
    val vector = remember(body, small) {
        ImageVector.Builder(name = "Mark", defaultWidth = size, defaultHeight = size, viewportWidth = 100f, viewportHeight = 100f)
            .addPath(addPathNodes(if (small) MarkPaths.SMALL_BODY else MarkPaths.BODY), fill = SolidColor(body))
            .addPath(addPathNodes(if (small) MarkPaths.SMALL_DIE else MarkPaths.DIE), fill = SolidColor(Ember))
            .build()
    }
    Image(rememberVectorPainter(vector), contentDescription = null, modifier = modifier.size(size))
}
