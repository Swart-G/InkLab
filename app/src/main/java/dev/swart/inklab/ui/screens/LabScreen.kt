package dev.swart.inklab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.AppContainer
import dev.swart.inklab.core.recognition.ProviderState
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors

@Composable
fun LabScreen(vm: EditorViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(InkColors.Paper).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("OCR Lab", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Сравнение движков на одном выделении", color = InkColors.Muted)
            }
        }
        Spacer(Modifier.height(22.dp))
        Surface(shape = RoundedCornerShape(26.dp), color = InkColors.PaperRaised) {
            Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(18.dp), color = InkColors.AccentSoft) {
                    Icon(Icons.Outlined.Science, null, tint = InkColors.Accent, modifier = Modifier.padding(14.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Текущее выделение", fontWeight = FontWeight.SemiBold)
                    Text("${vm.selectedIds.size} штрихов выбрано", color = InkColors.Muted)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Движки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        AppContainer.recognitionRegistry.all().forEach { p ->
            Surface(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = RoundedCornerShape(20.dp),
                color = InkColors.PaperRaised
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, fontWeight = FontWeight.SemiBold)
                        Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
                    }
                    AssistChip(onClick = {}, label = { Text(p.state(androidx.compose.ui.platform.LocalContext.current).name.lowercase().replace('_', ' ')) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "В следующем шаге BenchmarkRunner должен прогонять сохранённые образцы по всем доступным провайдерам и считать CER/WER/LaTeX edit distance. Архитектура провайдеров уже отделена от редактора.",
            color = InkColors.Muted
        )
    }
}
