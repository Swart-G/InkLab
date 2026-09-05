package dev.swart.inklab.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clipToBounds
import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.swart.inklab.R
import dev.swart.inklab.core.model.ConvertedInkKind
import dev.swart.inklab.core.model.DocumentFormat
import dev.swart.inklab.core.model.PageOrientation
import dev.swart.inklab.core.model.PaperPattern
import dev.swart.inklab.core.recognition.RecognitionMode
import dev.swart.inklab.core.storage.EraserMode
import dev.swart.inklab.ui.AppScreen
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.components.GlassPanel
import dev.swart.inklab.ui.theme.InkColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val context = LocalContext.current
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val board = vm.currentBoard
    BackHandler { vm.navigate(AppScreen.BOARDS) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = { EditorDrawer(vm) { scope.launch { drawerState.close() } } }
    ) {
        Box(Modifier.fillMaxSize().background(InkColors.Paper)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(vm, onMenu = { scope.launch { drawerState.open() } })
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    ToolDock(vm, Modifier.widthIn(max = 840.dp).fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = vm::undo) { Icon(Icons.Outlined.Undo, "Отменить") }
                    IconButton(onClick = vm::redo) { Icon(Icons.Outlined.Redo, "Повторить") }
                    Spacer(Modifier.weight(1f))
                    Text("${(vm.viewportScale * 100).roundToInt()}%", color = InkColors.Muted)
                    if (board?.format == DocumentFormat.NOTEBOOK) {
                        TextButton(onClick = vm::resetViewport) { Text("По ширине") }
                        TextButton(onClick = vm::fitPage) { Text("Лист целиком") }
                    } else {
                        TextButton(onClick = vm::resetViewport) { Text("Исходный вид") }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wideWorkspace = maxWidth >= 600.dp
                    Column(Modifier.fillMaxSize()) {
                        if (!wideWorkspace && board?.format == DocumentFormat.NOTEBOOK) CompactPageStrip(vm)
                        Row(Modifier.fillMaxSize()) {
                            if (wideWorkspace && board?.format == DocumentFormat.NOTEBOOK) InlinePageRail(vm)
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                                EditorCanvas(vm, Modifier.fillMaxSize())
                                ObjectMenu(vm, context)
                            }
                        }
                    }
                }
            }
            RecognitionStatus(vm, Modifier.align(Alignment.TopCenter).padding(top = 76.dp).zIndex(10f))

            vm.editingConvertedObject?.let { EditConvertedDialog(vm, it.kind, it.content) }
        }
    }
}

@Composable
private fun EditorDrawer(vm: EditorViewModel, close: () -> Unit) {
    ModalDrawerSheet(modifier = Modifier.width(320.dp).fillMaxHeight(), drawerContainerColor = InkColors.PaperRaised) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = InkColors.Accent) {
                    Icon(Icons.Outlined.Draw, null, tint = Color.White, modifier = Modifier.padding(10.dp))
                }
                Text("InkLab", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            NavigationDrawerItem(
                label = { Text("Библиотека") }, selected = false,
                icon = { Icon(Icons.Outlined.NoteAlt, null) },
                onClick = { close(); vm.navigate(AppScreen.BOARDS) }
            )
            NavigationDrawerItem(
                label = { Text("Настройки") }, selected = false,
                icon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { close(); vm.navigate(AppScreen.SETTINGS) }
            )
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = InkColors.Line)
            Text("Недавние", color = InkColors.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            vm.boards.filter { it.deletedAt == null }.take(6).forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.title, maxLines = 1) },
                    selected = item.id == vm.currentBoardId,
                    onClick = { close(); vm.openBoard(item.id) }
                )
            }
        }
    }
}

@Composable
private fun TopBar(vm: EditorViewModel, onMenu: () -> Unit) {
    var paperMenu by remember { mutableStateOf(false) }
    var renameDialog by remember(vm.currentBoardId) { mutableStateOf(false) }
    val board = vm.currentBoard
    Row(Modifier.fillMaxWidth().testTag("documentToolbar").padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Меню") }
        Column(Modifier.weight(1f).padding(start = 8.dp).clickable(enabled = board != null) { renameDialog = true }) {
            Text(board?.title ?: "InkLab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (vm.storageError != null) "Ошибка сохранения" else if (vm.saving) "Сохраняю…" else "Сохранено на устройстве",
                color = InkColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        IconButton(onClick = { vm.audioPanel = true }) { Icon(Icons.Outlined.Mic, "Диктофон", tint = if (dev.swart.inklab.audio.AudioHub.activeId != null) MaterialTheme.colorScheme.error else InkColors.Ink) }
        IconButton(onClick = { vm.documentActions = true }) { Icon(Icons.Outlined.MoreVert, "Действия с документом") }
        Box {
            IconButton(onClick = { paperMenu = true }, modifier = Modifier.background(InkColors.PaperRaised, CircleShape)) {
                Icon(Icons.Outlined.Tune, "Быстрые настройки бумаги")
            }
            PaperMenu(vm, paperMenu) { paperMenu = false }
        }
    }
    if (renameDialog && board != null) {
        RenameDocumentDialog(board.title, { renameDialog = false }) { title ->
            vm.renameBoard(board.id, title)
            renameDialog = false
        }
    }
}

@Composable
private fun RenameDocumentDialog(initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Переименовать файл") },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Название") }) },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { save(value.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }
    )
}

