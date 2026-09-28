package app.sicumi.meeting

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.sicumi.R
import app.sicumi.meetings.Meeting
import app.sicumi.meetings.MeetingRepository
import app.sicumi.meetings.MeetingStatus
import app.sicumi.meetings.MeetingTitles
import app.sicumi.pipeline.MeetingProcessor
import app.sicumi.protocol.Protocol
import app.sicumi.protocol.ProtocolExporter
import app.sicumi.providers.TranscriptSegment
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAB_PROTOCOL = 0
private const val TAB_TRANSCRIPT = 1
private const val TAB_RECORDING = 2

/** Экран встречи (макет C · Протокол): вкладки протокол / транскрипт / запись. */
@Composable
fun MeetingScreen(meetingId: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { MeetingRepository.get(context) }
    val meetings by repo.meetings.collectAsState()
    val meeting = meetings.firstOrNull { it.id == meetingId }
    if (meeting == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(TAB_PROTOCOL) }
    var editing by rememberSaveable { mutableStateOf(false) }

    // Протокол и транскрипт перечитываются, когда меняется статус (например, обработка завершилась).
    var protocol by remember(meeting.id, meeting.status) {
        mutableStateOf(repo.readJson(meeting.id, MeetingRepository.PROTOCOL)?.let { Protocol.fromJson(it) })
    }
    val transcript = remember(meeting.id, meeting.status) {
        TranscriptSegment.listFromJson(repo.readJson(meeting.id, MeetingRepository.TRANSCRIPT))
    }
    val player = remember(meeting.id, meeting.segments) {
        SegmentPlayer(repo.dir(meeting.id), meeting.segments, meeting.durationMs)
    }
    DisposableEffect(player) { onDispose { player.release() } }

    fun saveProtocol(p: Protocol) {
        protocol = p
        repo.writeJson(meeting.id, MeetingRepository.PROTOCOL, p.toJson())
        if (p.title.isNotBlank() && p.title != meeting.title) repo.update(meeting.id) { it.copy(title = p.title) }
    }

    fun seek(ms: Long) {
        tab = TAB_RECORDING
        player.seekTo(ms)
        player.play()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SicumiColors.Background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(
                meeting = meeting,
                canEdit = protocol != null && tab == TAB_PROTOCOL,
                editing = editing,
                onBack = onBack,
                onToggleEdit = { editing = !editing },
                onRegenerate = {
                    editing = false
                    repo.dir(meeting.id).resolve(MeetingRepository.PROTOCOL).delete()
                    repo.update(meeting.id) { it.copy(status = MeetingStatus.Recorded, error = null) }
                    MeetingProcessor.enqueue(context, meeting.id)
                },
                onDelete = {
                    MeetingProcessor.cancel(meeting.id)
                    player.release()
                    repo.delete(meeting.id)
                },
            )
            TitleBlock(meeting, protocol)
            Tabs(selected = tab, onSelect = { tab = it; if (it != TAB_PROTOCOL) editing = false })

            when (tab) {
                TAB_PROTOCOL -> ProtocolTab(
                    meeting = meeting,
                    protocol = protocol,
                    editing = editing,
                    onChange = ::saveProtocol,
                    onSeek = ::seek,
                    onRetry = { MeetingProcessor.enqueue(context, meeting.id) },
                    onOpenSettings = onOpenSettings,
                )
                TAB_TRANSCRIPT -> TranscriptTab(transcript = transcript, onSeek = ::seek)
                else -> RecordingTab(meeting = meeting, player = player)
            }
        }

        val p = protocol
        if (p != null && !editing) {
            ShareButton(
                protocol = p,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(20.dp),
            )
        }
    }
}

@Composable
private fun Header(
    meeting: Meeting,
    canEdit: Boolean,
    editing: Boolean,
    onBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareButton(R.drawable.ic_back, stringResource(R.string.back), onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canEdit) {
                SquareButton(
                    if (editing) R.drawable.ic_check else R.drawable.ic_edit,
                    stringResource(if (editing) R.string.action_done else R.string.action_edit),
                    onToggleEdit,
                    highlighted = editing,
                )
            }
            Box {
                SquareButton(R.drawable.ic_more, stringResource(R.string.action_more), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (meeting.status == MeetingStatus.Ready || meeting.status == MeetingStatus.Failed) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_regenerate)) },
                            onClick = {
                                menu = false
                                onRegenerate()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete_meeting), color = DangerRed) },
                        onClick = {
                            menu = false
                            confirmDelete = true
                        },
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.key_delete), color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            containerColor = SicumiColors.White,
        )
    }
}

@Composable
private fun TitleBlock(meeting: Meeting, protocol: Protocol?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(meeting.title, style = MaterialTheme.typography.headlineMedium, color = SicumiColors.Ink)
        val parts = buildList {
            add("⁦" + MeetingTitles.dateTime(meeting.createdAt) + "⁩")
            when {
                meeting.durationMs >= 60_000 -> add(stringResource(R.string.meeting_minutes, ((meeting.durationMs + 30_000) / 60_000).toInt()))
                meeting.durationMs > 0 -> add("\u2066" + MeetingTitles.duration(meeting.durationMs) + "\u2069")
            }
            val n = protocol?.participants?.size ?: 0
            if (n > 0) add(pluralStringResource(R.plurals.participants_count, n, n))
        }
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Muted)
    }
}

@Composable
private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.tab_protocol, R.string.tab_transcript, R.string.tab_recording)
    Row(
        Modifier
            .fillMaxWidth()
            .background(SicumiColors.Lilac, RoundedCornerShape(20.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val isSelected = i == selected
            Surface(
                onClick = { onSelect(i) },
                shape = SicumiShapes.Button,
                color = if (isSelected) SicumiColors.Violet else SicumiColors.Lilac,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) SicumiColors.White else SicumiColors.Ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareButton(protocol: Protocol, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { menu = true },
            shape = RoundedCornerShape(22.dp),
            color = SicumiColors.Tangerine,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier
                    .height(60.dp)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.ic_share), null, tint = SicumiColors.Ink, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.action_share), style = MaterialTheme.typography.titleMedium, color = SicumiColors.Ink)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_text)) },
                onClick = {
                    menu = false
                    shareText(context, protocol)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_pdf)) },
                onClick = {
                    menu = false
                    scope.launch {
                        val file = runCatching { withContext(Dispatchers.IO) { ProtocolExporter.pdf(context, protocol) } }.getOrNull()
                        shareFile(context, file, "application/pdf", protocol.title)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_as_docx)) },
                onClick = {
                    menu = false
                    scope.launch {
                        val file = runCatching { withContext(Dispatchers.IO) { ProtocolExporter.docx(context, protocol) } }.getOrNull()
                        shareFile(
                            context,
                            file,
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            protocol.title,
                        )
                    }
                },
            )
        }
    }
}

private fun shareText(context: Context, protocol: Protocol) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, protocol.title)
        .putExtra(Intent.EXTRA_TEXT, protocol.toPlainText(ProtocolExporter.labels(context)))
    startChooser(context, intent)
}

private fun shareFile(context: Context, file: File?, mime: String, title: String) {
    if (file == null) {
        Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startChooser(context, intent)
}

private fun startChooser(context: Context, intent: Intent) {
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun SquareButton(icon: Int, label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = SicumiShapes.Button,
        color = if (highlighted) SicumiColors.Violet else SicumiColors.Lilac,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(icon),
                contentDescription = label,
                tint = if (highlighted) SicumiColors.White else SicumiColors.Ink,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

internal val DangerRed = androidx.compose.ui.graphics.Color(0xFFC62828)
