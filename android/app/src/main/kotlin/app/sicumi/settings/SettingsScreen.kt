package app.sicumi.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.sicumi.R
import app.sicumi.providers.AiProvider
import app.sicumi.providers.AiProviders
import app.sicumi.providers.ApiPurpose
import app.sicumi.providers.ApiSelection
import app.sicumi.providers.ApiKeyStore
import app.sicumi.providers.KeyCheck
import app.sicumi.providers.KeyTester
import app.sicumi.providers.ModelCatalog
import app.sicumi.providers.ModelKind
import app.sicumi.providers.ProviderRole
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class KeyStatus { None, Saved, Testing, Ok, Invalid, Error }

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ApiKeyStore(context.applicationContext) }
    val selection = remember { ApiSelection(context.applicationContext) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge, color = SicumiColors.Ink)
        Text(stringResource(R.string.providers_section), style = MaterialTheme.typography.headlineMedium, color = SicumiColors.Ink)
        Text(stringResource(R.string.providers_intro), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Muted)
        ApiPurpose.entries.forEach { PurposeCard(it, store, selection) }
    }
}

@Composable
private fun PurposeCard(purpose: ApiPurpose, store: ApiKeyStore, selection: ApiSelection) {
    var selected by remember { mutableStateOf(selection.selected(purpose)) }
    val options = AiProviders.forPurpose(purpose)

    Surface(shape = SicumiShapes.Card, color = SicumiColors.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(purpose.title), style = MaterialTheme.typography.titleLarge, color = SicumiColors.Ink)
            Text(stringResource(purpose.description), style = MaterialTheme.typography.bodySmall, color = SicumiColors.Muted)

            // Выбор провайдера: сетка по два в ряд.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { provider ->
                            val isSelected = provider.id == selected.id
                            Surface(
                                onClick = {
                                    selected = provider
                                    selection.select(purpose, provider)
                                },
                                shape = SicumiShapes.Button,
                                color = if (isSelected) SicumiColors.Violet else SicumiColors.Background,
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        provider.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) SicumiColors.White else SicumiColors.Ink,
                                    )
                                    if (store.has(ApiSelection.keyId(purpose, provider))) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = stringResource(R.string.key_status_saved),
                                            tint = if (isSelected) SicumiColors.White else SicumiColors.Success,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (rowItems.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }

            key(purpose.id, selected.id) {
                KeyEditor(purpose, selected, store)
                ModelPicker(purpose, selected, store, selection, cleanup = false)
                // Очистка диктовки — отдельный платный запрос; только у провайдеров с текстовыми моделями.
                // Gemini не нуждается в ней: сам отдаёт чистый текст.
                if (purpose == ApiPurpose.Dictation && ProviderRole.Llm in selected.roles && selected.id != "gemini") {
                    ModelPicker(purpose, selected, store, selection, cleanup = true)
                }
            }
        }
    }
}

@Composable
private fun KeyEditor(purpose: ApiPurpose, provider: AiProvider, store: ApiKeyStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyId = ApiSelection.keyId(purpose, provider)
    var input by rememberSaveable(keyId) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(store.has(keyId)) }
    var status by remember { mutableStateOf(if (saved) KeyStatus.Saved else KeyStatus.None) }

    fun runCheck(key: String) {
        status = KeyStatus.Testing
        scope.launch {
            status = when (KeyTester.check(provider, key)) {
                KeyCheck.Ok -> KeyStatus.Ok
                KeyCheck.Invalid -> KeyStatus.Invalid
                KeyCheck.Error -> KeyStatus.Error
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: ActivityNotFoundException) {
                        // Нет браузера — ничего не делаем.
                    }
                },
                shape = SicumiShapes.Pill,
                color = SicumiColors.Lilac,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.key_get_for, provider.name),
                        style = MaterialTheme.typography.labelLarge,
                        color = SicumiColors.Violet,
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_external),
                        contentDescription = null,
                        tint = SicumiColors.Violet,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            StatusChip(status)
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it.trim() },
            label = { Text(stringResource(if (saved) R.string.key_label_replace else R.string.key_label)) },
            singleLine = true,
            // Ключ — латиница: вводится слева направо внутри RTL-интерфейса.
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Text(
                    stringResource(if (visible) R.string.key_hide else R.string.key_show),
                    style = MaterialTheme.typography.labelLarge,
                    color = SicumiColors.Violet,
                    modifier = Modifier
                        .clickable { visible = !visible }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SicumiColors.Violet,
                focusedLabelColor = SicumiColors.Violet,
                cursorColor = SicumiColors.Violet,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = stringResource(R.string.key_save),
                filled = true,
                enabled = input.isNotBlank(),
                onClick = {
                    val k = input
                    store.save(keyId, k)
                    saved = true
                    input = ""
                    visible = false
                    runCheck(k)
                },
            )
            ActionButton(
                text = stringResource(R.string.key_test),
                filled = false,
                enabled = saved && status != KeyStatus.Testing,
                onClick = { store.get(keyId)?.let { runCheck(it) } },
            )
            if (saved) {
                ActionButton(
                    text = stringResource(R.string.key_delete),
                    filled = false,
                    enabled = true,
                    onClick = {
                        store.remove(keyId)
                        saved = false
                        status = KeyStatus.None
                    },
                )
            }
        }
    }
}

private sealed interface ModelList {
    data object Idle : ModelList
    data object Loading : ModelList
    data object NeedKey : ModelList
    data object Failed : ModelList
    data class Loaded(val ids: List<String>) : ModelList
}

/**
 * Выбор модели для назначения. Список приходит от провайдера по ключу пользователя;
 * если нужной модели в нём нет, её ID можно ввести вручную.
 * cleanup = true — модель очистки диктовки, с вариантом «без очистки» (по умолчанию).
 */
