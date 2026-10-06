package org.experimentalmachines.execuserve.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.experimentalmachines.execuserve.app.BuildConfig
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.catalog.HfCatalog
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.host.ConsoleChat
import org.experimentalmachines.execuserve.host.ModelNames
import org.experimentalmachines.execuserve.host.ServeHost

/**
 * Chat with the models this phone hosts, through its own server: the same API, key checks
 * and queue another app gets, so trying a model here is trying it as a client. One
 * conversation, kept in memory like the browser chat's.
 */
@Composable
fun ChatScreen(model: MainViewModel, padding: PaddingValues, openModels: () -> Unit) {
    val server by model.server.collectAsState()
    val status by model.status.collectAsState()
    val installed by model.installed.collectAsState()
    val settings by model.settings.collectAsState()
    val chat by model.chat.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding(),
    ) {
        when {
            installed.isEmpty() -> Gate(
                stringResource(R.string.chat_no_models_title),
                stringResource(R.string.chat_no_models_note),
                stringResource(R.string.chat_no_models_action),
                openModels,
            )
            server !is ServeHost.State.Running -> Gate(
                stringResource(R.string.chat_start_title),
                stringResource(R.string.chat_start_note),
                stringResource(R.string.action_start),
                model::start,
            )
            else -> {
                // Whatever is in memory answers fastest, so it is the suggestion until someone picks.
                // Every candidate must still be installed: a model removed from disk and rescanned
                // can stay resident until it is unloaded (agy review).
                fun installedOrNull(id: String?) = installed.firstOrNull { it.id == id }
                val entry = installedOrNull(chat.model)
                    ?: status?.resident.orEmpty().firstNotNullOfOrNull { installedOrNull(it.id) }
                    ?: installedOrNull(settings?.defaultModel)
                    ?: installed.first()
                val target = entry.id
                Controls(installed, entry, chat, model)
                Conversation(chat, ModelNames.shown(entry, installed), model, target, Modifier.weight(1f)) { model.sendChat(it, target) }
                Composer(chat.running, model, onSend = { model.sendChat(it, target) }, onStop = model::stopChat)
            }
        }
    }
}

/** Why the chat cannot start yet, and the one thing that fixes it. */
@Composable
private fun Gate(title: String, note: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Dimens.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mark(56.dp)
        Text(title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
        Text(
            note,
            Modifier.padding(top = 8.dp, bottom = 20.dp).widthIn(max = 360.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkButton(action, onAction)
    }
}

/** The model and the processor it runs on, the thinking switch where it has one, and starting over. */
@Composable
private fun Controls(installed: List<ModelEntry>, entry: ModelEntry, chat: ChatState, model: MainViewModel) {
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { picking = true }, enabled = !chat.running) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(ModelNames.shown(entry, installed), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            shortProcessor(entry.backend),
                            entry.contextLength?.let {
                                stringResource(R.string.host_model_context, Format.window(it))
                            },
                        )
                            .joinToString(" · "),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Chevron(open = picking, extent = 20.dp)
            }
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                installed.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(ModelNames.shown(option, installed)) },
                        onClick = {
                            picking = false
                            model.chooseChatModel(option.id)
                        },
                    )
                }
            }
        }
        if (ConsoleChat.canThink(entry)) {
            FilterChip(
                selected = chat.thinking,
                onClick = { model.setChatThinking(!chat.thinking) },
                label = { Text(stringResource(R.string.chat_think)) },
                enabled = !chat.running,
            )
        }
        TextButton(onClick = model::newChat, enabled = chat.messages.isNotEmpty()) { Text(stringResource(R.string.chat_new)) }
    }
}

/** CPU, GPU or which NPU: short enough to sit under a model's name. */
@Composable
private fun shortProcessor(backend: String?): String = stringResource(
    when (backend) {
        HfCatalog.VULKAN -> R.string.processor_short_gpu
        HfCatalog.QNN -> R.string.processor_short_qualcomm
        HfCatalog.NEUROPILOT -> R.string.processor_short_mediatek
        else -> R.string.processor_short_cpu
    },
)

