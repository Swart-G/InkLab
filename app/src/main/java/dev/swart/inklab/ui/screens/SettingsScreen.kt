package dev.swart.inklab.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.AppContainer
import dev.swart.inklab.core.recognition.ProviderState
import dev.swart.inklab.core.recognition.RecognitionMode
import dev.swart.inklab.core.storage.EraserMode
import dev.swart.inklab.core.storage.FingerAction
import dev.swart.inklab.core.storage.StylusButtonAction
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors

@Composable
fun SettingsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(InkColors.Paper).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Назад") }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Ввод и локальное распознавание", color = InkColors.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Ввод") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("OCR и модели") })
            }
        }
        Spacer(Modifier.height(20.dp))
        if (tab == 0) InputSettings(vm) else RecognitionSettings(vm)
    }
}

@Composable
private fun InputSettings(vm: EditorViewModel) {
    val preferences = vm.inputPreferences
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SettingsCard("Палец", "Жест пальцем не влияет на поведение S Pen") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FingerAction.entries.forEach { action ->
                        FilterChip(
                            selected = preferences.fingerAction == action,
                            onClick = { vm.updateInputPreferences(preferences.copy(fingerAction = action)) },
                            label = { Text(action.label()) },
                            leadingIcon = if (preferences.fingerAction == action) {{ Icon(Icons.Outlined.TouchApp, null, Modifier.size(16.dp)) }} else null
                        )
                    }
                }
            }
        }
        item {
            SettingsCard("Стилус", "Кончик рисует выбранным инструментом; кнопка может временно менять его") {
                Text("Кнопка S Pen", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StylusButtonAction.entries.forEach { action ->
                        FilterChip(
                            selected = preferences.stylusButtonAction == action,
                            onClick = { vm.updateInputPreferences(preferences.copy(stylusButtonAction = action)) },
                            label = { Text(action.label()) }
                        )
                    }
                }
                SettingSwitch(
                    "Чувствительность к нажатию",
                    "Толщина линии меняется по pressure",
                    preferences.pressureEnabled
                ) { vm.updateInputPreferences(preferences.copy(pressureEnabled = it)) }
                SettingSwitch(
                    "Защита от ладони",
                    "Касания игнорируются, пока стилус пишет",
                    preferences.palmRejection
                ) { vm.updateInputPreferences(preferences.copy(palmRejection = it)) }
            }
        }
        item {
            SettingsCard("Ластик", "Пиксельный режим разрезает линию, штриховой удаляет её целиком") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EraserMode.entries.forEach { mode ->
                        FilterChip(
                            selected = preferences.eraserMode == mode,
                            onClick = { vm.updateInputPreferences(preferences.copy(eraserMode = mode)) },
                            label = { Text(if (mode == EraserMode.PIXEL) "Пиксельный" else "Весь штрих") }
                        )
                    }
                }
                Text("Размер · ${preferences.eraserRadius.toInt()} dp", color = InkColors.Muted)
                Slider(
                    value = preferences.eraserRadius,
                    onValueChange = { vm.updateInputPreferences(preferences.copy(eraserRadius = it)) },
                    valueRange = 8f..54f,
                    steps = 22
                )
            }
        }
    }
}

@Composable
private fun RecognitionSettings(vm: EditorViewModel) {
    val context = LocalContext.current
    val providers = AppContainer.recognitionRegistry.all()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Движки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProviderPicker("Рукописный текст", RecognitionMode.TEXT, vm.textProviderId, { vm.textProviderId = it }, Modifier.weight(1f))
                ProviderPicker("Формулы", RecognitionMode.MATH, vm.mathProviderId, { vm.mathProviderId = it }, Modifier.weight(1f))
            }
        }
        item { Text("Локальные пакеты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) }
        items(providers, key = { it.id }) { provider ->
            val state = provider.state(context)
            val progress = vm.modelProgress[provider.id]
            SettingsCard(provider.displayName, provider.subtitle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = { Text(state.label()) },
                        leadingIcon = if (state == ProviderState.READY) {{ Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp)) }} else null
                    )
                    provider.downloadSizeBytes?.let { size ->
                        Text("  ·  ${formatBytes(size)}", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    provider.licenseLabel?.let { license ->
                        Text("  ·  $license", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    when {
                        progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
                        state == ProviderState.READY && provider.downloadSizeBytes != null -> IconButton(onClick = { vm.removeProvider(context, provider.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, "Удалить пакет")
                        }
                        state == ProviderState.MODEL_REQUIRED -> IconButton(onClick = { vm.prepareProvider(context, provider.id) }) {
                            Icon(Icons.Outlined.Download, "Загрузить пакет")
                        }
                    }
                }
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Загрузка ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
                }
                vm.modelErrors[provider.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state == ProviderState.SDK_REQUIRED) {
                    Text("SDK требует отдельной лицензии и не включён в публичный репозиторий.", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProviderPicker(
    title: String,
    mode: RecognitionMode,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        AppContainer.recognitionRegistry.compatible(mode).forEach { provider ->
            val active = provider.id == selected
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onSelect(provider.id) },
                color = if (active) InkColors.AccentSoft else InkColors.PaperRaised,
                shape = RoundedCornerShape(18.dp),
                border = if (active) BorderStroke(1.dp, InkColors.Accent.copy(alpha = .4f)) else null
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(active, onClick = { onSelect(provider.id) })
                    Column {
                        Text(provider.displayName, fontWeight = FontWeight.SemiBold)
                        Text(provider.subtitle, style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = InkColors.PaperRaised) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
        }
        Switch(value, onChange)
    }
}

private fun FingerAction.label() = when (this) {
    FingerAction.PAN -> "Навигация"
    FingerAction.DRAW -> "Рисование"
    FingerAction.ERASE -> "Ластик"
    FingerAction.IGNORE -> "Игнорировать"
}

private fun StylusButtonAction.label() = when (this) {
    StylusButtonAction.ERASE -> "Ластик"
    StylusButtonAction.LASSO -> "Лассо"
    StylusButtonAction.IGNORE -> "Не менять"
}

private fun ProviderState.label() = when (this) {
    ProviderState.READY -> "Готов"
    ProviderState.MODEL_REQUIRED -> "Нужно скачать"
    ProviderState.SDK_REQUIRED -> "Нужен SDK"
    ProviderState.UNAVAILABLE -> "Недоступен"
}

private fun formatBytes(bytes: Long): String = "%.1f МБ".format(bytes / 1024f / 1024f)
