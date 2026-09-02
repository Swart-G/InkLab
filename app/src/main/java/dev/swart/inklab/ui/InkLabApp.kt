package dev.swart.inklab.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.swart.inklab.ui.screens.EditorScreen
import dev.swart.inklab.ui.screens.LabScreen
import dev.swart.inklab.ui.screens.SettingsScreen

@Composable
fun InkLabApp(vm: EditorViewModel = viewModel()) {
    when {
        vm.showSettings -> SettingsScreen(vm) { vm.showSettings = false }
        vm.showLab -> LabScreen(vm) { vm.showLab = false }
        else -> EditorScreen(vm)
    }
}
