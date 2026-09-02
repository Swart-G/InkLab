package dev.swart.inklab.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.recognition.RecognitionMode
import dev.swart.inklab.ui.EditorTool
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.components.GlassPanel
import dev.swart.inklab.ui.theme.InkColors

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(InkColors.Paper)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(vm)
            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
                Surface(
                    Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(30.dp),
                    color = InkColors.PaperRaised,
                    tonalElevation = 0.dp,
                    shadowElevation = 2.dp
                ) { EditorCanvas(vm, Modifier.fillMaxSize()) }

                ToolDock(vm, Modifier.align(Alignment.CenterStart).padding(start = 14.dp))

                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)) {
                    AnimatedVisibility(
                        visible = vm.selectedIds.isNotEmpty(),
                        enter = fadeIn() + scaleIn(initialScale = 0.92f),
                        exit = fadeOut() + scaleOut(targetScale = 0.92f)
                    ) {
                        SelectionBar(
                            onText = { vm.recognize(context, RecognitionMode.TEXT) },
                            onMath = { vm.recognize(context, RecognitionMode.MATH) },
                            onCompare = { vm.showLab = true }
                        )
                    }
                }
            }
        }

        vm.recognition?.let { state -> RecognitionDialog(vm, state.mode) }
    }
}

@Composable
private fun TopBar(vm: EditorViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("InkLab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("OCR playground · лекция 01", style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
        }
        AssistChip(onClick = { vm.undoErase() }, label = { Text("Undo") }, leadingIcon = { Icon(Icons.Outlined.Undo, null) })
        Spacer(Modifier.width(10.dp))
        IconButton(onClick = { vm.showLab = true }, modifier = Modifier.background(InkColors.AccentSoft, CircleShape)) {
            Icon(Icons.Outlined.Science, null, tint = InkColors.Accent)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { vm.showSettings = true }, modifier = Modifier.background(InkColors.PaperRaised, CircleShape)) {
            Icon(Icons.Outlined.Tune, null)
        }
    }
}

@Composable
private fun ToolDock(vm: EditorViewModel, modifier: Modifier = Modifier) {
    GlassPanel(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ToolButton(Icons.Outlined.Draw, "Перо", vm.tool == EditorTool.PEN) { vm.tool = EditorTool.PEN }
            ToolButton(Icons.Outlined.AutoFixOff, "Ластик", vm.tool == EditorTool.ERASER) { vm.tool = EditorTool.ERASER }
            ToolButton(Icons.Outlined.Gesture, "Лассо", vm.tool == EditorTool.LASSO) { vm.tool = EditorTool.LASSO }
            HorizontalDivider(Modifier.width(34.dp).padding(vertical = 6.dp), color = InkColors.Line)
            IconButton(onClick = { vm.penWidth = when {
                vm.penWidth < 4f -> 5f
                vm.penWidth < 7f -> 8f
                else -> 3f
            } }) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(vm.penWidth.dp.coerceAtMost(12.dp)).background(InkColors.Ink, CircleShape))
                }
            }
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
private fun SelectionBar(onText: () -> Unit, onMath: () -> Unit, onCompare: () -> Unit) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onText, colors = ButtonDefaults.filledTonalButtonColors(containerColor = InkColors.AccentSoft)) {
                Icon(Icons.Outlined.TextFields, null); Spacer(Modifier.width(8.dp)); Text("В текст")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onMath, colors = ButtonDefaults.filledTonalButtonColors(containerColor = InkColors.Mint)) {
                Icon(Icons.Outlined.Functions, null); Spacer(Modifier.width(8.dp)); Text("Формула")
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCompare) { Icon(Icons.Outlined.CompareArrows, "Сравнить") }
        }
    }
}

@Composable
private fun RecognitionDialog(vm: EditorViewModel, mode: RecognitionMode) {
    val state = vm.recognition ?: return
    AlertDialog(
        onDismissRequest = { vm.recognition = null },
        icon = { Icon(if (mode == RecognitionMode.TEXT) Icons.Outlined.TextFields else Icons.Outlined.Functions, null) },
        title = { Text(if (mode == RecognitionMode.TEXT) "Распознанный текст" else "Распознанная формула") },
        text = {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text("Локальное распознавание…")
                }
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                state.result != null -> Column {
                    Text(state.result.primary.ifBlank { "Нет результата" }, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("${state.result.latencyMs} мс · ${state.result.providerId}", color = InkColors.Muted, style = MaterialTheme.typography.bodySmall)
                    state.result.note?.let { Text(it, color = InkColors.Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.recognition = null }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = { vm.recognition = null }) { Text("Закрыть") } }
    )
}
