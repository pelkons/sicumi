package app.sicumi.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sicumi.R
import app.sicumi.meetings.Meeting
import app.sicumi.meetings.MeetingErrors
import app.sicumi.meetings.MeetingStatus
import app.sicumi.pipeline.MeetingProcessor
import app.sicumi.protocol.ActionItem
import app.sicumi.protocol.Decision
import app.sicumi.protocol.Participant
import app.sicumi.protocol.Protocol
import app.sicumi.protocol.Note
import app.sicumi.protocol.ProtocolItem
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes

@Composable
internal fun ProtocolTab(
    meeting: Meeting,
    protocol: Protocol?,
    editing: Boolean,
    onChange: (Protocol) -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when {
        meeting.status.isProcessing -> ProcessingCard(meeting)
        meeting.status == MeetingStatus.Failed -> FailedCard(meeting, onRetry, onOpenSettings)
    }
    when {
        protocol == null -> if (!meeting.status.isProcessing && meeting.status != MeetingStatus.Failed) {
            Text(stringResource(R.string.protocol_missing), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Muted)
        }
        editing -> ProtocolEditor(protocol, onChange)
        else -> ProtocolView(protocol, onChange, onSeek)
    }
}

// --- Состояния обработки ---

@Composable
private fun ProcessingCard(meeting: Meeting) {
    val progressMap by MeetingProcessor.progress.collectAsState()
    val progress = progressMap[meeting.id]
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.White)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.protocol_processing_title), style = MaterialTheme.typography.titleMedium, color = SicumiColors.Ink)
        Text(stringResource(MeetingErrors.statusLabel(meeting.status)), style = MaterialTheme.typography.labelLarge, color = SicumiColors.PeachText)
        ProgressLine(progress)
        Text(stringResource(R.string.protocol_processing_body), style = MaterialTheme.typography.bodySmall, color = SicumiColors.Muted)
    }
}

/** Полоса прогресса; null — неопределённый прогресс (показываем короткий сегмент). */
@Composable
internal fun ProgressLine(progress: Float?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(SicumiShapes.Pill)
            .background(SicumiColors.Background),
    ) {
        Box(
            Modifier
                .fillMaxWidth((progress ?: 0.15f).coerceIn(0.03f, 1f))
                .height(6.dp)
                .clip(SicumiShapes.Pill)
                .background(SicumiColors.Tangerine),
        )
    }
}

@Composable
private fun FailedCard(meeting: Meeting, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.Peach)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.status_failed), style = MaterialTheme.typography.titleMedium, color = SicumiColors.Ink)
        Text(MeetingErrors.message(context, meeting), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.action_retry), R.drawable.ic_retry, primary = true, onClick = onRetry)
            if (MeetingErrors.needsSettings(meeting)) {
                PillButton(stringResource(R.string.action_to_settings), R.drawable.ic_settings, primary = false, onClick = onOpenSettings)
            }
        }
    }
}

@Composable
internal fun PillButton(label: String, icon: Int?, primary: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = SicumiShapes.Pill, color = if (primary) SicumiColors.Violet else SicumiColors.White) {
        Row(
            Modifier
                .height(44.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val color = if (primary) SicumiColors.White else SicumiColors.Ink
            if (icon != null) Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

// --- Просмотр ---

@Composable
private fun ProtocolView(p: Protocol, onChange: (Protocol) -> Unit, onSeek: (Long) -> Unit) {
    if (p.summary.isNotBlank()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(SicumiShapes.Card)
                .background(SicumiColors.Violet)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.protocol_summary), style = SectionTitle, color = SicumiColors.White)
            Text(p.summary, style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Background)
        }
    }
    if (p.subject.isNotBlank()) {
        Text(
            stringResource(R.string.protocol_subject) + ": " + p.subject,
            style = MaterialTheme.typography.titleMedium,
            color = SicumiColors.Ink,
        )
    }
    if (p.participants.isNotEmpty()) {
        SectionCard(stringResource(R.string.protocol_participants)) {
            p.participants.forEach { person ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOf(person.role, person.name).filter { it.isNotBlank() }.joinToString(": "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SicumiColors.Ink,
                        )
                        if (person.company.isNotBlank()) {
                            Text(person.company, style = MaterialTheme.typography.bodySmall, color = SicumiColors.Muted)
                        }
                    }
                    if (person.attendance.isNotBlank()) AttendanceChip(person.attendance)
                }
            }
        }
    }
    p.items.forEachIndexed { index, item -> ItemCard(index + 1, item) }
    if (p.decisions.isNotEmpty()) {
        SectionCard(stringResource(R.string.protocol_decisions)) {
            p.decisions.forEach { d ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(d.text, style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Ink, modifier = Modifier.weight(1f))
                    parseTime(d.at)?.let { ms -> TimeChip(ms) { onSeek(ms) } }
                }
            }
        }
    }
    if (p.actionItems.isNotEmpty()) {
        SectionCard(stringResource(R.string.protocol_tasks)) {
            p.actionItems.forEachIndexed { i, a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = a.done,
                        onCheckedChange = { checked ->
                            onChange(p.copy(actionItems = p.actionItems.toMutableList().also { it[i] = a.copy(done = checked) }))
                        },
                        colors = CheckboxDefaults.colors(checkedColor = SicumiColors.Violet, uncheckedColor = SicumiColors.Muted),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            a.task,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (a.done) SicumiColors.Muted else SicumiColors.Ink,
                            textDecoration = if (a.done) TextDecoration.LineThrough else null,
                        )
                        val meta = listOfNotNull(
                            a.owner.takeIf { it.isNotBlank() },
                            a.due.takeIf { it.isNotBlank() }?.let { stringResource(R.string.protocol_until) + " \u2066" + it + "\u2069" },
                        )
                        if (meta.isNotEmpty()) {
                            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = SicumiColors.Muted)
                        }
                    }
                }
            }
        }
    }
    if (p.nextMeeting.isNotBlank()) {
        SectionCard(stringResource(R.string.protocol_next_meeting)) {
            Text(p.nextMeeting, style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Ink)
        }
    }
    if (p.cc.isNotEmpty()) {
        SectionCard(stringResource(R.string.protocol_cc)) {
            Text(p.cc.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Ink)
        }
    }
}

