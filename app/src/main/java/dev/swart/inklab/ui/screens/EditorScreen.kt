package dev.swart.inklab.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.recognition.RecognitionMode
import dev.swart.inklab.core.storage.EraserMode
import dev.swart.inklab.core.storage.FingerAction
import dev.swart.inklab.ui.AppScreen
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.components.GlassPanel
import dev.swart.inklab.ui.theme.InkColors
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val context = LocalContext.current
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            EditorDrawer(
                vm = vm,
                close = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Box(Modifier.fillMaxSize().background(InkColors.Paper)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(vm, onMenu = { scope.launch { drawerState.open() } })
                Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Surface(
                        Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(30.dp),
                        color = InkColors.PaperRaised,
                        tonalElevation = 0.dp,
                        shadowElevation = 2.dp
                    ) { EditorCanvas(vm, Modifier.fillMaxSize()) }

                    ToolDock(vm, Modifier.align(Alignment.CenterStart).padding(start = 14.dp))

                    AssistChip(
                        onClick = { vm.resetViewport() },
                        label = { Text("${(vm.viewportScale * 100).toInt()}%") },
                        leadingIcon = { Icon(Icons.Outlined.FitScreen, null, Modifier.size(16.dp)) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
                    )

                    AnimatedVisibility(
                        visible = vm.selectedIds.isNotEmpty(),
                        enter = fadeIn() + scaleIn(initialScale = 0.92f),
                        exit = fadeOut() + scaleOut(targetScale = 0.92f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                    ) {
                        SelectionBar(
                            count = vm.selectedIds.size,
                            onText = { vm.recognize(context, RecognitionMode.TEXT) },
                            onMath = { vm.recognize(context, RecognitionMode.MATH) },
                            onCompare = { vm.navigate(AppScreen.LAB) },
                            onDuplicate = vm::duplicateSelection,
                            onDelete = vm::deleteSelection,
                            onClose = vm::clearSelection
                        )
                    }

                    AnimatedVisibility(
                        visible = vm.selectedConvertedObject != null,
                        enter = fadeIn() + scaleIn(initialScale = 0.92f),
                        exit = fadeOut() + scaleOut(targetScale = 0.92f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                    ) {
                        val item = vm.selectedConvertedObject
                        ConvertedSelectionBar(
                            kind = item?.kind,
                            onRestore = vm::restoreConvertedSelection,
                            onDuplicate = vm::duplicateConvertedSelection,
                            onDelete = vm::deleteConvertedSelection,
                            onClose = vm::clearSelection
                        )
                    }
                }
            }

            vm.recognition?.let { state -> RecognitionDialog(vm, state.mode) }
        }
    }
}

@Composable
private fun EditorDrawer(vm: EditorViewModel, close: () -> Unit) {
    ModalDrawerSheet(
        modifier = Modifier.width(330.dp).fillMaxHeight(),
        drawerContainerColor = InkColors.PaperRaised
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = InkColors.Accent) {
                    Icon(Icons.Outlined.Draw, null, tint = Color.White, modifier = Modifier.padding(10.dp))
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text("InkLab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("local ink studio", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            NavigationDrawerItem(
                label = { Text("Все доски") },
                selected = false,
                icon = { Icon(Icons.Outlined.NoteAlt, null) },
                onClick = { close(); vm.navigate(AppScreen.BOARDS) }
            )
            NavigationDrawerItem(
                label = { Text("OCR Lab") },
                selected = false,
                icon = { Icon(Icons.Outlined.Science, null) },
                onClick = { close(); vm.navigate(AppScreen.LAB) }
            )
            NavigationDrawerItem(
                label = { Text("Настройки") },
                selected = false,
                icon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { close(); vm.navigate(AppScreen.SETTINGS) }
            )
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = InkColors.Line)
            Text("Недавние", color = InkColors.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            vm.boards.take(6).forEach { board ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(board.title, maxLines = 1)
                            if (board.subject.isNotBlank()) Text(board.subject, color = InkColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    },
                    selected = board.id == vm.currentBoardId,
                    onClick = { close(); vm.openBoard(board.id) }
                )
            }
        }
    }
}

