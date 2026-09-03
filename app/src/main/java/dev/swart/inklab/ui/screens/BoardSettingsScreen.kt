package dev.swart.inklab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors

@Composable
fun BoardSettingsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val board = vm.currentBoard ?: return
    var title by remember(board.id) { mutableStateOf(board.title) }
    var subject by remember(board.id) { mutableStateOf(board.subject) }
    var settings by remember(board.id) { mutableStateOf(board.settings) }

    Column(Modifier.fillMaxSize().background(InkColors.Paper).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Назад") }
            Column(Modifier.padding(start = 8.dp)) {
                Text("Параметры доски", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Название и вид бумаги", color = InkColors.Muted)
            }
        }
        Spacer(Modifier.height(22.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = InkColors.PaperRaised
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(subject, { subject = it }, label = { Text("Предмет или папка") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Разметка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperPattern.entries.forEach { pattern ->
                        FilterChip(
                            selected = settings.pattern == pattern,
                            onClick = { settings = settings.copy(pattern = pattern) },
                            label = { Text(pattern.label()) }
                        )
                    }
                }
                Text("Шаг разметки · ${settings.spacing.toInt()} dp", color = InkColors.Muted)
                Slider(
                    value = settings.spacing,
                    onValueChange = { settings = settings.copy(spacing = it) },
                    valueRange = 20f..56f,
                    steps = 8
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Красное поле")
                        Text("Вертикальная линия слева", style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
                    }
                    Switch(settings.showMargin, { settings = settings.copy(showMargin = it) })
                }
                Button(
                    onClick = { vm.updateBoard(title, subject, settings); onBack() },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Сохранить") }
            }
        }
    }
}

private fun PaperPattern.label() = when (this) {
    PaperPattern.RULED -> "Линейка"
    PaperPattern.GRID -> "Клетка"
    PaperPattern.DOTS -> "Точки"
    PaperPattern.BLANK -> "Чистая"
}
