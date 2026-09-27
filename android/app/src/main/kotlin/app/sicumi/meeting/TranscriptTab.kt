package app.sicumi.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.sicumi.R
import app.sicumi.meetings.Meeting
import app.sicumi.meetings.MeetingTitles
import app.sicumi.providers.TranscriptSegment
import app.sicumi.recording.BookmarkChip
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes
import kotlinx.coroutines.delay

/** Цвета дикторов по кругу — чтобы реплики разных людей различались с первого взгляда. */
private val SpeakerColors = listOf(
    SicumiColors.Violet,
    SicumiColors.PeachText,
    SicumiColors.Success,
    Color(0xFF0B6E99),
    Color(0xFF9C2A6B),
    Color(0xFF6B5B00),
)

@Composable
internal fun TranscriptTab(transcript: List<TranscriptSegment>, onSeek: (Long) -> Unit) {
    if (transcript.isEmpty()) {
        Text(stringResource(R.string.transcript_empty), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Muted)
        return
    }
    val speakers = remember(transcript) {
        LinkedHashMap<String, Int>().apply {
            transcript.forEach { s -> s.speaker?.let { getOrPut(it) { size + 1 } } }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.White)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        transcript.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val n = s.speaker?.let { speakers[it] }
                    if (n != null) {
                        Text(
                            stringResource(R.string.speaker_label, n),
                            style = MaterialTheme.typography.labelLarge,
                            color = SpeakerColors[(n - 1) % SpeakerColors.size],
                        )
                    }
                    TimeChip(s.startMs) { onSeek(s.startMs) }
                }
                Text(s.text, style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Ink)
            }
        }
    }
}

@Composable
internal fun RecordingTab(meeting: Meeting, player: SegmentPlayer) {
    val state by player.state.collectAsState()
    LaunchedEffect(state.playing) {
        while (state.playing) {
            player.tick()
            delay(250)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = meeting.durationMs.coerceAtLeast(1)
    val playLabel = stringResource(if (state.playing) R.string.player_pause else R.string.player_play)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.White)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = { player.toggle() },
            shape = SicumiShapes.Blob,
            color = if (state.playing) SicumiColors.Tangerine else SicumiColors.Violet,
            modifier = Modifier
                .size(84.dp)
                .semantics { contentDescription = playLabel },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = null,
                    tint = if (state.playing) SicumiColors.Ink else SicumiColors.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        // Шкала времени медиа — всегда слева направо.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.fillMaxWidth()) {
                Slider(
                    value = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { player.seekTo((it * duration).toLong()) }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = SicumiColors.Violet,
                        activeTrackColor = SicumiColors.Violet,
                        inactiveTrackColor = SicumiColors.Lilac,
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val shown = dragging?.let { (it * duration).toLong() } ?: state.positionMs
                    Text(MeetingTitles.duration(shown), style = MaterialTheme.typography.labelLarge, color = SicumiColors.Ink)
                    Text(MeetingTitles.duration(meeting.durationMs), style = MaterialTheme.typography.labelLarge, color = SicumiColors.Muted)
                }
            }
        }
    }
    if (meeting.bookmarks.isNotEmpty()) {
        SectionCard(stringResource(R.string.recording_bookmarks_title)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                meeting.bookmarks.forEach { at ->
                    BookmarkChip(at) {
                        player.seekTo(at)
                        player.play()
                    }
                }
            }
        }
    }
}
