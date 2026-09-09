package dev.swart.inklab.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.core.cloud.CloudPreferenceStore
import dev.swart.inklab.core.model.InkBoard

@Composable
fun CloudBackupSettingsDialog(boards: List<InkBoard>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { CloudPreferenceStore(context) }
    var preferences by remember { mutableStateOf(store.load()) }
    val liveBoards = boards.filter { it.deletedAt == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Облачные копии · preview") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Локальное сохранение всегда первично. Preview использует отдельную app-created папку Drive.")
                ToggleRow("Только Wi-Fi", preferences.wifiOnly) { preferences = preferences.copy(wifiOnly = it) }
                ToggleRow("Включать аудио", preferences.includeAudio) { preferences = preferences.copy(includeAudio = it) }
                ToggleRow("Пауза синхронизации", preferences.paused) { preferences = preferences.copy(paused = it) }
                HorizontalDivider()
                Text("Документы", fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(liveBoards, key = { it.id }) { board ->
                        val checked = board.id in preferences.selectedDocumentIds
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { enabled ->
                                val next = preferences.selectedDocumentIds.toMutableSet().apply { if (enabled) add(board.id) else remove(board.id) }
                                preferences = preferences.copy(selectedDocumentIds = next)
                            })
                            Text(board.title, Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { store.save(preferences); onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
