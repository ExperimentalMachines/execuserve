package org.experimentalmachines.execuserve.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.text.Format
import org.experimentalmachines.execuserve.catalog.HfCatalog
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.LaneState
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ResidentInfo
import org.experimentalmachines.execuserve.host.CONSOLE_KEY
import org.experimentalmachines.execuserve.host.ConsoleChat
import org.experimentalmachines.execuserve.host.ModelNames
import org.experimentalmachines.execuserve.host.ServeHost

/**
 * Chat with the models this phone hosts, through its own server: the same API, key checks
 * and queue another app gets, so trying a model here is trying it as a client. One
 * conversation, kept in memory. A thin wrapper over the server, laid out like OpenWeights'
 * chat without its harness: no attachments, commands, history or compaction.
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
            // A model without a chat template answers raw prompts only (the Completions API).
            installed.none { ConsoleChat.canChat(it) } -> Gate(
                stringResource(R.string.chat_no_template_title),
                stringResource(R.string.chat_no_template_note),
                stringResource(R.string.chat_no_models_action),
                openModels,
            )
            server !is ServeHost.State.Running -> Gate(
                stringResource(R.string.chat_start_title),
                stringResource(R.string.chat_start_note),
                stringResource(R.string.action_start_hosting),
                model::start,
            )
            else -> {
                // Whatever is in memory answers fastest, so it is the suggestion until someone picks.
                // Every candidate must still be installed: a model removed from disk and rescanned
                // can stay resident until it is unloaded.
                val chattable = installed.filter { ConsoleChat.canChat(it) }
                fun installedOrNull(id: String?) = chattable.firstOrNull { it.id == id }
                val entry = installedOrNull(chat.model)
                    ?: status?.resident.orEmpty().firstNotNullOfOrNull { installedOrNull(it.id) }
                    ?: installedOrNull(settings?.defaultModel)
                    ?: chattable.first()
                val target = entry.id
                var picking by rememberSaveable { mutableStateOf(false) }
                // Sideways with the keyboard up there is room for the conversation and the field only.
                if (!typingInShortWindow()) ChatTopBar(entry, installed, status, chat, onPick = { picking = true }, onNewChat = model::newChat)
                LoadFailure(status?.broken?.get(entry.id)) { model.retry(entry.id) }
                Transcript(chat, ModelNames.shown(entry, installed), model, Modifier.weight(1f)) { model.sendChat(it, target) }
                Composer(
                    running = chat.running,
                    loading = status?.lane == LaneState.LOADING && status?.loading == entry.id,
                    model = model,
                    leading = {
                        if (ConsoleChat.canThink(entry)) ThinkChip(chat.thinking, enabled = !chat.running) { model.setChatThinking(it) }
                    },
                    onSend = { model.sendChat(it, target) },
                    onStop = model::stopChat,
                )
                if (picking) {
                    ModelPickerSheet(
                        installed = installed,
                        options = chattable,
                        active = entry,
                        resident = status?.resident.orEmpty(),
                        limit = settings?.memoryLimit ?: 1,
                        onSelect = {
                            picking = false
                            model.chooseChatModel(it.id)
                        },
                        onManage = {
                            picking = false
                            openModels()
                        },
                        onDismiss = { picking = false },
                    )
                }
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
        Text(title, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            note,
            Modifier.padding(top = 8.dp, bottom = 20.dp).widthIn(max = 360.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction) { Text(action) }
    }
}

/**
 * The bar over the conversation (OpenWeights' ChatTopBar): which model answers, raising the
 * picker from its name, with what it runs on and where it stands beneath; and a new chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(entry: ModelEntry, installed: List<ModelEntry>, status: EngineStatus?, chat: ChatState, onPick: () -> Unit, onNewChat: () -> Unit) {
    val state = stringResource(
        when {
            status?.broken?.containsKey(entry.id) == true -> R.string.chat_load_failed
            status?.resident.orEmpty().any { it.id == entry.id } -> R.string.chat_in_memory
            status?.lane == LaneState.LOADING && status.loading == entry.id -> R.string.chat_loading
            else -> R.string.chat_loads_on_send
        },
    )
    val identity = listOfNotNull(
        shortProcessor(entry.backend),
        entry.contextLength?.let { stringResource(R.string.host_model_context, Format.window(it)) },
        state,
    ).joinToString(" · ")
    TopAppBar(
        title = {
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !chat.running, role = Role.Button, onClick = onPick)
                    .heightIn(min = Dimens.touch)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        ModelNames.shown(entry, installed),
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    identity,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(onClick = onNewChat, enabled = chat.messages.isNotEmpty() && !chat.running) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.chat_new))
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** The chosen model failed to load: the runtime's reason, and trying again. */
@Composable
private fun LoadFailure(failure: String?, onRetry: () -> Unit) {
    failure ?: return
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            failure,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.host_retry)) }
    }
}