@Composable
private fun ModelPicker(
    purpose: ApiPurpose,
    provider: AiProvider,
    store: ApiKeyStore,
    selection: ApiSelection,
    cleanup: Boolean,
) {
    val scope = rememberCoroutineScope()
    val kind = if (cleanup || purpose.role == ProviderRole.Llm) ModelKind.Llm else ModelKind.Stt
    var current by remember {
        mutableStateOf(if (cleanup) selection.cleanupModel(provider) else selection.model(purpose, provider))
    }
    var expanded by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<ModelList>(ModelList.Idle) }
    var manual by rememberSaveable(purpose.id, provider.id, cleanup) { mutableStateOf("") }
    val providerName = "\u2068${provider.name}\u2069"

    fun choose(model: String?) {
        if (cleanup) selection.setCleanupModel(provider, model) else selection.setModel(purpose, provider, model)
        current = model
        expanded = false
    }

    fun load() {
        val key = store.get(ApiSelection.keyId(purpose, provider))
        if (key == null) {
            list = ModelList.NeedKey
            return
        }
        list = ModelList.Loading
        scope.launch {
            list = try {
                ModelList.Loaded(ModelCatalog.list(provider, key, kind))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelList.Failed
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (cleanup) R.string.cleanup_label else R.string.model_label),
                style = MaterialTheme.typography.titleMedium,
                color = SicumiColors.Ink,
                modifier = Modifier.weight(1f),
            )
            val label = current ?: stringResource(if (cleanup) R.string.cleanup_none else R.string.model_none)
            if (current == null && !cleanup) {
                Chip(label, SicumiColors.Peach, SicumiColors.PeachText)
            } else {
                Chip(label, SicumiColors.Lilac, SicumiColors.Ink)
            }
        }
        if (cleanup) {
            Text(stringResource(R.string.cleanup_hint), style = MaterialTheme.typography.bodySmall, color = SicumiColors.Muted)
        }

        if (!expanded) {
            ActionButton(
                text = stringResource(if (current == null) R.string.model_choose else R.string.model_change),
                filled = current == null && !cleanup,
                enabled = true,
                onClick = {
                    expanded = true
                    load()
                },
            )
        } else {
            Text(stringResource(R.string.model_hint, providerName), style = MaterialTheme.typography.bodySmall, color = SicumiColors.Muted)
            when (val state = list) {
                ModelList.Idle, ModelList.Loading ->
                    Text(stringResource(R.string.model_loading), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Muted)
                ModelList.NeedKey ->
                    Text(stringResource(R.string.model_need_key), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.PeachText)
                ModelList.Failed -> {
                    Text(stringResource(R.string.model_load_error, providerName), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.PeachText)
                    ActionButton(stringResource(R.string.model_retry), filled = false, enabled = true, onClick = ::load)
                }
                is ModelList.Loaded -> {
                    if (cleanup) ModelRow(stringResource(R.string.cleanup_none), selected = current == null, latin = false) { choose(null) }
                    if (state.ids.isEmpty()) {
                        Text(stringResource(R.string.model_empty, providerName), style = MaterialTheme.typography.bodyMedium, color = SicumiColors.Muted)
                    }
                    state.ids.forEach { id -> ModelRow(id, selected = id == current, latin = true) { choose(id) } }
                }
            }

            // Ручной ввод — если нужной модели нет в списке провайдера.
            if (list !is ModelList.Loading && list !is ModelList.NeedKey) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it.trim() },
                    label = { Text(stringResource(R.string.model_manual_label)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SicumiColors.Violet,
                        focusedLabelColor = SicumiColors.Violet,
                        cursorColor = SicumiColors.Violet,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (manual.isNotBlank()) {
                    ActionButton(stringResource(R.string.model_manual_save), filled = true, enabled = true) {
                        choose(manual)
                        manual = ""
                    }
                }
                ActionButton(stringResource(R.string.model_close), filled = false, enabled = true) { expanded = false }
            }
        }
    }
}

@Composable
private fun ModelRow(label: String, selected: Boolean, latin: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = SicumiShapes.Button,
        color = if (selected) SicumiColors.Lilac else SicumiColors.Background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                // ID модели — латиница: читается слева направо внутри RTL-экрана.
                style = if (latin) {
                    MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = SicumiColors.Ink,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = SicumiColors.Violet,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: KeyStatus) {
    val (label, bg, fg) = when (status) {
        KeyStatus.None -> Triple(R.string.key_status_none, SicumiColors.Background, SicumiColors.Muted)
        KeyStatus.Saved -> Triple(R.string.key_status_saved, SicumiColors.Lilac, SicumiColors.Ink)
        KeyStatus.Testing -> Triple(R.string.key_status_testing, SicumiColors.Lilac, SicumiColors.Ink)
        KeyStatus.Ok -> Triple(R.string.key_status_ok, SicumiColors.SuccessBg, SicumiColors.Success)
        KeyStatus.Invalid -> Triple(R.string.key_status_invalid, SicumiColors.Peach, SicumiColors.PeachText)
        KeyStatus.Error -> Triple(R.string.key_status_error, SicumiColors.Peach, SicumiColors.PeachText)
    }
    Chip(stringResource(label), bg, fg)
}

@Composable
private fun Chip(text: String, background: Color, content: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .background(background, SicumiShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun ActionButton(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = when {
        !enabled -> SicumiColors.Background
        filled -> SicumiColors.Violet
        else -> SicumiColors.Lilac
    }
    val fg = when {
        !enabled -> SicumiColors.Muted
        filled -> SicumiColors.White
        else -> SicumiColors.Ink
    }
    Surface(onClick = onClick, enabled = enabled, shape = SicumiShapes.Pill, color = bg) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
