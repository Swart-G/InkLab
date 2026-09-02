package dev.swart.inklab.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.swart.inklab.AppContainer
import dev.swart.inklab.core.recognition.ProviderState
import dev.swart.inklab.core.recognition.RecognitionMode
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.theme.InkColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(InkColors.Paper).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null) }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Распознавание", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Выберите движки отдельно для текста и формул", color = InkColors.Muted)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ProviderColumn(
                title = "Рукописный текст",
                mode = RecognitionMode.TEXT,
                selected = vm.textProviderId,
                preparing = preparing,
                onSelect = { vm.textProviderId = it },
                onPrepare = { id ->
                    val p = AppContainer.recognitionRegistry.get(id)
                    if (p != null) {
                        preparing = id
                        scope.launch { p.prepare(context); preparing = null }
                    }
                },
                modifier = Modifier.weight(1f)
            )
            ProviderColumn(
                title = "Формулы",
                mode = RecognitionMode.MATH,
                selected = vm.mathProviderId,
                preparing = preparing,
                onSelect = { vm.mathProviderId = it },
                onPrepare = {},
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProviderColumn(
    title: String,
    mode: RecognitionMode,
    selected: String,
    preparing: String?,
    onSelect: (String) -> Unit,
    onPrepare: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        AppContainer.recognitionRegistry.compatible(mode).forEach { provider ->
            val state = provider.state(context)
            val isSelected = selected == provider.id
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { onSelect(provider.id) },
                shape = RoundedCornerShape(22.dp),
                color = if (isSelected) InkColors.AccentSoft else InkColors.PaperRaised,
                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, InkColors.Accent.copy(alpha = .45f)) else null
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(provider.displayName, fontWeight = FontWeight.SemiBold)
                            if (isSelected) {
                                Spacer(Modifier.width(7.dp))
                                Icon(Icons.Outlined.CheckCircle, null, tint = InkColors.Accent, modifier = Modifier.size(17.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(provider.subtitle, style = MaterialTheme.typography.bodySmall, color = InkColors.Muted)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when (state) {
                                ProviderState.READY -> "Готов"
                                ProviderState.MODEL_REQUIRED -> if (provider.id == "mlkit-ru") "Модель загружается по требованию" else "Нужны локальные веса"
                                ProviderState.SDK_REQUIRED -> "Нужно подключить SDK"
                                ProviderState.UNAVAILABLE -> "Недоступен"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state == ProviderState.READY) Color(0xFF2A7A58) else InkColors.Muted
                        )
                    }
                    if (provider.id == "mlkit-ru") {
                        IconButton(onClick = { onPrepare(provider.id) }) {
                            if (preparing == provider.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Download, "Загрузить модель")
                        }
                    }
                }
            }
        }
    }
}
