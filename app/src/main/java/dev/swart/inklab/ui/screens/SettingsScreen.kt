package dev.swart.inklab.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.storage.EraserMode
import dev.swart.inklab.core.storage.StylusButtonAction
import dev.swart.inklab.core.update.AppUpdater
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val preferences = vm.inputPreferences
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember(context) { AppUpdater(context.applicationContext) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
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
                SettingsCard("Жесты", "Отмена коротким одновременным касанием двух пальцев") {
                    SettingSwitch("Отмена двумя пальцами", "Блокируется рядом со стилусом и сразу после письма", preferences.twoFingerUndo) {
                        vm.updateInputPreferences(preferences.copy(twoFingerUndo = it))
                    }
                    Text("Палец прокручивает страницы, два пальца меняют масштаб. Для отмены уберите перо от экрана.", color = InkColors.Muted)
                }
            }
            item { Action("Языковые пакеты распознавания") { vm.languagePanel = true } }
            item {
                SettingsCard("Данные и перенос", "Обновление поверх установленной версии сохраняет данные") {
                    Action("Создать переносимую резервную копию") { vm.libraryTools = true }
                    Text(
                        "Android Auto Backup включён, но облачный системный архив ограничен размером устройства. Перед удалением приложения сохраните явную копию — приватные данные после удаления нельзя гарантированно оставить на устройстве.",
                        color = InkColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                SettingsCard("Обновление приложения", "Проверка подписи и SHA-256 перед системной установкой") {
                    Action(if (updateBusy) "Проверяем…" else "Проверить обновления") {
                        if (!updateBusy) scope.launch {
                            updateBusy = true
                            runCatching {
                                val update = updater.check() ?: return@runCatching "Установлена актуальная версия"
                                val apk = updater.download(update)
                                context.startActivity(updater.installIntent(apk))
                                if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                                    "Разрешите установку из InkLab, затем нажмите «Проверить обновления» ещё раз"
                                } else "Обновление ${update.version} передано системному установщику"
                            }.onSuccess { updateMessage = it }
                                .onFailure { updateMessage = it.message ?: "Не удалось проверить обновление" }
                            updateBusy = false
                        }
                    }
                    if (updateBusy) CircularProgressIndicator()
                }
            }
            item {
                SettingsCard("Оформление", "Комфортный вид днём и вечером") {
                    SettingSwitch("Как в системе", "Автоматически выбирать тему Android", preferences.systemTheme) {
                        vm.updateInputPreferences(preferences.copy(systemTheme = it))
                    }
                    SettingSwitch("Ночной вид бумаги", "Адаптировать бумагу и нейтральные чернила только на экране", preferences.nightPaper) {
                        vm.updateInputPreferences(preferences.copy(nightPaper = it))
                    }
                    SettingSwitch("Тёмная тема", "Используется, когда системная тема отключена", preferences.darkTheme) {
                        vm.updateInputPreferences(preferences.copy(darkTheme = it))
                    }
                }
            }
        }
    }
    updateMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { updateMessage = null },
            text = { Text(message) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { updateMessage = null }) { Text("ОК") } }
        )
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
    Row(Modifier.toggleable(value=value,role=Role.Switch,onValueChange=onChange),verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
        }
        Switch(value, null)
    }
}

private fun StylusButtonAction.label() = when (this) {
    StylusButtonAction.ERASE -> "Ластик"
    StylusButtonAction.LASSO -> "Лассо"
    StylusButtonAction.IGNORE -> "Не менять"
}