/**
 * Which model answers, and changing it (OpenWeights' ModelPickerSheet): the models that can
 * chat, each with what it runs on and what choosing it does to memory, then the library.
 * The list is a plain scrolling column capped by the screen, not a lazy list in a weight: the
 * sheet's height fed the list's and the list's the sheet's, and the open sheet rose and fell.
 */
@Composable
private fun ModelPickerSheet(
    installed: List<ModelEntry>,
    options: List<ModelEntry>,
    active: ModelEntry,
    resident: List<ResidentInfo>,
    limit: Int,
    onSelect: (ModelEntry) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val listMax = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * LIST_SHARE).toDp() }
    ActionsSheet(onDismiss) {
        Column(Modifier.heightIn(max = listMax).verticalScroll(rememberScrollState())) {
            options.forEach { option ->
                PickerRow(
                    name = ModelNames.shown(option, installed),
                    detail = listOfNotNull(
                        shortProcessor(option.backend),
                        option.contextLength?.let { stringResource(R.string.host_model_context, Format.window(it)) },
                        switchConsequence(option, resident, limit, installed),
                    ).joinToString(" · "),
                    active = option.id == active.id,
                    onClick = { onSelect(option) },
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onManage).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(TICK))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_manage_models), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.chat_manage_models_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(TICK).clearAndSetSemantics {},
            )
        }
    }
}

/** One model: a tick when it is the one answering, its name, and a line on what it is. */
@Composable
private fun PickerRow(name: String, detail: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (active) {
            Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.chat_model_current), modifier = Modifier.size(TICK))
        } else {
            Spacer(Modifier.size(TICK))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * What choosing [option] does to memory, as things stand now (another client can change it
 * before the load): already in memory, loads, or loads and unloads the least recently used.
 */
@Composable
private fun switchConsequence(option: ModelEntry, resident: List<ResidentInfo>, limit: Int, installed: List<ModelEntry>): String {
    if (resident.any { it.id == option.id }) return stringResource(R.string.chat_in_memory)
    val evicted = resident.takeIf { it.size >= limit }?.minByOrNull { it.lastUsedMs } ?: return stringResource(R.string.chat_switch_loads)
    val name = installed.firstOrNull { it.id == evicted.id }?.let { ModelNames.shown(it, installed) } ?: evicted.id
    return stringResource(R.string.chat_switch_unloads, name)
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

/** The conversation, capped to a readable width, with the way back to the latest message. */
@Composable
private fun Transcript(chat: ChatState, modelName: String, model: MainViewModel, modifier: Modifier, onSuggestion: (String) -> Unit) {
    // Opened at the newest message: started at the top, the first frame drew the oldest one.
    val list =
        rememberLazyListState(initialFirstVisibleItemIndex = chat.messages.lastIndex.coerceAtLeast(0), initialFirstVisibleItemScrollOffset = Int.MAX_VALUE)
    var follow by rememberFollow(list, chat)
    if (chat.messages.isEmpty()) {
        Welcome(modelName, modifier, onSuggestion)
        return
    }
    val speaking by model.reader.speaking.collectAsState()
    val status by model.status.collectAsState()
    val scope = rememberCoroutineScope()
    val away by remember { derivedStateOf { list.canScrollForward } }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize().wrapContentWidth().widthIn(max = READABLE_WIDTH),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(chat.messages, key = { it.id }) { message ->
                if (message.fromUser) {
                    UserTurn(message)
                } else {
                    AssistantTurn(
                        message,
                        phase = if (message.running) replyPhase(status, message) else null,
                        speaking = speaking == message.id,
                        onReadAloud = { if (speaking == message.id) model.reader.stop() else model.reader.speak(message.id, message.content) },
                    )
                }
            }
        }
        // Scrolled away from the end: the way back, which also resumes following.
        AnimatedVisibility(
            visible = !follow && away,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    follow = true
                    scope.launch { list.scrollToItem(chat.messages.lastIndex, Int.MAX_VALUE) }
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.chat_latest), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * What a reply is waiting on, from the engine rather than assumed: behind another app's
 * request, the model loading into memory, the prompt being read, thinking, or writing.
 */
private fun replyPhase(status: EngineStatus?, message: ChatMessage): Int {
    val job = status?.running
    val ours = job?.client == CONSOLE_KEY
    return when {
        message.content.isNotEmpty() -> R.string.chat_phase_writing
        message.reasoning.isNotEmpty() -> R.string.chat_reasoning_now
        status?.lane == LaneState.LOADING && (ours || status.loading == message.model) -> R.string.chat_phase_loading
        job != null && !ours -> R.string.chat_phase_queued
        status?.lane == LaneState.LOADING -> R.string.chat_phase_queued
        else -> R.string.chat_waiting
    }
}

/**
 * Whether the list follows the end of the conversation. Following the end is a decision, not
 * a measurement: a growing reply is taller than the screen long before it ends, so "is the
 * end in view" turns false on its own. Sending turns following on; a finger on the list turns
 * it off at once; scrolling back to the end turns it on.
 */
@Composable
private fun rememberFollow(list: LazyListState, chat: ChatState): MutableState<Boolean> {
    val follow = remember { mutableStateOf(true) }
    val last = chat.messages.lastOrNull()
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow.value = false }
    }
    LaunchedEffect(list.isScrollInProgress) {
        if (!list.isScrollInProgress && chat.messages.isNotEmpty()) follow.value = !list.canScrollForward
    }
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) {
            follow.value = true
            list.scrollToItem(chat.messages.lastIndex, Int.MAX_VALUE)
        }
    }
    LaunchedEffect(last?.content?.length, last?.reasoning?.length, last?.running) {
        if (follow.value && !list.isScrollInProgress && chat.messages.isNotEmpty()) list.scrollToItem(chat.messages.lastIndex, Int.MAX_VALUE)
    }
    return follow
}

