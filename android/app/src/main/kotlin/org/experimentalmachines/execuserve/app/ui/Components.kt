package org.experimentalmachines.execuserve.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.experimentalmachines.execuserve.app.R

/**
 * A block of a screen, always on the neutral surface. A [tone] marks one that asks for
 * something to be done: its title carries the state's light and colour, the same rule as
 * the status panel, so no panel is ever painted over (painted amber read as mud in dark mode).
 */
@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.corner),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(Dimens.gutter), verticalArrangement = Arrangement.spacedBy(Dimens.row)) {
            if (title != null || trailing != null) PanelTitle(title.orEmpty(), trailing, Modifier.padding(bottom = TITLE_SPACE), tone = tone)
            content()
        }
    }
}

@Composable
fun PanelTitle(title: String, trailing: @Composable (() -> Unit)? = null, modifier: Modifier = Modifier, tone: Tone? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tone != null) Dot(tone.color, 10.dp)
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = tone?.color ?: Color.Unspecified)
        trailing?.invoke()
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** A short state label on a model: loaded, loads at start. */
@Composable
fun Pill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(50))
            .background(tone.container)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = tone.onContainer,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
}

/** One number the server is watched by: what it is, the figure, and what the figure means. */
@Composable
fun Figure(label: String, value: String, details: List<String>, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = FigureStyle, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A labelled value in a row of them: "Total" over "3.10 s". */
@Composable
fun Fact(label: String, value: String, valueColor: Color = Color.Unspecified, labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor, maxLines = 1)
    }
}

/** A text action in ink: colour is kept for state, and a button is not a state. */
@Composable
fun Action(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(text, maxLines = 1) }
}

/** The one filled action of a panel that is not Start: Send, Share. Ink on paper, inverted. */
@Composable
fun InkButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
        ),
    ) { Text(text, maxLines = 1) }
}

/** A secondary action: ink label, grey outline. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Text(text, maxLines = 1) }
}

/**
 * A value to copy: an address, a key, a command. The value wraps under its own width; it
 * never runs under the buttons.
 */
@Composable
fun CopyRow(
    value: String,
    label: String? = null,
    shown: String = value,
    prominent: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
    qr: Boolean = false,
    sensitive: Boolean = false,
) {
    val context = LocalContext.current
    var copied by remember(value) { mutableStateOf<Boolean?>(null) }
    var showQr by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied != null) { kotlinx.coroutines.delay(2500); copied = null } }
    if (showQr) ValueQrDialog(value, label, sensitive) { showQr = false }
    val style = if (prominent) Mono.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize, lineHeight = MaterialTheme.typography.titleMedium.lineHeight, fontWeight = FontWeight.Medium) else Mono
    val actions: @Composable () -> Unit = {
        extra?.invoke()
        Action(stringResource(if (copied == true) R.string.copied else R.string.action_copy), onClick = {
            copied = copy(context, value, sensitive, notifyFailure = !qr)
            if (copied == false && qr) showQr = true
        })
        if (qr) Action(stringResource(R.string.action_show_qr), onClick = { showQr = true })
    }
    // With large text the value keeps the full width and the actions go under it, so an
    // address never breaks before its port to make room for a button.
    if (largeText() || qr) {
        Column(Modifier.fillMaxWidth()) {
            Text(shown, style = style)
            if (label != null) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.offset(x = -ACTION_INSET)) { actions() }
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimens.touch), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(shown, style = style)
                if (label != null) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
    }
}

/** Whether the person reads at a large text size, where side-by-side rows crowd. */
@Composable
fun largeText(): Boolean = androidx.compose.ui.platform.LocalDensity.current.fontScale >= LARGE_TEXT

private const val LARGE_TEXT = 1.3f

/** Added to the row gap under a panel's heading, so the heading reads as the group's and not as its first row. */
private val TITLE_SPACE = 4.dp

/** A text button's own padding, pulled back so its label lines up with the text above. */
val ACTION_INSET = 12.dp

/** A row that opens to show detail; technical content lives here rather than on top. */
@Composable
fun Expandable(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clip(RoundedCornerShape(8.dp)).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Chevron(open)
        }
        AnimatedVisibility(open) { Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(Dimens.row), content = content) }
    }
}

@Composable
fun Chevron(open: Boolean, extent: Dp = 24.dp) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(extent).rotate(if (open) 180f else 0f)) {
        val w = size.width
        drawLine(color, Offset(w * 0.28f, w * 0.4f), Offset(w * 0.5f, w * 0.62f), w * 0.09f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, w * 0.62f), Offset(w * 0.72f, w * 0.4f), w * 0.09f, StrokeCap.Round)
    }
}

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/**
 * Two figures side by side, split by a rule: without it the details under each cell sit on
 * one baseline and read across as a single sentence.
 */
