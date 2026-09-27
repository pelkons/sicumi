package app.sicumi.ui.home

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import app.sicumi.R
import app.sicumi.meetings.Meeting
import app.sicumi.meetings.MeetingErrors
import app.sicumi.meetings.MeetingSource
import app.sicumi.meetings.MeetingStatus
import app.sicumi.meetings.MeetingTitles
import app.sicumi.pipeline.MeetingProcessor
import app.sicumi.recording.RecordingSnapshot
import kotlinx.coroutines.delay
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes
import app.sicumi.ui.theme.SicumiTheme

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    meetings: List<Meeting> = emptyList(),
    recording: RecordingSnapshot? = null,
    importing: Boolean = false,
    onStartRecording: () -> Unit = {},
    onOpenRecording: () -> Unit = {},
    onImport: () -> Unit = {},
    onOpenMeeting: (String) -> Unit = {},
    onOpenDictation: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val progress by MeetingProcessor.progress.collectAsState()

    val visible = meetings
        .filter { it.status != MeetingStatus.Recording }
        .filter {
            when (filter) {
                1 -> it.status == MeetingStatus.Ready
                2 -> it.status.isProcessing || it.status == MeetingStatus.Failed
                else -> true
            }
        }
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HomeHeader(searching = searching, onToggleSearch = {
                searching = !searching
                if (!searching) query = ""
            })
            if (searching) SearchField(query) { query = it }
            if (recording != null) {
                RecordingHero(recording, onOpenRecording)
            } else {
                RecordHero(onRecord = onStartRecording, onImport = onImport, importing = importing)
            }
            FilterRow(selected = filter, onSelect = { filter = it })
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    meetings.none { it.status != MeetingStatus.Recording } -> EmptyState()
                    visible.isEmpty() -> Text(
                        stringResource(R.string.home_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SicumiColors.Muted,
                    )
                    else -> visible.forEach { m ->
                        MeetingCard(m, progress[m.id]) { onOpenMeeting(m.id) }
                    }
                }
            }
        }

        BottomBar(
            onOpenDictation = onOpenDictation,
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        singleLine = true,
        shape = SicumiShapes.Button,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SicumiColors.Violet,
            unfocusedBorderColor = SicumiColors.Lilac,
            focusedContainerColor = SicumiColors.White,
            unfocusedContainerColor = SicumiColors.White,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.White)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(SicumiShapes.Blob)
                .background(SicumiColors.Lilac),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_meetings), null, tint = SicumiColors.Violet, modifier = Modifier.size(26.dp))
        }
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium, color = SicumiColors.Ink)
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = SicumiColors.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingHero(recording: RecordingSnapshot, onOpen: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    Surface(onClick = onOpen, shape = SicumiShapes.Block, color = SicumiColors.Violet, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (recording.paused) R.string.recording_paused else R.string.home_recording_now),
                    style = MaterialTheme.typography.titleLarge,
                    color = SicumiColors.White,
                )
                Text(
                    "\u2066" + MeetingTitles.timer(recording.elapsedMs(now)) + "\u2069 · " + stringResource(R.string.home_back_to_recording),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SicumiColors.LilacText,
                )
            }
            Box(
                Modifier
                    .size(84.dp)
                    .clip(SicumiShapes.Blob)
                    .background(SicumiColors.Tangerine),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_wave), null, tint = SicumiColors.Ink, modifier = Modifier.size(34.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader(searching: Boolean, onToggleSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            onClick = onToggleSearch,
            shape = SicumiShapes.Button,
            color = if (searching) SicumiColors.Violet else SicumiColors.Lilac,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(if (searching) R.drawable.ic_close else R.drawable.ic_search),
                    contentDescription = stringResource(R.string.home_search),
                    tint = if (searching) SicumiColors.White else SicumiColors.Ink,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordHero(onRecord: () -> Unit, onImport: () -> Unit, importing: Boolean) {
    val startLabel = stringResource(R.string.home_record_start)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Block)
            .background(SicumiColors.Violet),
    ) {
        // Декоративная «капля» на фоне. В RTL End — это левый край.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 40.dp, y = 60.dp)
                .size(180.dp)
                .clip(SicumiShapes.Blob)
                .background(SicumiColors.Violet2),
        )
        Column(
            Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.home_record_new),
                        style = MaterialTheme.typography.titleLarge,
                        color = SicumiColors.White,
                    )
                    Text(
                        text = stringResource(R.string.home_record_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SicumiColors.LilacText,
                    )
                }
                Surface(
                    onClick = onRecord,
                    shape = SicumiShapes.Blob,
                    color = SicumiColors.Tangerine,
                    modifier = Modifier
                        .size(84.dp)
                        .semantics { contentDescription = startLabel },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = null,
                            tint = SicumiColors.Ink,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
            Surface(
                onClick = onImport,
                enabled = !importing,
                shape = SicumiShapes.Pill,
                color = SicumiColors.White,
            ) {
                Row(
                    Modifier
                        .height(44.dp)
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_upload),
                        contentDescription = null,
                        tint = SicumiColors.Ink,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(if (importing) R.string.importing else R.string.home_import_audio),
                        style = MaterialTheme.typography.labelLarge,
                        color = SicumiColors.Ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        stringResource(R.string.filter_all),
        stringResource(R.string.filter_ready),
        stringResource(R.string.filter_processing),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            Surface(
                onClick = { onSelect(index) },
                shape = SicumiShapes.Pill,
                color = if (isSelected) SicumiColors.Ink else SicumiColors.Lilac,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) SicumiColors.White else SicumiColors.Ink,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun MeetingCard(meeting: Meeting, progress: Float?, onClick: () -> Unit) {
    val processing = meeting.status.isProcessing
    val failed = meeting.status == MeetingStatus.Failed
    Surface(
        onClick = onClick,
        shape = SicumiShapes.Card,
        color = SicumiColors.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(if (processing) CircleShape else RoundedCornerShape(18.dp))
                    .background(if (processing || failed) SicumiColors.Peach else SicumiColors.SuccessBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        when {
                            processing -> R.drawable.ic_wave
                            failed -> R.drawable.ic_retry
                            else -> R.drawable.ic_check
                        },
                    ),
                    contentDescription = stringResource(MeetingErrors.statusLabel(meeting.status)),
                    tint = if (processing || failed) SicumiColors.PeachText else SicumiColors.Success,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (processing) 8.dp else 3.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = meeting.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = SicumiColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (processing || failed) {
                        Text(
                            text = stringResource(MeetingErrors.statusLabel(meeting.status)),
                            style = MaterialTheme.typography.labelSmall,
                            color = SicumiColors.PeachText,
                        )
                    }
                }
                if (processing) {
                    ProgressBar(progress ?: 0.12f)
                } else {
                    val minutes = ((meeting.durationMs + 30_000) / 60_000).toInt()
                    Text(
                        text = "\u2066" + MeetingTitles.dateTime(meeting.createdAt) + "\u2069" +
                            if (minutes > 0) " · " + stringResource(R.string.meeting_minutes, minutes) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = SicumiColors.Muted,
                    )
                }
            }
        }
    }
}

