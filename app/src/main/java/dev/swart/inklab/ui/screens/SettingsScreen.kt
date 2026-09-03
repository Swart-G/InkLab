package dev.swart.inklab.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.storage.EraserMode
import dev.swart.inklab.core.storage.StylusButtonAction
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors

@Composable
fun SettingsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val preferences = vm.inputPreferences
    Column(Modifier.fillMaxSize().background(InkColors.Paper).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Назад") }
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SettingsCard("Стилус", "Кончик пишет, кнопка временно переключает инструмент") {
                    Text("Кнопка стилуса", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StylusButtonAction.entries.forEach { action ->
                            FilterChip(
                                selected = preferences.stylusButtonAction == action,
                                onClick = { vm.updateInputPreferences(preferences.copy(stylusButtonAction = action)) },
                                label = { Text(action.label()) }
                            )
                        }
                    }
                    SettingSwitch("Нажим пера", "Толщина линии зависит от силы нажатия", preferences.pressureEnabled) {
                        vm.updateInputPreferences(preferences.copy(pressureEnabled = it))
                    }
                    SettingSwitch("Защита от ладони", "Игнорировать случайные касания во время письма", preferences.palmRejection) {
                        vm.updateInputPreferences(preferences.copy(palmRejection = it))
                    }
                }
            }
            item {
                SettingsCard("Рисование", "Аккуратные фигуры без отдельного режима") {
                    SettingSwitch(
                        "Исправление фигур",
                        "Удерживайте стилус в конце штриха примерно полсекунды, чтобы выправить линию, окружность или прямоугольник",
                        preferences.autoShapes
                    ) {
                        vm.updateInputPreferences(preferences.copy(autoShapes = it))
                    }
                }
            }
            item {
                SettingsCard("Ластик", "Удалять участок линии или штрих целиком") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EraserMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.eraserMode == mode,
                                onClick = { vm.updateInputPreferences(preferences.copy(eraserMode = mode)) },
                                label = { Text(if (mode == EraserMode.PIXEL) "Пиксельный" else "Весь штрих") }
                            )
                        }
                    }
                    Text("Размер · ${preferences.eraserRadius.toInt()}", color = InkColors.Muted)
                    Slider(
                        value = preferences.eraserRadius,
                        onValueChange = { vm.updateInputPreferences(preferences.copy(eraserRadius = it)) },
                        valueRange = 8f..54f,
                        steps = 22
                    )
                }
            }
            item {
                SettingsCard("Оформление", "Комфортный вид днём и вечером") {
                    SettingSwitch("Тёмная тема", "Тёмный интерфейс с сохранением выбранного цвета бумаги", preferences.darkTheme) {
                        vm.updateInputPreferences(preferences.copy(darkTheme = it))
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

private fun StylusButtonAction.label() = when (this) {
    StylusButtonAction.ERASE -> "Ластик"
    StylusButtonAction.LASSO -> "Лассо"
    StylusButtonAction.IGNORE -> "Не менять"
}