@Composable
private fun TopBar(vm: EditorViewModel, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Меню") }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(vm.currentBoard?.title ?: "InkLab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                vm.currentBoard?.subject?.ifBlank { "InkLab · локальный конспект" } ?: "InkLab",
                style = MaterialTheme.typography.bodySmall,
                color = InkColors.Muted
            )
        }
        AssistChip(
            onClick = { vm.navigate(AppScreen.SETTINGS) },
            label = { Text(if (vm.inputPreferences.fingerAction == FingerAction.PAN) "Палец: навигация" else "Палец: ${vm.inputPreferences.fingerAction.name.lowercase()}") }
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = vm::undo) { Icon(Icons.Outlined.Undo, "Отменить") }
        IconButton(onClick = vm::redo) { Icon(Icons.Outlined.Redo, "Повторить") }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { vm.navigate(AppScreen.BOARD_SETTINGS) }, modifier = Modifier.background(InkColors.PaperRaised, CircleShape)) {
            Icon(Icons.Outlined.Tune, "Параметры доски")
        }
    }
}

@Composable
private fun ToolDock(vm: EditorViewModel, modifier: Modifier = Modifier) {
    var optionsFor by remember { mutableStateOf<EditorTool?>(null) }
    Box(modifier) {
        GlassPanel {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ToolButton(Icons.Outlined.Draw, "Перо", vm.tool == EditorTool.PEN) {
                    if (vm.tool == EditorTool.PEN) optionsFor = EditorTool.PEN else vm.tool = EditorTool.PEN
                }
                ToolButton(Icons.Outlined.AutoFixOff, "Ластик", vm.tool == EditorTool.ERASER) {
                    if (vm.tool == EditorTool.ERASER) optionsFor = EditorTool.ERASER else vm.tool = EditorTool.ERASER
                }
                ToolButton(Icons.Outlined.Gesture, "Лассо", vm.tool == EditorTool.LASSO) {
                    if (vm.tool == EditorTool.LASSO) optionsFor = EditorTool.LASSO else vm.tool = EditorTool.LASSO
                }
                HorizontalDivider(Modifier.width(34.dp).padding(vertical = 6.dp), color = InkColors.Line)
                IconButton(onClick = { optionsFor = vm.tool }) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        when (vm.tool) {
                            EditorTool.PEN -> Box(Modifier.size(vm.penWidth.dp.coerceAtMost(13.dp)).background(vm.penColor, CircleShape))
                            EditorTool.ERASER -> Surface(Modifier.size((vm.inputPreferences.eraserRadius / 3f).dp.coerceIn(7.dp, 18.dp)), CircleShape, color = InkColors.AccentSoft) {}
                            EditorTool.LASSO -> Icon(Icons.Outlined.Gesture, null, Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        DropdownMenu(
            expanded = optionsFor != null,
            onDismissRequest = { optionsFor = null },
            modifier = Modifier.width(280.dp).background(InkColors.PaperRaised)
        ) {
            when (optionsFor) {
                EditorTool.PEN -> PenOptions(vm)
                EditorTool.ERASER -> EraserOptions(vm)
                EditorTool.LASSO -> LassoOptions(vm) { optionsFor = null }
                null -> Unit
            }
        }
    }
}

@Composable
private fun PenOptions(vm: EditorViewModel) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Перо", fontWeight = FontWeight.SemiBold)
        Text("Толщина · ${vm.penWidth.toInt()}", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
        Slider(vm.penWidth, { vm.penWidth = it }, valueRange = 2f..12f, steps = 9)
    }
}

@Composable
private fun EraserOptions(vm: EditorViewModel) {
    val preferences = vm.inputPreferences
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ластик", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(
                onClick = { vm.updateInputPreferences(preferences.copy(eraserMode = EraserMode.PIXEL)) },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (preferences.eraserMode == EraserMode.PIXEL) InkColors.AccentSoft else InkColors.Paper)
            ) { Text("Пиксельный") }
            FilledTonalButton(
                onClick = { vm.updateInputPreferences(preferences.copy(eraserMode = EraserMode.STROKE)) },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (preferences.eraserMode == EraserMode.STROKE) InkColors.AccentSoft else InkColors.Paper)
            ) { Text("Штрих") }
        }
        Text("Размер · ${preferences.eraserRadius.toInt()}", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
        Slider(preferences.eraserRadius, { vm.updateInputPreferences(preferences.copy(eraserRadius = it)) }, valueRange = 8f..54f)
    }
}

