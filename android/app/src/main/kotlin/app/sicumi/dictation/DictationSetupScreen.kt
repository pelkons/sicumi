package app.sicumi.dictation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sicumi.R
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes
import kotlinx.coroutines.delay

@Composable
fun DictationSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(false) }
    var serviceOn by remember { mutableStateOf(false) }

    // Статус меняется в системных настройках — просто перепроверяем раз в секунду.
    LaunchedEffect(Unit) {
        while (true) {
            micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            serviceOn = DictationService.isEnabled(context)
            delay(1000)
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(onClick = onBack, shape = SicumiShapes.Button, color = SicumiColors.Lilac, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.back),
                    tint = SicumiColors.Ink,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(stringResource(R.string.dictation_title), style = MaterialTheme.typography.headlineMedium, color = SicumiColors.Ink)
        Text(stringResource(R.string.dictation_body), style = MaterialTheme.typography.bodyLarge, color = SicumiColors.Ink)

        StepCard(
            title = stringResource(R.string.step_mic_title),
            done = micGranted,
            actionLabel = stringResource(R.string.action_allow),
            onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        StepCard(
            title = stringResource(R.string.step_access_title),
            done = serviceOn,
            actionLabel = stringResource(R.string.action_open_settings),
            onAction = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )

        Text(stringResource(R.string.dictation_access_note), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Muted)
        if (micGranted && serviceOn) {
            Text(stringResource(R.string.dictation_try), style = MaterialTheme.typography.titleMedium, color = SicumiColors.Violet)
            // Только в debug-сборке: быстрый переход в чужое приложение с полем ввода для проверки кнопки.
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                Surface(
                    onClick = {
                        val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(sms)
                        } catch (e: ActivityNotFoundException) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    shape = SicumiShapes.Pill,
                    color = SicumiColors.Tangerine,
                ) {
                    Text(
                        stringResource(R.string.debug_open_other_app),
                        style = MaterialTheme.typography.labelLarge,
                        color = SicumiColors.Ink,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCard(title: String, done: Boolean, actionLabel: String, onAction: () -> Unit) {
    Surface(shape = SicumiShapes.Card, color = SicumiColors.White, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (done) SicumiColors.SuccessBg else SicumiColors.Lilac, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = SicumiColors.Success,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = SicumiColors.Ink, modifier = Modifier.weight(1f))
            if (done) {
                Text(stringResource(R.string.status_on), style = MaterialTheme.typography.labelLarge, color = SicumiColors.Success)
            } else {
                Surface(onClick = onAction, shape = SicumiShapes.Pill, color = SicumiColors.Violet) {
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = SicumiColors.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
