package app.sicumi.dictation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sicumi.R
import app.sicumi.providers.ApiKeyStore
import app.sicumi.providers.ApiPurpose
import app.sicumi.providers.ApiSelection
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes
import kotlinx.coroutines.delay

private enum class SetupStep { Mic, Access, Key, Try }

/**
 * Мастер включения диктовки: по одному шагу на экран, с картинками системных экранов,
 * где подсвечено, куда нажимать. Шаг засчитывается сам, когда система сообщает, что всё включено.
 */
@Composable
fun DictationSetupScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val serviceName = stringResource(R.string.dictation_service_label)
    val labels = remember(serviceName) { SystemSettings.labels(serviceName) }
    val likelyRestricted = remember { SystemSettings.likelyRestricted(context) }
    val providerName = remember { "⁨${ApiSelection(context).selected(ApiPurpose.Dictation).name}⁩" }

    var micGranted by remember { mutableStateOf(context.micGranted()) }
    var serviceOn by remember { mutableStateOf(DictationService.isEnabled(context)) }
    var hasKey by remember { mutableStateOf(context.hasDictationKey()) }

    // Разрешения меняются в системных настройках, поэтому состояние просто перепроверяется.
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            micGranted = context.micGranted()
            serviceOn = DictationService.isEnabled(context)
            hasKey = context.hasDictationKey()
        }
    }

    fun isDone(s: SetupStep) = when (s) {
        SetupStep.Mic -> micGranted
        SetupStep.Access -> serviceOn
        SetupStep.Key -> hasKey
        SetupStep.Try -> false
    }

    var step by remember { mutableStateOf(SetupStep.entries.firstOrNull { !isDone(it) } ?: SetupStep.Try) }
    fun next() {
        step = SetupStep.entries[minOf(step.ordinal + 1, SetupStep.entries.lastIndex)]
    }

    // Автопереход, только если шаг выполнен, пока пользователь на нём.
    val stepDone = isDone(step)
    val doneOnEntry = remember(step) { stepDone }
    LaunchedEffect(step, stepDone) {
        if (stepDone && !doneOnEntry) {
            delay(900)
            next()
        }
    }

    var micBlocked by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        val activity = context.findActivity()
        if (!granted && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            micBlocked = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
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
            StepProgress(current = step.ordinal, count = SetupStep.entries.size, currentDone = stepDone, modifier = Modifier.weight(1f))
        }

        Crossfade(targetState = step, modifier = Modifier.weight(1f), label = "dictation-setup") { s ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (s) {
                    SetupStep.Mic -> MicStep(labels, done = micGranted, blocked = micBlocked)
                    SetupStep.Access -> AccessStep(labels, serviceName, done = serviceOn, likelyRestricted = likelyRestricted)
                    SetupStep.Key -> KeyStep(providerName, done = hasKey)
                    SetupStep.Try -> TryStep(
                        missing = SetupStep.entries.filter { it != SetupStep.Try && !isDone(it) },
                        onGoTo = { step = it },
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                step == SetupStep.Try -> PrimaryButton(stringResource(R.string.setup_done), onBack)
                stepDone -> PrimaryButton(stringResource(R.string.setup_next), ::next)
                step == SetupStep.Mic && micBlocked -> PrimaryButton(stringResource(R.string.setup_mic_open_app)) {
                    SystemSettings.openAppDetails(context)
                }
                step == SetupStep.Mic -> PrimaryButton(stringResource(R.string.setup_mic_action)) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                step == SetupStep.Access -> PrimaryButton(stringResource(R.string.setup_access_action)) {
                    SystemSettings.openAccessibility(context)
                }
                step == SetupStep.Key -> {
                    PrimaryButton(stringResource(R.string.setup_key_action), onOpenSettings)
                    TextAction(stringResource(R.string.setup_skip), ::next)
                }
            }
        }
    }
}

@Composable
private fun MicStep(labels: SystemLabels, done: Boolean, blocked: Boolean) {
    StepHeader(stringResource(R.string.setup_mic_title), stringResource(R.string.setup_mic_body))
    when {
        done -> DoneBadge(stringResource(R.string.setup_mic_done))
        blocked -> Note(stringResource(R.string.setup_mic_blocked))
        else -> InstructionStep(1, stringResource(R.string.setup_mic_instruction)) {
            MockFrame(labels.rtl) {
                MockPermissionDialog(
                    title = labels.micTitle,
                    options = listOf(labels.micWhileUsing, labels.micOnlyThisTime, labels.micDeny),
                    highlighted = 0,
                )
            }
        }
    }
}