/** A model id may break after its hyphens, never inside a word ("neuropilo" / "t"). */
private fun breakable(id: String) = id.replace("-", "-​")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(chat: ChatState, modelName: String, model: MainViewModel, target: String, modifier: Modifier, onSuggestion: (String) -> Unit) {
    val list = rememberLazyListState()
    val last = chat.messages.lastOrNull()
    // Following the end is a decision, not a measurement: a growing reply is taller than the
    // screen long before it ends, so "is the end in view" turns false on its own. Sending
    // turns following on; scrolling away turns it off; scrolling back to the end turns it on.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) {
        if (!list.isScrollInProgress && chat.messages.isNotEmpty()) follow = !list.canScrollForward
    }
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) {
            follow = true
            list.scrollToItem(chat.messages.lastIndex, Int.MAX_VALUE)
        }
    }
    LaunchedEffect(last?.content?.length, last?.reasoning?.length, last?.running) {
        if (follow && chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.lastIndex, Int.MAX_VALUE)
    }
    if (chat.messages.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(vertical = Dimens.gutter), verticalArrangement = Arrangement.Center) {
            Mark(48.dp)
            Text(stringResource(R.string.chat_welcome, breakable(modelName)), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.chat_welcome_note),
                Modifier.padding(top = 8.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.chat_suggest_plan, R.string.chat_suggest_explain, R.string.chat_suggest_write).forEach { id ->
                    val text = stringResource(id)
                    SuggestionChip(onClick = { onSuggestion(text) }, label = { Text(text) })
                }
            }
        }
        return
    }
    val speaking by model.reader.speaking.collectAsState()
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(vertical = Dimens.row),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(chat.messages, key = { it.id }) { message ->
            if (message.fromUser) {
                Asked(message)
            } else {
                // Only the last reply can be asked again, and only once nothing is being written.
                val again = message.id == last?.id && !chat.running && chat.messages.getOrNull(chat.messages.size - 2)?.fromUser == true
                Reply(
                    message,
                    speaking = speaking == message.id,
                    onReadAloud = { if (speaking == message.id) model.reader.stop() else model.reader.speak(message.id, message.content) },
                    onRegenerate = if (again) ({ model.regenerateChat(target) }) else null,
                )
            }
        }
    }
}