@Composable
private fun PaperMenu(vm: EditorViewModel, expanded: Boolean, dismiss: () -> Unit) {
    val settings = vm.currentBoard?.settings ?: return
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss, modifier = Modifier.width(310.dp).background(InkColors.PaperRaised)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Бумага", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PaperPattern.entries.forEach { pattern ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (settings.pattern == pattern) InkColors.AccentSoft else InkColors.Paper,
                        modifier = Modifier.clickable { vm.updatePaperSettings(settings.copy(pattern = pattern)) }
                    ) { Text(pattern.shortLabel(), Modifier.padding(horizontal = 9.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text("Цвет листа", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                paperPalette.forEach { color ->
                    ColorDot(Color(color), Color(settings.paperColor), 30.dp) { vm.updatePaperSettings(settings.copy(paperColor = color)) }
                }
            }
            Text("Шаг · ${settings.spacing.toInt()}", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
            Slider(settings.spacing, { vm.updatePaperSettings(settings.copy(spacing = it)) }, valueRange = 20f..56f, steps = 8)
        }
    }
}

@Composable
private fun ToolDock(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var optionsFor by remember { mutableStateOf<EditorTool?>(null) }
    var editingColorSlot by remember { mutableStateOf<Int?>(null) }
    Box(modifier) {
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(Icons.Outlined.Draw, "Перо", vm.tool == EditorTool.PEN) {
                    if (vm.tool == EditorTool.PEN) optionsFor = EditorTool.PEN else vm.tool = EditorTool.PEN
                }
                EraserToolButton(vm.tool == EditorTool.ERASER) {
                    if (vm.tool == EditorTool.ERASER) optionsFor = EditorTool.ERASER else vm.tool = EditorTool.ERASER
                }
                ToolButton(Icons.Outlined.Gesture, "Лассо", vm.tool == EditorTool.LASSO) {
                    if (vm.tool == EditorTool.LASSO) optionsFor = EditorTool.LASSO else vm.tool = EditorTool.LASSO
                }
                Box(Modifier.padding(horizontal = 6.dp).size(width = 1.dp, height = 30.dp).background(InkColors.Line))
                vm.inputPreferences.quickPenColors.forEachIndexed { index, color ->
                    QuickColorDot(
                        color = if (index == 0) InkColors.Ink else Color(color),
                        label = if(index == 0) "Основной цвет" else "Быстрый цвет ${index + 1}",
                        selected = vm.tool == EditorTool.PEN && vm.penColor == Color(if (index == 0) 0xFF25272C.toInt() else color),
                        onTap = { vm.chooseQuickPenColor(index) },
                        onLongPress = { if (index == 0) Toast.makeText(context, "Основной цвет следует теме и не изменяется", Toast.LENGTH_SHORT).show() else editingColorSlot = index }
                    )
                }
                IconButton(onClick = { optionsFor = vm.tool }) {
                    Icon(Icons.Outlined.Tune, "Параметры инструмента", Modifier.size(21.dp))
                }
            }
        }
        DropdownMenu(expanded = optionsFor != null, onDismissRequest = { optionsFor = null }, modifier = Modifier.width(292.dp).background(InkColors.PaperRaised)) {
            when (optionsFor) {
                EditorTool.PEN -> PenOptions(vm)
                EditorTool.ERASER -> EraserOptions(vm)
                EditorTool.LASSO -> LassoOptions(vm) { optionsFor = null }
                null -> Unit
            }
        }
    }
    editingColorSlot?.let { slot ->
        val initial = Color(vm.inputPreferences.quickPenColors[slot])
        QuickColorDialog(initial, { editingColorSlot = null }) { color ->
            vm.updateQuickPenColor(slot, color)
            editingColorSlot = null
        }
    }
}