@Composable
private fun AccessStep(labels: SystemLabels, serviceName: String, done: Boolean, likelyRestricted: Boolean) {
    StepHeader(stringResource(R.string.setup_access_title), stringResource(R.string.setup_access_body))
    if (done) {
        DoneBadge(stringResource(R.string.setup_access_done))
        return
    }
    InstructionStep(1, stringResource(R.string.setup_access_1)) {
        MockFrame(labels.rtl) { MockRow(labels.section, highlighted = true) }
    }
    InstructionStep(2, stringResource(R.string.setup_access_2, "⁨$serviceName⁩")) {
        MockFrame(labels.rtl) {
            MockHeader(serviceName)
            MockRow(labels.toggle) { MockSwitch(on = true, highlighted = true) }
        }
    }
    InstructionStep(3, stringResource(R.string.setup_access_3)) {
        MockFrame(labels.rtl) { MockConfirmDialog(labels.fullControlTitle, labels.deny, labels.allow) }
    }
    InstructionStep(4, stringResource(R.string.setup_access_4)) {
        MockFrame(labels.rtl) { MockRow(labels.shortcut) { MockSwitchOffMarked() } }
    }
    Note(stringResource(R.string.dictation_access_note))

    if (SystemSettings.canBeRestricted) {
        var expanded by remember { mutableStateOf(likelyRestricted) }
        if (!expanded) {
            TextAction(stringResource(R.string.setup_restricted_link)) { expanded = true }
        } else {
            RestrictedHelp(labels)
        }
    }
}

@Composable
private fun RestrictedHelp(labels: SystemLabels) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.Peach)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.setup_restricted_title), style = MaterialTheme.typography.titleLarge, color = SicumiColors.Ink)
        Text(stringResource(R.string.setup_restricted_body), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Ink)
        InstructionStep(1, stringResource(R.string.setup_restricted_1)) {
            SmallButton(stringResource(R.string.setup_restricted_action)) { SystemSettings.openAppDetails(context) }
        }
        InstructionStep(2, stringResource(R.string.setup_restricted_2)) {
            MockFrame(labels.rtl) { MockHeader(labels.appInfo, menuHighlighted = true, showMenu = true) }
        }
        InstructionStep(3, stringResource(R.string.setup_restricted_3)) {
            MockFrame(labels.rtl) { MockRow(labels.restrictedMenu, highlighted = true) }
        }
        InstructionStep(4, stringResource(R.string.setup_restricted_4))
    }
}

@Composable
private fun KeyStep(providerName: String, done: Boolean) {
    StepHeader(stringResource(R.string.setup_key_title), stringResource(R.string.setup_key_body, providerName))
    if (done) {
        DoneBadge(stringResource(R.string.setup_key_ready, providerName))
    } else {
        Note(stringResource(R.string.setup_key_missing, providerName))
    }
}

@Composable
private fun TryStep(missing: List<SetupStep>, onGoTo: (SetupStep) -> Unit) {
    var text by remember { mutableStateOf("") }
    StepHeader(stringResource(R.string.setup_try_title), stringResource(R.string.setup_try_body))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.setup_try_hint)) },
        minLines = 4,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = SicumiShapes.Button,
        modifier = Modifier.fillMaxWidth(),
    )
    if (missing.isNotEmpty()) {
        Note(stringResource(R.string.setup_try_missing))
        missing.forEach { s ->
            val title = when (s) {
                SetupStep.Mic -> R.string.step_mic_title
                SetupStep.Access -> R.string.step_access_title
                SetupStep.Key -> R.string.setup_key_title
                SetupStep.Try -> return@forEach
            }
            SmallButton(stringResource(title)) { onGoTo(s) }
        }
    }
}

@Composable
private fun StepHeader(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = SicumiColors.Ink)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = SicumiColors.Muted)
    }
}

@Composable
private fun StepProgress(current: Int, count: Int, currentDone: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val fill = when {
                i < current -> 1f
                i == current -> if (currentDone) 1f else 0.4f
                else -> 0f
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(SicumiShapes.Pill)
                    .background(SicumiColors.Lilac),
            ) {
                if (fill > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fill)
                            .fillMaxHeight()
                            .background(SicumiColors.Violet, SicumiShapes.Pill),
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneBadge(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SicumiShapes.Card)
            .background(SicumiColors.SuccessBg)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(SicumiColors.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = SicumiColors.Success,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = SicumiColors.Success)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Muted)
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = SicumiShapes.Pill,
        color = SicumiColors.Violet,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = SicumiColors.White)
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = SicumiShapes.Pill, color = SicumiColors.Violet) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = SicumiColors.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = SicumiShapes.Pill, color = SicumiColors.Background) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = SicumiColors.Violet,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

private fun Context.micGranted(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Для диктовки нужны и ключ, и модель, которую выбрал пользователь. */
private fun Context.hasDictationKey(): Boolean {
    val selection = ApiSelection(this)
    val provider = selection.selected(ApiPurpose.Dictation)
    return ApiKeyStore(this).has(ApiSelection.keyId(ApiPurpose.Dictation, provider)) &&
        selection.model(ApiPurpose.Dictation, provider) != null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
