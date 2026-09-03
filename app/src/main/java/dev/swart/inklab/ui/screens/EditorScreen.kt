package dev.swart.inklab.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = vm.selectionBounds == null,
        drawerContent = { EditorDrawer(vm) { scope.launch { drawerState.close() } } }
    ) {
        Box(Modifier.fillMaxSize().background(InkColors.Paper)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(vm, onMenu = { scope.launch { drawerState.open() } })
                Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    val pageModifier = if (board?.format == DocumentFormat.NOTEBOOK) {
                        Modifier.fillMaxHeight().aspectRatio(
                            if (board.orientation == PageOrientation.PORTRAIT) 1f / 1.4142f else 1.4142f,
                            matchHeightConstraintsFirst = true
                        )
                    } else Modifier.fillMaxSize()

                    Surface(
                        pageModifier.align(Alignment.Center),
                        shape = RoundedCornerShape(if (board?.format == DocumentFormat.NOTEBOOK) 16.dp else 30.dp),
                        color = InkColors.PaperRaised,
                        shadowElevation = 3.dp
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            EditorCanvas(vm, Modifier.fillMaxSize())
                            ObjectMenu(vm, context)
                        }
                    }

                    ToolDock(vm, Modifier.align(Alignment.CenterStart).padding(start = 14.dp).zIndex(3f))
                    AssistChip(
                        onClick = vm::resetViewport,
                        label = { Text("${(vm.viewportScale * 100).toInt()}%") },
                        leadingIcon = { Icon(Icons.Outlined.FitScreen, null, Modifier.size(16.dp)) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
                    )
                    if (board?.format == DocumentFormat.NOTEBOOK) {
                        PageControls(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).zIndex(4f))
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
                label = { Text("Все файлы") }, selected = false,
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
            vm.boards.take(6).forEach { item ->
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Меню") }
        Text(
            vm.currentBoard?.title ?: "InkLab",
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        IconButton(onClick = vm::undo) { Icon(Icons.Outlined.Undo, "Отменить") }
        IconButton(onClick = vm::redo) { Icon(Icons.Outlined.Redo, "Повторить") }
        Box {
            IconButton(onClick = { paperMenu = true }, modifier = Modifier.background(InkColors.PaperRaised, CircleShape)) {
                Icon(Icons.Outlined.Tune, "Быстрые настройки бумаги")
            }
            PaperMenu(vm, paperMenu) { paperMenu = false }
        }
    }
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
            TextButton(onClick = { dismiss(); vm.navigate(AppScreen.BOARD_SETTINGS) }, modifier = Modifier.align(Alignment.End)) {
                Text("Параметры файла")
            }
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
                EraserToolButton(vm.tool == EditorTool.ERASER) {
                    if (vm.tool == EditorTool.ERASER) optionsFor = EditorTool.ERASER else vm.tool = EditorTool.ERASER
                }
                ToolButton(Icons.Outlined.Gesture, "Лассо", vm.tool == EditorTool.LASSO) {
                    if (vm.tool == EditorTool.LASSO) optionsFor = EditorTool.LASSO else vm.tool = EditorTool.LASSO
                }
                HorizontalDivider(Modifier.width(34.dp).padding(vertical = 6.dp), color = InkColors.Line)
                IconButton(onClick = { optionsFor = vm.tool }) {
                    when (vm.tool) {
                        EditorTool.PEN -> Box(Modifier.size(vm.penWidth.dp.coerceIn(5.dp, 13.dp)).background(vm.penColor, CircleShape))
                        EditorTool.ERASER -> EraserGlyph(InkColors.Ink, Modifier.size(22.dp))
                        EditorTool.LASSO -> Icon(Icons.Outlined.Gesture, null, Modifier.size(20.dp))
                    }
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
}

@Composable
private fun PenOptions(vm: EditorViewModel) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Перо", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            penPalette.forEach { ColorDot(it, vm.penColor, 28.dp) { vm.choosePenColor(it) } }
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
    val screenX = bounds.center.x * vm.viewportScale + vm.viewportOffset.x
    val screenTop = bounds.top * vm.viewportScale + vm.viewportOffset.y
    val screenBottom = bounds.bottom * vm.viewportScale + vm.viewportOffset.y
    val widthPx = constraints.maxWidth.toFloat()
    val heightPx = constraints.maxHeight.toFloat()
    val x = (screenX - menuWidth / 2f).coerceIn(margin, (widthPx - menuWidth - margin).coerceAtLeast(margin))
    val y = if (screenTop > menuHeight + margin * 2f) screenTop - menuHeight - margin else screenBottom + margin
    var recognizeExpanded by remember(bounds, vm.selectedConvertedId) { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(initialScale = 0.82f),
        modifier = Modifier.offset { IntOffset(x.roundToInt(), y.coerceIn(margin, heightPx - menuHeight - margin).roundToInt()) }.zIndex(8f)
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
                                text = { Text("Текст") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) },
                                onClick = { recognizeExpanded = false; vm.recognize(context, RecognitionMode.TEXT) }
                            )
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
private fun EraserToolButton(selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) InkColors.Accent else Color.Transparent,
            contentColor = if (selected) Color.White else InkColors.Ink
        )
    ) { EraserGlyph(if (selected) Color.White else InkColors.Ink, Modifier.size(24.dp)) }
}

@Composable
private fun EraserGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * .23f, h * .73f), Offset(w * .70f, h * .26f), w * .28f, StrokeCap.Square)
        drawLine(color.copy(alpha = .42f), Offset(w * .59f, h * .37f), Offset(w * .76f, h * .54f), w * .28f, StrokeCap.Square)
        drawLine(color, Offset(w * .12f, h * .87f), Offset(w * .55f, h * .87f), w * .07f, StrokeCap.Round)
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