/** Строка таблицы протокола как карточка: номер и тема, замечания, внизу ответственные и срок. */
@Composable
private fun ItemCard(number: Int, item: ProtocolItem) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.White)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(SicumiShapes.Button)
                    .background(SicumiColors.Lilac),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = SicumiColors.Violet)
            }
            Text(item.topic, style = SectionTitle, color = SicumiColors.Ink, modifier = Modifier.weight(1f))
        }
        item.notes.forEach { note ->
            if (note.important) {
                Text(
                    note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SicumiColors.Ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Highlight)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            } else {
                Bullet(note.text)
            }
        }
        if (item.owner.isNotBlank() || item.due.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.owner.isNotBlank()) {
                    Text(
                        stringResource(R.string.protocol_owner) + ": " + item.owner,
                        style = MaterialTheme.typography.bodySmall,
                        color = SicumiColors.Muted,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                if (item.due.isNotBlank()) DueChip(item.due)
            }
        }
    }
}

@Composable
private fun DueChip(due: String) {
    Box(
        Modifier
            .clip(SicumiShapes.Pill)
            .background(SicumiColors.Peach)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text("\u2068" + due + "\u2069", style = MaterialTheme.typography.labelSmall, color = SicumiColors.PeachText)
    }
}

@Composable
private fun AttendanceChip(value: String) {
    // «נכח/נכחה» — зелёный, «לא …» — нейтральный.
    val present = !value.startsWith("לא")
    Box(
        Modifier
            .clip(SicumiShapes.Pill)
            .background(if (present) SicumiColors.SuccessBg else SicumiColors.Background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(value, style = MaterialTheme.typography.labelSmall, color = if (present) SicumiColors.Success else SicumiColors.Muted)
    }
}

/** Жёлтое выделение важных замечаний — как маркер в бумажных протоколах. */
private val Highlight = Color(0xFFFFF1A8)

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .padding(top = 9.dp)
                .size(6.dp)
                .clip(SicumiShapes.Pill)
                .background(SicumiColors.Tangerine),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Ink)
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.White)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = SectionTitle, color = SicumiColors.Ink)
        content()
    }
}

@Composable
internal fun TimeChip(ms: Long, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = SicumiColors.Peach) {
        Row(
            Modifier
                .height(28.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(R.drawable.ic_play), null, tint = SicumiColors.PeachText, modifier = Modifier.size(12.dp))
            Text(
                "⁦" + app.sicumi.meetings.MeetingTitles.duration(ms) + "⁩",
                style = MaterialTheme.typography.labelSmall,
                color = SicumiColors.PeachText,
            )
        }
    }
}

/** "12:04" / "1:02:03" → мс. */
internal fun parseTime(text: String): Long? {
    val parts = text.trim().removePrefix("[").removeSuffix("]").split(':')
    if (parts.size !in 2..3) return null
    val nums = parts.map { it.toLongOrNull() ?: return null }
    return if (nums.size == 3) (nums[0] * 3600 + nums[1] * 60 + nums[2]) * 1000 else (nums[0] * 60 + nums[1]) * 1000
}

private val SectionTitle @Composable get() = MaterialTheme.typography.titleLarge.copy(
    fontSize = 18.sp,
    lineHeight = 24.sp,
)

// --- Редактирование ---