@Composable
private fun LassoOptions(vm: EditorViewModel, dismiss: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Лассо", fontWeight = FontWeight.SemiBold)
        Text("Обведите рукопись. Нажмите на преобразованный текст или формулу, чтобы выбрать и переместить объект.", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (vm.selectedIds.isNotEmpty() || vm.selectedConvertedObject != null) FilledTonalButton(onClick = { vm.clearSelection(); dismiss() }) {
            Icon(Icons.Outlined.Close, null); Spacer(Modifier.width(6.dp)); Text("Снять выделение")
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) InkColors.Accent else Color.Transparent,
            contentColor = if (selected) Color.White else InkColors.Ink
        )
    ) { Icon(icon, contentDescription = label) }
}

@Composable
private fun SelectionBar(
    count: Int,
    onText: () -> Unit,
    onMath: () -> Unit,
    onCompare: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$count", style = MaterialTheme.typography.labelLarge, color = InkColors.Muted, modifier = Modifier.padding(horizontal = 8.dp))
            FilledTonalButton(onClick = onText, colors = ButtonDefaults.filledTonalButtonColors(containerColor = InkColors.AccentSoft)) {
                Icon(Icons.Outlined.TextFields, null); Spacer(Modifier.width(8.dp)); Text("В текст")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onMath, colors = ButtonDefaults.filledTonalButtonColors(containerColor = InkColors.Mint)) {
                Icon(Icons.Outlined.Functions, null); Spacer(Modifier.width(8.dp)); Text("Формула")
            }
            IconButton(onClick = onCompare) { Icon(Icons.Outlined.CompareArrows, "Сравнить") }
            IconButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, "Дублировать") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Удалить") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Снять выделение") }
        }
    }
}

@Composable
private fun ConvertedSelectionBar(
    kind: ConvertedInkKind?,
    onRestore: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (kind == ConvertedInkKind.MATH) Icons.Outlined.Functions else Icons.Outlined.TextFields, null, tint = InkColors.Accent, modifier = Modifier.padding(horizontal = 8.dp))
            Text(if (kind == ConvertedInkKind.MATH) "Формула" else "Текст", style = MaterialTheme.typography.labelLarge, color = InkColors.Muted)
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(onClick = onRestore, colors = ButtonDefaults.filledTonalButtonColors(containerColor = InkColors.AccentSoft)) {
                Icon(Icons.Outlined.Undo, null); Spacer(Modifier.width(8.dp)); Text("Вернуть рукопись")
            }
            IconButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, "Дублировать") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Удалить") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Снять выделение") }
        }
    }
}

@Composable
private fun RecognitionDialog(vm: EditorViewModel, mode: RecognitionMode) {
    val state = vm.recognition ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.recognition = null },
        icon = { Icon(if (mode == RecognitionMode.TEXT) Icons.Outlined.TextFields else Icons.Outlined.Functions, null) },
        title = { Text(if (mode == RecognitionMode.TEXT) "Заменить рукопись текстом" else "Заменить рукопись формулой") },
        text = {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text("Локальное распознавание…")
                }
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                state.result != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.result.primary.ifBlank { "Нет результата" }, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (mode == RecognitionMode.TEXT)
                            "Результат будет отрисован рукописным шрифтом. Исходные штрихи сохранятся внутри объекта."
                        else
                            "LaTeX будет отрисован математическим движком. Исходные штрихи сохранятся внутри объекта.",
                        color = InkColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("${state.result.latencyMs} мс · ${state.result.providerId}", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
                    state.result.note?.let { Text(it, color = InkColors.Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = state.result?.primary?.isNotBlank() == true,
                onClick = vm::applyRecognition
            ) { Text("Заменить") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.recognition = null }) { Text("Отмена") } }
    )
}