@Composable
private fun QuickColorDot(color: Color, label: String, selected: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = label }.pointerInput(color, selected) {
        detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
    }, contentAlignment = Alignment.Center) {
    Surface(
        modifier = Modifier
            .size(30.dp),
        shape = CircleShape,
        color = color,
        border = if (selected) BorderStroke(3.dp, InkColors.Accent) else BorderStroke(1.dp, InkColors.Line)
    ) {}
    }
}

@Composable
private fun QuickColorDialog(initial: Color, dismiss: () -> Unit, save: (Color) -> Unit) {
    var red by remember(initial) { mutableFloatStateOf(initial.red * 255f) }
    var green by remember(initial) { mutableFloatStateOf(initial.green * 255f) }
    var blue by remember(initial) { mutableFloatStateOf(initial.blue * 255f) }
    val current = Color(red / 255f, green / 255f, blue / 255f, 1f)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Быстрый цвет") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(42.dp), CircleShape, current, border = BorderStroke(1.dp, InkColors.Line)) {}
                    Text("Удерживайте цвет на панели, чтобы изменить его снова.", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    penPalette.forEach { preset ->
                        ColorDot(preset, current, 25.dp) {
                            red = preset.red * 255f
                            green = preset.green * 255f
                            blue = preset.blue * 255f
                        }
                    }
                }
                Text("R · ${red.toInt()}", style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
                Slider(red, { red = it }, valueRange = 0f..255f)
                Text("G · ${green.toInt()}", style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
                Slider(green, { green = it }, valueRange = 0f..255f)
                Text("B · ${blue.toInt()}", style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
                Slider(blue, { blue = it }, valueRange = 0f..255f)
            }
        },
        confirmButton = { TextButton(onClick = { save(current) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } }
    )
}

@Composable
private fun PenOptions(vm: EditorViewModel) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Перо", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            penPalette.forEachIndexed { index, color ->
                val display = if(index == 0) InkColors.Ink else color
                ColorDot(display, if(vm.penColor == color) display else Color.Transparent, 28.dp) { vm.choosePenColor(color) }
            }
        }
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
        Text("Обведите рукопись или перетащите выбранный объект.", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (vm.selectionBounds != null) FilledTonalButton(onClick = { vm.clearSelection(); dismiss() }) {
            Icon(Icons.Outlined.Close, null); Spacer(Modifier.width(6.dp)); Text("Снять выделение")
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.ObjectMenu(vm: EditorViewModel, context: android.content.Context) {
    val bounds = vm.selectionBounds ?: return
    val density = LocalDensity.current
    val menuWidth = with(density) { 306.dp.toPx() }
    val menuHeight = with(density) { 58.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }
    val screenX = vm.canvasToScreen(bounds.center).x
    val screenTop = vm.canvasToScreen(bounds.topLeft).y
    val screenBottom = vm.canvasToScreen(bounds.bottomRight).y
    val widthPx = constraints.maxWidth.toFloat()
    val heightPx = constraints.maxHeight.toFloat()
    val x = (screenX - menuWidth / 2f).coerceIn(margin, (widthPx - menuWidth - margin).coerceAtLeast(margin))
    val y = if (screenTop > menuHeight + margin * 2f) screenTop - menuHeight - margin else screenBottom + margin
    var recognizeExpanded by remember(bounds, vm.selectedConvertedId) { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(initialScale = 0.82f),
        modifier = Modifier.offset { IntOffset(x.roundToInt(), y.coerceIn(margin, (heightPx - menuHeight - margin).coerceAtLeast(margin)).roundToInt()) }.zIndex(8f)
    ) {
        GlassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.selectedIds.isNotEmpty()) {
                    Box {
                        FilledTonalButton(
                            onClick = { recognizeExpanded = true },
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = InkColors.AccentSoft)
                        ) { Text("Распознать") }
                        DropdownMenu(expanded = recognizeExpanded, onDismissRequest = { recognizeExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Текст · ${vm.currentBoard?.languageTag ?: "ru-RU"}") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) },
                                onClick = { recognizeExpanded = false; vm.recognize(context, RecognitionMode.TEXT) }
                            )
                            DropdownMenuItem(text = { Text("Выбрать язык…") }, onClick = { recognizeExpanded = false; vm.languagePanel = true })
                            DropdownMenuItem(
                                text = { Text("Формула") }, leadingIcon = { Icon(Icons.Outlined.Functions, null) },
                                onClick = { recognizeExpanded = false; vm.recognize(context, RecognitionMode.MATH) }
                            )
                        }
                    }
                    IconButton(onClick = vm::duplicateSelection) { Icon(Icons.Outlined.ContentCopy, "Дублировать") }
                    IconButton(onClick = vm::deleteSelection) { Icon(Icons.Outlined.DeleteOutline, "Удалить") }
                } else {
                    IconButton(onClick = vm::beginEditConverted) { Icon(Icons.Outlined.Edit, "Редактировать") }
                    FilledTonalButton(onClick = vm::restoreConvertedSelection) {
                        Icon(Icons.Outlined.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("В рукопись")
                    }
                    IconButton(onClick = vm::duplicateConvertedSelection) { Icon(Icons.Outlined.ContentCopy, "Дублировать") }
                    IconButton(onClick = vm::deleteConvertedSelection) { Icon(Icons.Outlined.DeleteOutline, "Удалить") }
                }
                IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, "Закрыть") }
            }
        }
    }
}