@Composable
private fun Asked(message: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            SelectionContainer { Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reply(message: ChatMessage, speaking: Boolean, onReadAloud: () -> Unit, onRegenerate: (() -> Unit)?) {
    val context = LocalContext.current
    var reporting by rememberSaveable(message.id) { mutableStateOf(false) }
    var more by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mark(20.dp)
            Text(message.model.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (message.reasoning.isNotEmpty()) {
            Expandable(
                stringResource(R.string.chat_reasoning),
                pluralStringResource(R.plurals.characters, message.reasoning.length, Format.count(message.reasoning.length)),
            ) {
                SelectionContainer {
                    Text(message.reasoning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (message.content.isNotEmpty()) {
            SelectionContainer {
                // While it streams, a span the model opened and has not closed shows styled, not as raw asterisks.
                val content = if (message.running) ChatText.closeOpen(message.content) else message.content
                Text(ChatText.styled(content, MaterialTheme.colorScheme.surfaceContainer), style = MaterialTheme.typography.bodyLarge)
            }
        }
        ReplyStatus(message)
        if (!message.running && (message.content.isNotEmpty() || message.reasoning.isNotEmpty())) {
            ReplyActions(message, speaking, onCopy = { copy(context, message.content) }, onReadAloud = onReadAloud, onMore = { more = true })
        }
    }
    if (more) {
        MoreActions(
            onRegenerate = onRegenerate?.let { regenerate ->
                {
                    more = false
                    regenerate()
                }
            },
            onReport = {
                more = false
                reporting = true
            },
            onDismiss = { more = false },
        )
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
 * Copy and read aloud in one tap under every reply, the rarer actions behind "more", and the
 * reply's own figures. A flow row: at a large font scale the figures drop to their own line
 * rather than being squeezed (OpenWeights' MessageActions).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyActions(message: ChatMessage, speaking: Boolean, onCopy: () -> Unit, onReadAloud: () -> Unit, onMore: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (message.content.isNotEmpty()) {
                ReplyAction(Icons.Rounded.ContentCopy, stringResource(R.string.chat_copy), onCopy)
                ReplyAction(
                    if (speaking) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp,
                    stringResource(if (speaking) R.string.chat_stop_reading else R.string.chat_read_aloud),
                    onReadAloud,
                )
            }
            ReplyAction(Icons.Rounded.MoreHoriz, stringResource(R.string.chat_more), onMore)
        }
        message.result?.let { result ->
            Text(
                listOfNotNull(
                    result.prefillTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.chat_prefill, Format.rate(it)) },
                    result.decodeTokensPerSecond.takeIf { it > 0 }?.let { stringResource(R.string.chat_decode, Format.rate(it)) },
                    Format.duration(result.totalMs),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A quiet icon in a full touch target: these sit under every reply, so they must not compete with it. */
@Composable
private fun ReplyAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(Dimens.touch)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * The rarer actions for a reply. Reporting is here rather than under every reply: nothing is
 * sent anywhere by the app, the report is a text the person shares where they choose, and
 * Play's generative-AI policy asks only that it be reachable where the content appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreActions(onRegenerate: (() -> Unit)?, onReport: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            onRegenerate?.let { SheetAction(Icons.Rounded.Refresh, stringResource(R.string.chat_regenerate), it) }
            SheetAction(Icons.Rounded.Flag, stringResource(R.string.report_action), onReport)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Where a reply stands: being read or written, refused or stopped. Its figures sit with its actions. */
@Composable
private fun ReplyStatus(message: ChatMessage) {
    val tones = LocalTones.current
    if (message.running && message.content.isEmpty()) {
        Text(
            stringResource(if (message.reasoning.isEmpty()) R.string.chat_waiting else R.string.chat_reasoning_now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = tones.working.color, trackColor = tones.working.container)
    }
    message.failure?.let { failure ->
        Text(
            failure.status?.let { stringResource(R.string.chat_http_error, it, failure.message.orEmpty()) } ?: failure.message.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = tones.failed.color,
        )
    }
    if (message.stopped) {
        Text(
            stringResource(R.string.chat_stopped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The message being written, with dictation and send below it (OpenWeights' composer, without
 * its attachments and commands): one rounded field the width of the screen, its border the
 * focus indicator, and one button that sends or, while a reply is written, stops it.
 */
@Composable
private fun Composer(running: Boolean, model: MainViewModel, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val dictating by model.dictation.state.collectAsState()
    val speechError by model.reader.error.collectAsState()
    // Leaving the chat, or the app (Home, the lock button), while listening must not keep the
    // microphone or type into a draft no one is looking at.
    DisposableEffect(Unit) { onDispose { model.dictation.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.dictation.stop() }
    val border by animateColorAsState(
        if (focused || dictating.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "composer border",
    )
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        (dictating.error ?: speechError)?.let {
            Text(it, Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = LocalTones.current.failed.color)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, border, RoundedCornerShape(24.dp)),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = MAX_LINES,
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            // While listening, the words heard so far show where they will land.
                            Text(
                                dictating.partial.ifEmpty { stringResource(if (dictating.listening) R.string.chat_listening else R.string.chat_message) },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        field()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (model.dictation.available) {
                    DictateButton(dictating.listening) {
                        if (dictating.listening) {
                            model.dictation.stop()
                        } else {
                            model.dictation.start { heard -> draft = if (draft.isEmpty()) heard else "${draft.trimEnd()} $heard" }
                        }
                    }
                }
                SendButton(running, enabled = running || draft.isNotBlank()) {
                    if (running) {
                        onStop()
                    } else {
                        model.dictation.stop()
                        onSend(draft)
                        draft = ""
                    }
                }
            }
        }
    }
}

/**
 * The microphone. Permission is asked on the first tap, never at launch, and stopping never
 * asks: a permission revoked mid-session must not turn Stop into another request.
 */
@Composable
private fun DictateButton(listening: Boolean, onDictate: () -> Unit) {
    val context = LocalContext.current
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onDictate() }
    val label = stringResource(if (listening) R.string.chat_stop_dictation else R.string.chat_dictate)
    IconButton(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (listening || granted) onDictate() else microphone.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = Modifier.size(Dimens.touch),
    ) {
        Icon(
            if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = label,
            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Send, and stop: a 36 dp circle in a 48 dp target, filled only when there is something to do. */
@Composable
private fun SendButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val container = when {
        running -> MaterialTheme.colorScheme.surfaceContainerHighest
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        running -> MaterialTheme.colorScheme.onSurface
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(Dimens.touch)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
            Icon(
                if (running) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(if (running) R.string.chat_stop else R.string.chat_send),
                tint = content,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Eight lines of draft before it scrolls: past that the field eats the conversation. */
private const val MAX_LINES = 8
