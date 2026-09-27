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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.sicumi.R
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes
import app.sicumi.ui.theme.SicumiTheme

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenDictation: () -> Unit = {},
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }

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
            HomeHeader()
            RecordHero(onRecord = {}, onImport = {})
            FilterRow(selected = filter, onSelect = { filter = it })
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DemoMeetings.forEach { MeetingCard(it) }
            }
        }

        BottomBar(
            onOpenDictation = onOpenDictation,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun HomeHeader() {
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
            onClick = {},
            shape = SicumiShapes.Button,
            color = SicumiColors.Lilac,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = stringResource(R.string.home_search),
                    tint = SicumiColors.Ink,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordHero(onRecord: () -> Unit, onImport: () -> Unit) {
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
                        text = stringResource(R.string.home_import_audio),
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
private fun MeetingCard(meeting: MeetingUi) {
    val processing = meeting.progress != null
    Surface(
        onClick = {},
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
                    .background(if (processing) SicumiColors.Peach else SicumiColors.SuccessBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (processing) R.drawable.ic_wave else R.drawable.ic_check),
                    contentDescription = stringResource(
                        if (processing) R.string.status_transcribing else R.string.status_ready,
                    ),
                    tint = if (processing) SicumiColors.PeachText else SicumiColors.Success,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (processing) 8.dp else 3.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = meeting.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = SicumiColors.Ink,
                    )
                    if (processing) {
                        Text(
                            text = stringResource(R.string.status_transcribing),
                            style = MaterialTheme.typography.labelSmall,
                            color = SicumiColors.PeachText,
                        )
                    }
                }
                val progress = meeting.progress
                if (progress != null) {
                    ProgressBar(progress)
                } else {
                    Text(
                        text = meeting.meta,
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
private fun BottomBar(onOpenDictation: () -> Unit, modifier: Modifier = Modifier) {
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
            NavItem(R.drawable.ic_settings, stringResource(R.string.nav_settings), onClick = {})
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
    SicumiTheme { HomeScreen() }
}
