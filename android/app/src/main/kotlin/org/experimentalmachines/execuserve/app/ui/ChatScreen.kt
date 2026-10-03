package org.experimentalmachines.execuserve.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.app.BuildConfig
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.text.Format
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
                val target = chat.model?.takeIf { id -> installed.any { it.id == id } }
                    ?: status?.resident?.firstOrNull()?.id ?: settings?.defaultModel?.takeIf { id -> installed.any { it.id == id } } ?: installed.first().id
                val entry = installed.first { it.id == target }
                Controls(installed, entry, chat, model)
                Conversation(chat, ModelNames.shown(entry, installed), Modifier.weight(1f)) { model.sendChat(it, target) }
                Composer(ModelNames.shown(entry, installed), chat.running, onSend = { model.sendChat(it, target) }, onStop = model::stopChat)
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

/** The model, the thinking switch where the model has one, and starting over. */
@Composable
private fun Controls(installed: List<ModelEntry>, entry: ModelEntry, chat: ChatState, model: MainViewModel) {
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { picking = true }, enabled = !chat.running) {
                Text(ModelNames.shown(entry, installed), maxLines = 1, style = MaterialTheme.typography.titleSmall)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(chat: ChatState, modelName: String, modifier: Modifier, onSuggestion: (String) -> Unit) {
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
        Column(modifier.fillMaxWidth().padding(Dimens.gutter), verticalArrangement = Arrangement.Center) {
            Mark(48.dp)
            Text(stringResource(R.string.chat_welcome, modelName), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
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
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = Dimens.gutter, vertical = Dimens.row),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(chat.messages, key = { it.id }) { message ->
            if (message.fromUser) Asked(message) else Reply(message)
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
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer { Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun Reply(message: ChatMessage) {
    val context = LocalContext.current
    var reporting by rememberSaveable(message.id) { mutableStateOf(false) }
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
                Text(ChatText.styled(message.content, MaterialTheme.colorScheme.surfaceContainer), style = MaterialTheme.typography.bodyLarge)
            }
        }
        ReplyStatus(message)
        if (!message.running && (message.content.isNotEmpty() || message.reasoning.isNotEmpty())) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (message.content.isNotEmpty()) {
                    TextButton(onClick = { copy(context, message.content) }) { Text(stringResource(R.string.action_copy)) }
                }
                // Play's generative-AI policy: anything a model showed can be reported where it
                // appears, its reasoning too, even when it stopped before replying.
                TextButton(onClick = { reporting = true }) { Text(stringResource(R.string.report_action)) }
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

/** Where a reply stands: being read or written, refused, stopped, or done with its figures. */
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

/** Write, send; while a reply is written, the same button stops it. */
@Composable
private fun Composer(modelName: String, running: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val sendLabel = stringResource(if (running) R.string.chat_stop else R.string.chat_send)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = Dimens.row),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_placeholder, modelName), maxLines = 1) },
            maxLines = 5,
            shape = RoundedCornerShape(20.dp),
        )
        FilledIconButton(
            onClick = {
                if (running) {
                    onStop()
                } else {
                    onSend(draft)
                    draft = ""
                }
            },
            enabled = running || draft.isNotBlank(),
            modifier = Modifier.size(Dimens.touch).semantics { contentDescription = sendLabel },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(if (running) "■" else "↑", style = MaterialTheme.typography.titleLarge)
        }
    }
    Spacer(Modifier.size(4.dp))
}