@Composable
private fun PageControls(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val board = vm.currentBoard ?: return
    GlassPanel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = vm.currentPageIndex > 0, onClick = { vm.openPage(vm.currentPageIndex - 1) }) {
                Icon(Icons.Outlined.ArrowBack, "Предыдущая страница")
            }
            Text("${vm.currentPageIndex + 1} / ${board.pages.size}", style = MaterialTheme.typography.labelLarge)
            IconButton(enabled = vm.currentPageIndex < board.pages.lastIndex, onClick = { vm.openPage(vm.currentPageIndex + 1) }) {
                Icon(Icons.Outlined.ArrowBack, "Следующая страница", Modifier.graphicsLayer(rotationZ = 180f))
            }
            IconButton(onClick = vm::addPage) { Icon(Icons.Outlined.Add, "Добавить лист") }
            if (board.pages.size > 1) IconButton(onClick = vm::deleteCurrentPage) { Icon(Icons.Outlined.DeleteOutline, "Удалить лист") }
        }
    }
}

@Composable
private fun RecognitionStatus(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val state = vm.recognition ?: return
    if (state.result != null && state.mode == RecognitionMode.TEXT) {
        AlertDialog(onDismissRequest = { vm.recognition = null }, title = { Text("Результат распознавания") },
            text = { Column { state.result.candidates.ifEmpty { listOf(state.result.primary) }.take(5).forEach { candidate ->
                TextButton(onClick = { vm.chooseRecognitionCandidate(candidate) }) { Text(candidate) }
            } } }, confirmButton = { TextButton(onClick = vm::applyRecognition) { Text("Вставить") } },
            dismissButton = { TextButton(onClick = { vm.recognition = null }) { Text("Отмена") } })
        return
    }
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { vm.recognition = null },
            title = { Text("Не удалось распознать") },
            text = { Text(state.error) },
            confirmButton = { TextButton(onClick = { vm.recognition = null }) { Text("Закрыть") } }
        )
    } else if (state.loading) {
        GlassPanel(modifier) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (state.mode == RecognitionMode.TEXT) "Распознаю текст…" else "Распознаю формулу…")
            }
        }
    }
}

@Composable
private fun EditConvertedDialog(vm: EditorViewModel, kind: ConvertedInkKind, initial: String) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = vm::cancelEditConverted,
        title = { Text(if (kind == ConvertedInkKind.MATH) "Редактировать LaTeX" else "Редактировать текст") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), minLines = if (kind == ConvertedInkKind.MATH) 2 else 3)
                if (kind == ConvertedInkKind.MATH) Text("Формула перерисуется после сохранения.", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { vm.updateConvertedContent(value) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = vm::cancelEditConverted) { Text("Отмена") } }
    )
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) InkColors.Accent else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else InkColors.Ink
        )
    ) { Icon(icon, contentDescription = label) }
}

@Composable
private fun EraserToolButton(selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) InkColors.Accent else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else InkColors.Ink
        )
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ink_eraser),
            contentDescription = "Ластик",
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ColorDot(color: Color, selected: Color, size: Dp, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(size).clickable(onClick = onClick),
        shape = CircleShape,
        color = color,
        border = if (color == selected) BorderStroke(3.dp, InkColors.Accent) else BorderStroke(1.dp, InkColors.Line)
    ) {}
}

private fun PaperPattern.shortLabel() = when (this) {
    PaperPattern.RULED -> "Линии"
    PaperPattern.GRID -> "Клетка"
    PaperPattern.DOTS -> "Точки"
    PaperPattern.BLANK -> "Чисто"
}

private val penPalette = listOf(
    Color(0xFF25272C), Color(0xFF6157D9), Color(0xFF246BCE), Color(0xFF0D8B65),
    Color(0xFFE05A47), Color(0xFFD83A73), Color(0xFFF09A33)
)

private val paperPalette = listOf(
    0xFFFBF9F5, 0xFFFFFFFF, 0xFFFFFAE8, 0xFFF1F7FF, 0xFFF4EEFF, 0xFF202126
)
