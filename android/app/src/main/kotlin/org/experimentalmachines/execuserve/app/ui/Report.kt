package org.experimentalmachines.execuserve.app.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.experimentalmachines.execuserve.app.R

/**
 * Reporting a model's reply, as Play's generative-AI policy asks: from inside the app, without
 * leaving it until the person chooses to send.
 *
 * The report goes nowhere on its own. The dialog shows exactly what it will contain (the
 * model, the reason, the note, the reply and the app version) and hands that text to the
 * system share sheet; the person picks where it goes. There is no server to send it to, and a
 * hard-coded destination such as a public issue tracker would publish a reply and a note under
 * someone's name without asking (the same decision OpenWeights made).
 */
object ContentReport {
    enum class Reason(@param:StringRes val words: Int) {
        OFFENSIVE(R.string.report_reason_offensive),
        HARMFUL(R.string.report_reason_harmful),
        SEXUAL(R.string.report_reason_sexual),
        FALSE(R.string.report_reason_false),
        OTHER(R.string.report_reason_other),
    }

    /** Long enough for any reply the console asks for, short enough for a share intent. */
    const val MAX_REPLY_CHARS = 8_000

    /** Where a mailed report goes: the contact the README and the privacy policy publish. */
    const val CONTACT = "alpha@experimentalmachines.org"

    /**
     * The report's text: a heading, labelled fields (blank ones left out), the model's
     * reasoning when it showed any (it is on screen, so it can be what is wrong), then the reply.
     */
    fun text(
        heading: String,
        fields: List<Pair<String, String>>,
        replyLabel: String,
        reply: String,
        reasoningLabel: String = "",
        reasoning: String = "",
    ): String = buildString {
        appendLine(heading)
        appendLine()
        fields.filter { it.second.isNotBlank() }.forEach { (label, value) -> appendLine("$label: ${value.trim()}") }
        if (reasoning.isNotBlank()) {
            appendLine()
            appendLine(reasoningLabel)
            appendLine(clip(reasoning))
        }
        appendLine()
        appendLine(replyLabel)
        append(clip(reply))
    }

    private fun clip(text: String): String = text.trim().let { if (it.length > MAX_REPLY_CHARS) it.take(MAX_REPLY_CHARS) + "…" else it }

    /**
     * The share sheet, with the report as plain text; nothing is sent until the person picks.
     * A mail app gets the project's published contact as its recipient (README, privacy
     * policy), so a report has somewhere to go; any other app is the person's choice.
     */
    fun share(context: Context, subject: String, text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}

/**
 * Choose a reason, add a note if you like, see the report, then share it. Share stays off
 * until a reason is chosen, so every report says what was wrong.
 */
@Composable
fun ReportDialog(
    model: String,
    reply: String,
    version: String,
    onDismiss: () -> Unit,
    onShare: (subject: String, text: String) -> Unit,
    reasoning: String = "",
) {
    var reason by rememberSaveable { mutableStateOf<ContentReport.Reason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    val heading = stringResource(R.string.report_heading)
    val text = ContentReport.text(
        heading,
        listOf(
            stringResource(R.string.report_field_model) to model,
            stringResource(R.string.report_field_reason) to (reason?.let { stringResource(it.words) } ?: ""),
            stringResource(R.string.report_field_note) to note,
            stringResource(R.string.report_field_version) to version,
        ),
        stringResource(R.string.report_field_reply),
        reply,
        stringResource(R.string.report_field_reasoning),
        reasoning,
    )
    val subject = stringResource(R.string.report_subject, model)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.report_intro), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    ContentReport.Reason.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = reason == option, onClick = { reason = option }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == option, onClick = null)
                            Text(stringResource(option.words), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_NOTE_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.report_note)) },
                    maxLines = 4,
                )
                Text(stringResource(R.string.report_preview), style = MaterialTheme.typography.labelLarge)
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text,
                        Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onShare(subject, text) }, enabled = reason != null) { Text(stringResource(R.string.report_share)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_NOTE_CHARS = 1_000
