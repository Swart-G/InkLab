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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.model.BoardSettings
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.core.model.InkFolder
import dev.swart.inklab.core.model.PageOrientation
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors
import java.text.DateFormat
import java.util.Date

@Composable
fun BoardsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    var currentFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var creatingFormat by remember { mutableStateOf<DocumentFormat?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }

    val currentFolder = currentFolderId?.let { id -> vm.folders.firstOrNull { it.id == id } }
    val visibleFolders = vm.folders.filter { it.parentId == currentFolderId }
    val visibleBoards = vm.boards.filter { it.folderId == currentFolderId }

    Box(Modifier.fillMaxSize().background(InkColors.Paper)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (currentFolder != null) currentFolderId = currentFolder.parentId else if (vm.currentBoard != null) onBack()
                }) { Icon(Icons.Outlined.ArrowBack, "Назад") }
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Файлы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    if (currentFolder != null) Text(currentFolder.title, color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(22.dp))

            if (visibleFolders.isEmpty() && visibleBoards.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Surface(shape = RoundedCornerShape(24.dp), color = InkColors.AccentSoft) {
                        Icon(Icons.Outlined.Folder, null, tint = InkColors.Accent, modifier = Modifier.padding(22.dp).size(40.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(if (currentFolder == null) "Здесь появятся ваши доски, тетради и папки" else "Папка пока пустая", color = InkColors.Muted)
                    TextButton(onClick = { createMenu = true }) { Text("Создать") }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(230.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(visibleFolders, key = { "folder:${it.id}" }) { folder ->
                        FolderCard(
                            folder = folder,
                            itemCount = vm.folders.count { it.parentId == folder.id } + vm.boards.count { it.folderId == folder.id },
                            onOpen = { currentFolderId = folder.id },
                            onRename = { vm.renameFolder(folder.id, it) },
                            onDelete = { vm.deleteFolder(folder.id) }
                        )
                    }
                    items(visibleBoards, key = { "board:${it.id}" }) { board ->
                        BoardCard(
                            board = board,
                            active = board.id == vm.currentBoardId,
                            onOpen = { vm.openBoard(board.id) },
                            onRename = { vm.renameBoard(board.id, it) },
                            onDelete = { vm.deleteBoard(board.id) }
                        )
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            FloatingActionButton(
                onClick = { createMenu = true },
                containerColor = InkColors.Accent,
                contentColor = Color.White
            ) { Icon(Icons.Outlined.Add, "Создать") }
            DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Доска") },
                    leadingIcon = { Icon(Icons.Outlined.Draw, null) },
                    onClick = { createMenu = false; creatingFormat = DocumentFormat.BOARD }
                )
                DropdownMenuItem(
                    text = { Text("Тетрадь") },
                    leadingIcon = { Icon(Icons.Outlined.NoteAlt, null) },
                    onClick = { createMenu = false; creatingFormat = DocumentFormat.NOTEBOOK }
                )
                DropdownMenuItem(
                    text = { Text("Папка") },
                    leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                    onClick = { createMenu = false; creatingFolder = true }
                )
            }
        }
    }

    creatingFormat?.let { format ->
        CreateDocumentDialog(vm, format, currentFolderId) { creatingFormat = null }
    }
    if (creatingFolder) {
        NameDialog(
            title = "Новая папка",
            initial = "",
            confirm = "Создать",
            dismiss = { creatingFolder = false }
        ) { name ->
            vm.createFolder(name, currentFolderId)
            creatingFolder = false
        }
    }
}

@Composable
private fun BoardCard(
    board: InkBoard,
    active: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    val strokeCount = board.pages.sumOf { it.strokes.size }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }

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
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия", tint = InkColors.Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; rename = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { menu = false; delete = true }
                        )
                    }
                }
            }
        }
    }

    if (rename) NameDialog("Переименовать файл", board.title, "Сохранить", { rename = false }) {
        onRename(it)
        rename = false
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text("Удалить файл?") },
        text = { Text("«${board.title}» будет удалён без возможности восстановления.") },
        confirmButton = { TextButton(onClick = { delete = false; onDelete() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } }
    )
}

@Composable
private fun FolderCard(
    folder: InkFolder,
    itemCount: Int,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(26.dp),
        color = InkColors.PaperRaised
    ) {
        Column(Modifier.padding(18.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(1.45f),
                shape = RoundedCornerShape(18.dp),
                color = InkColors.AccentSoft
            ) {
                Box(Modifier.padding(18.dp)) {
                    Icon(Icons.Outlined.Folder, null, tint = InkColors.Accent, modifier = Modifier.size(42.dp))
                    Text(
                        "$itemCount элем.",
                        modifier = Modifier.align(Alignment.BottomStart),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkColors.Muted
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(folder.title, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия", tint = InkColors.Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Переименовать") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; rename = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить папку") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { menu = false; delete = true }
                        )
                    }
                }
            }
        }
    }

    if (rename) NameDialog("Переименовать папку", folder.title, "Сохранить", { rename = false }) {
        onRename(it)
        rename = false
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text("Удалить папку?") },
        text = { Text("Документы и вложенные папки не удалятся — они будут перемещены на уровень выше.") },
        confirmButton = { TextButton(onClick = { delete = false; onDelete() }) { Text("Удалить папку") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Отмена") } }
    )
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Название") }) },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { save(value.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }
    )
}

@Composable
private fun CreateDocumentDialog(vm: EditorViewModel, initialFormat: DocumentFormat, folderId: String?, dismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var format by remember(initialFormat) { mutableStateOf(initialFormat) }
    var orientation by remember { mutableStateOf(PageOrientation.PORTRAIT) }
    var pattern by remember { mutableStateOf(PaperPattern.RULED) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (format == DocumentFormat.BOARD) "Новая доска" else "Новая тетрадь") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                vm.createDocument(
                    title = title,
                    format = format,
                    orientation = if (format == DocumentFormat.BOARD) PageOrientation.LANDSCAPE else orientation,
                    settings = BoardSettings(pattern = pattern),
                    folderId = folderId
                )
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