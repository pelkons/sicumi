package app.sicumi.recording

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import app.sicumi.R
import app.sicumi.meetings.MeetingTitles
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes
import kotlinx.coroutines.delay

/** Экран идущей записи (макет C · Запись). Вызывается, только пока RecorderState.current != null. */
@Composable
fun RecordingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshot by RecorderState.current.collectAsState()
    val s = snapshot ?: return

    // Таймер перерисовывается 4 раза в секунду; источник времени — снимок сервиса.
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(s.paused) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SicumiColors.Violet),
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 150.dp)
                .size(340.dp)
                .clip(SicumiShapes.Blob)
                .background(SicumiColors.Violet2),
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onBack,
                    shape = SicumiShapes.Button,
                    color = SicumiColors.Violet2,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.back),
                            tint = SicumiColors.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                StatusPill(paused = s.paused)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = s.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = SicumiColors.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }

            Spacer(Modifier.height(20.dp))
            // Время и волна — всегда слева направо.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Text(
                        text = MeetingTitles.timer(s.elapsedMs(now)),
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 64.sp, lineHeight = 68.sp),
                        color = if (s.paused) SicumiColors.LilacText else SicumiColors.White,
                    )
                    Waveform(levels = s.levels, paused = s.paused)
                }
            }

            Spacer(Modifier.weight(1f))
            BottomPanel(
                bookmarks = s.bookmarks,
                paused = s.paused,
                onPause = { RecorderService.send(context, if (s.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE) },
                onStop = {
                    RecorderService.send(context, RecorderService.ACTION_STOP)
                    onBack()
                },
                onBookmark = { RecorderService.send(context, RecorderService.ACTION_BOOKMARK) },
            )
        }
    }
}

@Composable
private fun StatusPill(paused: Boolean) {
    Row(
        Modifier
            .height(34.dp)
            .clip(SicumiShapes.Pill)
            .background(SicumiColors.White)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(SicumiShapes.Pill)
                .background(if (paused) SicumiColors.Muted else RecordRed),
        )
        Text(
            text = stringResource(if (paused) R.string.recording_paused else R.string.recording_active),
            style = MaterialTheme.typography.labelLarge,
            color = SicumiColors.Ink,
        )
    }
}

@Composable
private fun Waveform(levels: List<Float>, paused: Boolean) {
    Row(
        Modifier.height(110.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val padded = List(RecorderState.LEVELS - levels.size) { 0f } + levels
        padded.forEachIndexed { i, level ->
            val target = (12 + level * 98).dp
            val h by animateDpAsState(target, label = "bar")
            val fresh = i >= padded.size - 7
            Box(
                Modifier
                    .width(6.dp)
                    .height(h)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            paused -> Color.White.copy(alpha = 0.3f)
                            fresh -> SicumiColors.White
                            else -> Color.White.copy(alpha = 0.55f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun BottomPanel(
    bookmarks: List<Long>,
    paused: Boolean,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onBookmark: () -> Unit,
) {
    val stopLabel = stringResource(R.string.recording_stop_a11y)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
            .background(SicumiColors.Background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.recording_bookmarks_title),
                style = MaterialTheme.typography.labelLarge,
                color = SicumiColors.Muted,
            )
            if (bookmarks.isEmpty()) {
                Text(
                    stringResource(R.string.recording_bookmarks_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = SicumiColors.Muted,
                    modifier = Modifier.height(34.dp),
                )
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    bookmarks.forEach { BookmarkChip(it) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundAction(
                icon = if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                label = stringResource(if (paused) R.string.recording_resume else R.string.recording_pause),
                onClick = onPause,
            )
            Surface(
                onClick = onStop,
                shape = SicumiShapes.Blob,
                color = SicumiColors.Tangerine,
                modifier = Modifier
                    .size(96.dp)
                    .semantics { contentDescription = stopLabel },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_stop),
                        contentDescription = null,
                        tint = SicumiColors.Ink,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            RoundAction(
                icon = R.drawable.ic_star,
                label = stringResource(R.string.recording_bookmark_a11y),
                onClick = onBookmark,
            )
        }

        Text(
            stringResource(R.string.recording_locked_hint),
            style = MaterialTheme.typography.bodySmall,
            color = SicumiColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun BookmarkChip(atMs: Long, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_star_filled),
                contentDescription = null,
                tint = BookmarkStar,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "⁦" + MeetingTitles.duration(atMs) + "⁩",
                style = MaterialTheme.typography.labelLarge,
                color = SicumiColors.Ink,
            )
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = SicumiShapes.Pill, color = SicumiColors.Peach) { content() }
    } else {
        Surface(shape = SicumiShapes.Pill, color = SicumiColors.Peach) { content() }
    }
}

@Composable
private fun RoundAction(icon: Int, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = SicumiColors.Lilac,
        modifier = Modifier
            .size(64.dp)
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = SicumiColors.Ink, modifier = Modifier.size(24.dp))
        }
    }
}

private val RecordRed = Color(0xFFE5484D)
private val BookmarkStar = Color(0xFFC25A14)