@Composable
private fun ProtocolEditor(p: Protocol, onChange: (Protocol) -> Unit) {
    SectionCard(stringResource(R.string.edit_title)) {
        Field(p.title, stringResource(R.string.edit_title)) { onChange(p.copy(title = it)) }
        Field(p.subject, stringResource(R.string.edit_subject)) { onChange(p.copy(subject = it)) }
    }
    SectionCard(stringResource(R.string.protocol_summary)) {
        Field(p.summary, stringResource(R.string.protocol_summary), singleLine = false) { onChange(p.copy(summary = it)) }
    }
    SectionCard(stringResource(R.string.protocol_participants)) {
        EditableList(
            items = p.participants,
            onChange = { onChange(p.copy(participants = it)) },
            newItem = { Participant("", "", "", "") },
        ) { item, update ->
            Field(item.role, stringResource(R.string.edit_role)) { update(item.copy(role = it)) }
            Field(item.name, stringResource(R.string.edit_name)) { update(item.copy(name = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field(item.company, stringResource(R.string.edit_company)) { update(item.copy(company = it)) } }
                Box(Modifier.weight(1f)) { Field(item.attendance, stringResource(R.string.edit_attendance)) { update(item.copy(attendance = it)) } }
            }
        }
    }
    SectionCard(stringResource(R.string.protocol_items)) {
        EditableList(
            items = p.items,
            onChange = { onChange(p.copy(items = it)) },
            newItem = { ProtocolItem("", listOf(Note("", false)), "", "") },
        ) { item, update ->
            Field(item.topic, stringResource(R.string.protocol_topic)) { update(item.copy(topic = it)) }
            EditableList(
                items = item.notes,
                onChange = { update(item.copy(notes = it)) },
                newItem = { Note("", false) },
            ) { note, updateNote ->
                Field(note.text, stringResource(R.string.edit_note), singleLine = false) { updateNote(note.copy(text = it)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = note.important,
                        onCheckedChange = { updateNote(note.copy(important = it)) },
                        colors = CheckboxDefaults.colors(checkedColor = SicumiColors.Violet, uncheckedColor = SicumiColors.Muted),
                    )
                    Text(stringResource(R.string.edit_important), style = MaterialTheme.typography.bodySmall, color = SicumiColors.Ink)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field(item.owner, stringResource(R.string.protocol_owner)) { update(item.copy(owner = it)) } }
                Box(Modifier.weight(1f)) { Field(item.due, stringResource(R.string.protocol_due)) { update(item.copy(due = it)) } }
            }
        }
    }
    SectionCard(stringResource(R.string.protocol_decisions)) {
        EditableList(
            items = p.decisions,
            onChange = { onChange(p.copy(decisions = it)) },
            newItem = { Decision("", "") },
        ) { item, update ->
            Field(item.text, stringResource(R.string.edit_item), singleLine = false) { update(item.copy(text = it)) }
        }
    }
    SectionCard(stringResource(R.string.protocol_tasks)) {
        EditableList(
            items = p.actionItems,
            onChange = { onChange(p.copy(actionItems = it)) },
            newItem = { ActionItem("", "", "") },
        ) { item, update ->
            Field(item.task, stringResource(R.string.edit_task), singleLine = false) { update(item.copy(task = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field(item.owner, stringResource(R.string.edit_owner)) { update(item.copy(owner = it)) } }
                Box(Modifier.weight(1f)) { Field(item.due, stringResource(R.string.edit_due)) { update(item.copy(due = it)) } }
            }
        }
    }
    SectionCard(stringResource(R.string.protocol_next_meeting)) {
        Field(p.nextMeeting, stringResource(R.string.protocol_next_meeting)) { onChange(p.copy(nextMeeting = it)) }
    }
    SectionCard(stringResource(R.string.protocol_cc)) {
        EditableList(
            items = p.cc,
            onChange = { onChange(p.copy(cc = it)) },
            newItem = { "" },
        ) { item, update ->
            Field(item, stringResource(R.string.edit_name)) { update(it) }
        }
    }
}

/** Список с удалением элементов и кнопкой «הוספה». */
@Composable
private fun <T> EditableList(
    items: List<T>,
    onChange: (List<T>) -> Unit,
    newItem: () -> T,
    editor: @Composable ColumnScope.(item: T, update: (T) -> Unit) -> Unit,
) {
    items.forEachIndexed { i, item ->
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SicumiColors.Background)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            this.editor(item) { changed -> onChange(items.toMutableList().also { it[i] = changed }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = { onChange(items.toMutableList().also { it.removeAt(i) }) },
                    shape = SicumiShapes.Pill,
                    color = Color.Transparent,
                ) {
                    Text(
                        stringResource(R.string.action_remove),
                        style = MaterialTheme.typography.labelLarge,
                        color = DangerRed,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    PillButton(stringResource(R.string.action_add), null, primary = false) { onChange(items + newItem()) }
}

@Composable
private fun Field(value: String, label: String, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SicumiColors.Violet,
            unfocusedBorderColor = SicumiColors.Lilac,
            focusedLabelColor = SicumiColors.Violet,
            focusedContainerColor = SicumiColors.White,
            unfocusedContainerColor = SicumiColors.White,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