/** Полоса прогресса обработки. В RTL заполняется справа налево — это правильно (не медиа). */
@Composable
private fun ProgressBar(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(SicumiShapes.Pill)
            .background(SicumiColors.Background),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(SicumiShapes.Pill)
                .background(SicumiColors.Tangerine),
        )
    }
}

@Composable
private fun BottomBar(onOpenDictation: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = SicumiColors.White,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItemSelected(R.drawable.ic_meetings, stringResource(R.string.nav_meetings))
            NavItem(R.drawable.ic_dictation, stringResource(R.string.nav_dictation), onClick = onOpenDictation)
            NavItem(R.drawable.ic_settings, stringResource(R.string.nav_settings), onClick = onOpenSettings)
        }
    }
}

@Composable
private fun NavItemSelected(@DrawableRes icon: Int, label: String) {
    Surface(onClick = {}, shape = SicumiShapes.Pill, color = SicumiColors.Violet) {
        Row(
            Modifier
                .height(50.dp)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = SicumiColors.White,
                modifier = Modifier.size(22.dp),
            )
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = SicumiColors.White)
        }
    }
}

@Composable
private fun NavItem(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(50.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = SicumiColors.Muted,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, locale = "he")
@Composable
private fun HomeScreenPreview() {
    val now = System.currentTimeMillis()
    SicumiTheme {
        HomeScreen(
            meetings = listOf(
                Meeting("1", "ישיבת צוות שבועית", now, 42 * 60_000L, MeetingSource.Recorded, MeetingStatus.Ready, emptyList(), emptyList()),
                Meeting("2", "פגישה עם הקבלן", now - 86_400_000L, 75 * 60_000L, MeetingSource.Imported, MeetingStatus.Transcribing, emptyList(), emptyList()),
            ),
        )
    }
}
