package dev.swart.inklab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.model.BoardSettings
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.PageOrientation
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors
import java.text.DateFormat
import java.util.Date

@Composable
fun BoardsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(InkColors.Paper)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.currentBoard != null) IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Назад") }
                Text("Файлы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(22.dp))
            if (vm.boards.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Surface(shape = RoundedCornerShape(24.dp), color = InkColors.AccentSoft) {
                        Icon(Icons.Outlined.NoteAlt, null, tint = InkColors.Accent, modifier = Modifier.padding(22.dp).size(40.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Здесь появятся ваши доски и тетради", color = InkColors.Muted)
                    TextButton(onClick = { creating = true }) { Text("Создать первый файл") }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(230.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(vm.boards, key = { it.id }) { board ->
                        BoardCard(board, board.id == vm.currentBoardId, { vm.openBoard(board.id) }, { vm.deleteBoard(board.id) })
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp),
            containerColor = InkColors.Accent,
            contentColor = Color.White
        ) { Icon(Icons.Outlined.Add, "Создать") }
    }
    if (creating) CreateDocumentDialog(vm) { creating = false }
}

@Composable
private fun BoardCard(board: InkBoard, active: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val strokeCount = board.pages.sumOf { it.strokes.size }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(26.dp),
        color = if (active) InkColors.AccentSoft else InkColors.PaperRaised,
        shadowElevation = if (active) 1.dp else 0.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(if (board.format == DocumentFormat.NOTEBOOK && board.orientation == PageOrientation.PORTRAIT) 1.25f else 1.45f),
                shape = RoundedCornerShape(18.dp),
                color = Color(board.settings.paperColor)
            ) {
                Box(Modifier.padding(16.dp)) {
                    Icon(if (board.format == DocumentFormat.NOTEBOOK) Icons.Outlined.NoteAlt else Icons.Outlined.Draw, null, tint = InkColors.Accent.copy(alpha = 0.55f), modifier = Modifier.size(32.dp))
                    Text(
                        if (board.format == DocumentFormat.NOTEBOOK) "${board.pages.size} стр. · $strokeCount штр." else "$strokeCount штрихов",
                        modifier = Modifier.align(Alignment.BottomStart),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkColors.Muted
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(board.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        if (board.format == DocumentFormat.NOTEBOOK) "Тетрадь" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(board.updatedAt)),
                        color = InkColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = InkColors.Muted) }
            }
        }
    }
}

@Composable
private fun CreateDocumentDialog(vm: EditorViewModel, dismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(DocumentFormat.NOTEBOOK) }
    var orientation by remember { mutableStateOf(PageOrientation.PORTRAIT) }
    var pattern by remember { mutableStateOf(PaperPattern.RULED) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Новый файл") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocumentFormat.entries.forEach { item ->
                        FilterChip(selected = format == item, onClick = { format = item }, label = { Text(if (item == DocumentFormat.BOARD) "Доска" else "Тетрадь") })
                    }
                }
                if (format == DocumentFormat.NOTEBOOK) {
                    Text("Ориентация", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PageOrientation.entries.forEach { item ->
                            FilterChip(selected = orientation == item, onClick = { orientation = item }, label = { Text(if (item == PageOrientation.PORTRAIT) "Книжная" else "Альбомная") })
                        }
                    }
                }
                Text("Фон", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaperPattern.entries.forEach { item ->
                        FilterChip(selected = pattern == item, onClick = { pattern = item }, label = { Text(item.createLabel()) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.createDocument(title, format, if (format == DocumentFormat.BOARD) PageOrientation.LANDSCAPE else orientation, BoardSettings(pattern = pattern))
                dismiss()
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }
    )
}

private fun PaperPattern.createLabel() = when (this) {
    PaperPattern.RULED -> "Линии"
    PaperPattern.GRID -> "Клетка"
    PaperPattern.DOTS -> "Точки"
    PaperPattern.BLANK -> "Чистый"
}