/** An empty chat: the mark, which model answers, where replies come from, and three ways to begin. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(modelName: String, modifier: Modifier, onSuggestion: (String) -> Unit) {
    Column(
        modifier.fillMaxWidth().wrapContentWidth().widthIn(max = READABLE_WIDTH).padding(horizontal = 16.dp, vertical = Dimens.gutter),
        verticalArrangement = Arrangement.Center,
    ) {
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
}

/**
 * Thinking on or off, where the message is written (OpenWeights' ThinkChip): the word, the
 * icon and the fill all change, so any one of them says which way it is set.
 */
@Composable
private fun ThinkChip(thinking: Boolean, enabled: Boolean, onThinking: (Boolean) -> Unit) {
    FilterChip(
        selected = thinking,
        onClick = { onThinking(!thinking) },
        enabled = enabled,
        label = { Text(stringResource(if (thinking) R.string.chat_thinking_on else R.string.chat_thinking_off), style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(if (thinking) Icons.Rounded.Check else Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.padding(start = 8.dp),
    )
}

/**
 * Where the message is written (OpenWeights' composer, without attachments or commands): one
 * rounded container with the text above and the controls beneath, its border the focus
 * indicator; thinking on the left, dictation and one button that sends or stops on the right.
 */
@Composable
private fun Composer(running: Boolean, loading: Boolean, model: MainViewModel, leading: @Composable () -> Unit, onSend: (String) -> Unit, onStop: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val dictating by model.dictation.state.collectAsState()
    val speechError by model.reader.error.collectAsState()
    // Leaving the chat, or the app, while listening must not keep the microphone.
    DisposableEffect(Unit) { onDispose { model.dictation.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.dictation.stop() }
    val border by animateColorAsState(
        if (focused || dictating.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "composer border",
    )
    Column(Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = READABLE_WIDTH).padding(horizontal = 12.dp, vertical = 8.dp)) {
        (dictating.error ?: speechError)?.let {
            Text(it, Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(COMPOSER_CORNER))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, border, RoundedCornerShape(COMPOSER_CORNER)),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
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
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                leading()
                if (loading) LoadingHint(Modifier.weight(1f).padding(start = 8.dp)) else Spacer(Modifier.weight(1f))
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

/** Between the controls while the chosen model comes into memory: sending still works, and waits. */
@Composable
private fun LoadingHint(modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.chat_loading_model),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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

/** Past seventy or eighty characters a line, prose stops being comfortable; on phones a no-op. */
private val READABLE_WIDTH = 720.dp
private val COMPOSER_CORNER = 24.dp
private val TICK = 20.dp

/** The share of the screen the picker's list may take before it scrolls, so its footer stays in view. */
private const val LIST_SHARE = 0.55f