@Composable
fun FigurePair(first: @Composable RowScope.() -> Unit, second: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.gutter)) {
        first()
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        second()
    }
}

// ---------------------------------------------------------------------------------------
// Setting rows. The label always takes the free width and wraps; the control keeps its own.
// ---------------------------------------------------------------------------------------

@Composable
private fun Label(title: String, note: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SwitchRow(title: String, note: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touch).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(title, note, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A choice among two or three short options, as segmented buttons under its label. */
@Composable
fun <T> ChoiceRow(title: String, note: String? = null, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(title, note, Modifier.fillMaxWidth())
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // Selection is the brand's ember, as in the navigation and on switches.
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        activeBorderColor = MaterialTheme.colorScheme.outline,
                        inactiveBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** A menu of options, for lists too long for segments. */
@Composable
fun <T> MenuRow(title: String, note: String? = null, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(title, note, Modifier.weight(1f))
        Box {
            OutlinedButton(
                onClick = { open = true },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text(
                    // A value none of the options carries is shown as itself, never as a blank button.
                    options.firstOrNull { it.first == selected }?.second ?: selected?.toString().orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onSelect(value)
                        open = false
                    })
                }
            }
        }
    }
}

/** A number, applied once valid; the field keeps whatever is typed meanwhile. */
@Composable
fun NumberRow(title: String, note: String? = null, value: Int, range: IntRange, suffix: String? = null, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(title, note ?: stringResource(R.string.range_hint, range.first, range.last), Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.filter(Char::isDigit).take(6)
                text.toIntOrNull()?.takeIf { v -> v in range && v != value }?.let(onValue)
            },
            modifier = Modifier.widthIn(min = 96.dp, max = 128.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            suffix = suffix?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
            isError = parsed == null || parsed !in range,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
fun TextRow(title: String, note: String? = null, value: String, placeholder: String, onValue: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(title, note, Modifier.fillMaxWidth())
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValue(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = Mono,
            placeholder = { Text(placeholder, style = Mono) },
        )
    }
}

/**
 * How many times the screen has resumed: a key for what is read from the system and can
 * change while the app is away (a permission, a battery setting, files pushed with adb).
 */
@Composable
fun resumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { count++ } }
    return count
}

fun copy(context: Context, text: String, sensitive: Boolean = false, notifyFailure: Boolean = true): Boolean {
    val success = runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(context.getString(R.string.app_name), text)
        if (sensitive) clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
        clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text
    }.getOrDefault(false)
    if ((!success && notifyFailure) || (success && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)) {
        Toast.makeText(context, if (success) R.string.copied_on_phone else R.string.copy_failed, Toast.LENGTH_SHORT).show()
    }
    return success
}

/** The navigation glyphs, drawn: three shapes do not justify an icon library. */
@Composable
fun NavGlyph(tab: Int, selected: Boolean) {
    // The navigation item sets the content colour for selected and unselected.
    val color = LocalContentColor.current
    val light = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val stroke = w * 0.09f
        when (tab) {
            0 -> {
                drawLine(color, Offset(w * 0.12f, w * 0.25f), Offset(w * 0.42f, w * 0.5f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.42f, w * 0.5f), Offset(w * 0.12f, w * 0.75f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.52f, w * 0.75f), Offset(w * 0.82f, w * 0.75f), stroke, StrokeCap.Round)
                drawCircle(if (selected) light else color, w * 0.1f, Offset(w * 0.8f, w * 0.25f))
            }
            1 -> for (row in 0..2) {
                val y = w * (0.22f + row * 0.28f)
                drawLine(color, Offset(w * 0.15f, y), Offset(w * 0.85f, y), stroke, StrokeCap.Round)
            }
            // Runs: bars of different heights, the shape of a history.
            2 -> listOf(0.45f, 0.7f, 0.3f, 0.58f).forEachIndexed { index, height ->
                val x = w * (0.2f + index * 0.2f)
                drawLine(color, Offset(x, w * 0.82f), Offset(x, w * (0.82f - height * 0.72f)), stroke * 1.2f, StrokeCap.Round)
            }
            else -> for (row in 0..1) {
                val y = w * (0.33f + row * 0.34f)
                drawLine(color, Offset(w * 0.12f, y), Offset(w * 0.88f, y), stroke, StrokeCap.Round)
                drawCircle(color, w * 0.12f, Offset(w * (if (row == 0) 0.35f else 0.65f), y))
            }
        }
    }
}
