package org.experimentalmachines.execuserve.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.experimentalmachines.execuserve.app.BuildConfig
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.host.TestResult
import kotlin.time.Duration.Companion.seconds

// The turns of a chat, after OpenWeights' chat screen: the person's message in a bubble, the
// model's reply unboxed beneath its thinking, what the server is doing while it waits, and
// the reply's actions and figures once it has finished.

/** The person's message: a bubble at the end, its text selectable, and "more" to copy it whole. */
@Composable
internal fun UserTurn(message: ChatMessage) {
    var actions by rememberSaveable(message.id) { mutableStateOf(false) }
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
        SelectionContainer(Modifier.weight(1f, fill = false)) {
            Text(
                message.content,
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(BUBBLE_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = { actions = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.chat_message_actions))
        }
    }
    if (actions) {
        ActionsSheet(onDismiss = { actions = false }) {
            SheetAction(Icons.Rounded.ContentCopy, stringResource(R.string.chat_copy_message)) {
                copy(context, message.content)
                actions = false
            }
        }
    }
}

/**
 * The model's reply: its thinking, folded once it has answered, then the answer as Markdown;
 * while it is written, what the server is doing; once it has finished, copy, read aloud and
 * more, beside the reply's own figures.
 */
@Composable
internal fun AssistantTurn(message: ChatMessage, phase: Int?, speaking: Boolean, onReadAloud: () -> Unit, onRegenerate: (() -> Unit)?) {
    val context = LocalContext.current
    var more by rememberSaveable(message.id) { mutableStateOf(false) }
    var reporting by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column {
                if (message.reasoning.isNotEmpty()) {
                    ReasoningBlock(message.reasoning, thinking = message.running && message.content.isEmpty(), durationMs = message.reasoningMs)
                }
                if (message.content.isNotEmpty()) {
                    // While it streams, a span the model opened and has not closed shows styled, not as raw asterisks.
                    MarkdownText(if (message.running) ChatText.closeOpen(message.content) else message.content)
                }
            }
        }
        if (message.running && phase != null) ActivityLine(phase)
        ReplyNotes(message)
        if (!message.running && message.content.isNotEmpty()) {
            MessageActions(
                speaking = speaking,
                onCopy = { copy(context, message.content) },
                onReadAloud = onReadAloud,
                onMore = { more = true },
                result = message.result,
            )
        }
    }
    if (more) {
        ActionsSheet(onDismiss = { more = false }) {
            message.result?.let { TurnStats(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            onRegenerate?.let { regenerate ->
                SheetAction(Icons.Rounded.Refresh, stringResource(R.string.chat_regenerate)) {
                    more = false
                    regenerate()
                }
            }
            // Here rather than under every reply: nothing is sent anywhere by the app, the report
            // is a text the person shares where they choose.
            SheetAction(Icons.Rounded.Flag, stringResource(R.string.report_action)) {
                more = false
                reporting = true
            }
        }
    }
    if (reporting) {
        val chooser = stringResource(R.string.report_chooser)
        ReportDialog(
            model = message.model.orEmpty(),
            reply = message.content,
            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            onDismiss = { reporting = false },
            onShare = { subject, text ->
                reporting = false
                ContentReport.share(context, subject, text, chooser)
            },
            reasoning = message.reasoning,
        )
    }
}

/**
 * The model's thinking: open while it thinks, folded once it answers, a tap wins either way
 * until thinking ends (OpenWeights' ReasoningBlock).
 */
@Composable
private fun ReasoningBlock(reasoning: String, thinking: Boolean, durationMs: Long?) {
    var override by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(thinking) { if (!thinking) override = null }
    val expanded = override ?: thinking
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { override = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                when {
                    thinking -> stringResource(R.string.chat_reasoning_now)
                    durationMs == null -> stringResource(R.string.chat_thought)
                    else -> stringResource(R.string.chat_thought_for, Format.duration(durationMs))
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.chat_hide_reasoning else R.string.chat_show_reasoning),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }
        if (expanded) {
            Text(
                reasoning,
                Modifier.padding(start = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the server is doing for this reply, at the end of what has been written so far: a
 * pulse and the seconds since this phase began, which restart when the phase changes.
 */
@Composable
private fun ActivityLine(phase: Int) {
    var seconds by remember(phase) { mutableIntStateOf(0) }
    LaunchedEffect(phase) {
        while (true) {
            delay(1.seconds)
            seconds++
        }
    }
    val pulse = rememberInfiniteTransition(label = "activity")
    val alpha by pulse.animateFloat(
        initialValue = PULSE_DIM,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Text(stringResource(phase), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (seconds > 0) Text("${seconds}s", style = MetricStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A refusal in the server's words, or that the person stopped the reply. */
@Composable
private fun ReplyNotes(message: ChatMessage) {
    message.failure?.let { failure ->
        Text(
            failure.status?.let { stringResource(R.string.chat_http_error, it, failure.message.orEmpty()) } ?: failure.message.orEmpty(),
            Modifier.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (message.stopped) {
        Text(
            stringResource(R.string.chat_stopped),
            Modifier.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Copy and read aloud in one tap, the rarer actions behind "more", and the reply's figures at
 * the end of the row. A flow row: at a large font scale the figures drop to their own line
 * rather than being squeezed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageActions(speaking: Boolean, onCopy: () -> Unit, onReadAloud: () -> Unit, onMore: () -> Unit, result: TestResult?) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ReplyAction(Icons.Rounded.ContentCopy, stringResource(R.string.chat_copy), onCopy)
            ReplyAction(
                if (speaking) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp,
                stringResource(if (speaking) R.string.chat_stop_reading else R.string.chat_read_aloud),
                onReadAloud,
            )
            ReplyAction(Icons.Rounded.MoreHoriz, stringResource(R.string.chat_more), onMore)
        }
        result?.let { Measurements(it) }
    }
}

/**
 * Prefill and decode by name, then the whole time: the two phases cost differently on a phone.
 * Each figure is kept whole, so a narrow row breaks between them, never inside "82.84 s".
 */
@Composable
private fun Measurements(result: TestResult) {
    Text(
        listOfNotNull(
            result.prefillTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.chat_prefill, Format.rate(it)) },
            result.decodeTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.chat_decode, Format.rate(it)) },
            Format.duration(result.totalMs),
        ).joinToString(" · ") { it.replace(' ', '\u00A0') },
        Modifier.padding(end = 4.dp),
        style = MetricStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A quiet icon in a full touch target: these sit under every reply, so they must not compete with it. */
@Composable
private fun ReplyAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(Dimens.touch)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * Where one reply's time went (OpenWeights' TurnStatsPanel): what the cache spared, all the
 * tokens, the whole time; then each phase as tokens, seconds and a rate. Reused plus read plus
 * written is the total, so every figure is accounted for by another. Only what the server
 * measured; a dash where it did not.
 */
@Composable
private fun TurnStats(result: TestResult, modifier: Modifier = Modifier) {
    val read = (result.promptTokens - result.cachedTokens).coerceAtLeast(0)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Headline(Format.count(result.cachedTokens), stringResource(R.string.stat_reused), Modifier.weight(1f))
            Headline(Format.count(result.promptTokens + result.completionTokens), stringResource(R.string.stat_tokens), Modifier.weight(1f))
            Headline(Format.duration(result.totalMs), stringResource(R.string.stat_total_time), Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PhaseRow(stringResource(R.string.stat_prefill), read, millisOf(read, result.prefillTokensPerSecond), result.prefillTokensPerSecond)
        PhaseRow(
            stringResource(R.string.stat_decode),
            result.completionTokens,
            millisOf(result.completionTokens - 1, result.decodeTokensPerSecond),
            result.decodeTokensPerSecond,
        )
    }
}

/** The time a phase took, from its tokens and its measured rate; null when either is missing. */
private fun millisOf(tokens: Int, perSecond: Double): Long? = if (tokens > 0 && perSecond > 0) (tokens * MS_PER_SECOND / perSecond).toLong() else null

@Composable
private fun Headline(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PhaseRow(name: String, tokens: Int, millis: Long?, perSecond: Double) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        Cell(stringResource(R.string.stat_tokens_value, Format.count(tokens)), Modifier.weight(TOKENS_WEIGHT))
        Cell(millis?.let(Format::duration) ?: NOT_MEASURED, Modifier.weight(1f))
        Cell(if (perSecond > 0) stringResource(R.string.stat_rate, Format.rate(perSecond)) else NOT_MEASURED, Modifier.weight(RATE_WEIGHT))
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier) {
    Text(text, modifier, style = MetricStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/**
 * A sheet of actions, fully open from the first frame and sized to its rows: a scrolling
 * column knows its whole height at once, so the sheet measures once and stays put.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionsSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) { content() }
    }
}

@Composable
internal fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private val BUBBLE_CORNER = 12.dp

/** Figures under a reply: the mono, a step smaller and firmer than body text (OpenWeights' MetricTextStyle). */
private val MetricStyle = Mono.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private const val PULSE_DIM = 0.3f
private const val PULSE_MS = 700
private const val MS_PER_SECOND = 1000.0
private const val TOKENS_WEIGHT = 1.3f
private const val RATE_WEIGHT = 1.1f
private const val NOT_MEASURED = "-"
