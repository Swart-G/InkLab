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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.model.InkBoard
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors
import java.text.DateFormat
import java.util.Date

@Composable
fun BoardsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(InkColors.Paper)) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Назад") }
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Доски", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Все конспекты хранятся локально", color = InkColors.Muted)
                }
            }
            Spacer(Modifier.height(22.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(230.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(vm.boards, key = { it.id }) { board ->
                    BoardCard(
                        board = board,
                        active = board.id == vm.currentBoardId,
                        canDelete = vm.boards.size > 1,
                        onOpen = { vm.openBoard(board.id) },
                        onDelete = { vm.deleteBoard(board.id) }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { vm.createBoard() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp),
            containerColor = InkColors.Accent,
            contentColor = androidx.compose.ui.graphics.Color.White
        ) { Icon(Icons.Outlined.Add, "Создать доску") }
    }
}

@Composable
private fun BoardCard(
    board: InkBoard,
    active: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(26.dp),
        color = if (active) InkColors.AccentSoft else InkColors.PaperRaised,
        shadowElevation = if (active) 1.dp else 0.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().aspectRatio(1.45f),
                shape = RoundedCornerShape(18.dp),
                color = androidx.compose.ui.graphics.Color(board.settings.paperColor)
            ) {
                Box(Modifier.padding(16.dp)) {
                    Icon(Icons.Outlined.Draw, null, tint = InkColors.Accent.copy(alpha = 0.55f), modifier = Modifier.size(32.dp))
                    Text(
                        "${board.strokes.size} штрихов",
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
                        board.subject.ifBlank { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(board.updatedAt)) },
                        color = InkColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                if (canDelete) IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Удалить", tint = InkColors.Muted) }
            }
        }
    }
}
