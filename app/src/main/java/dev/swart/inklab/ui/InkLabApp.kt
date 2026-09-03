package dev.swart.inklab.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.swart.inklab.ui.screens.EditorScreen
import dev.swart.inklab.ui.screens.BoardSettingsScreen
import dev.swart.inklab.ui.screens.BoardsScreen
import dev.swart.inklab.ui.screens.LabScreen
import dev.swart.inklab.ui.screens.SettingsScreen

@Composable
fun InkLabApp(vm: EditorViewModel = viewModel()) {
    when (vm.screen) {
        AppScreen.EDITOR -> EditorScreen(vm)
        AppScreen.BOARDS -> BoardsScreen(vm) { vm.navigate(AppScreen.EDITOR) }
        AppScreen.SETTINGS -> SettingsScreen(vm) { vm.navigate(AppScreen.EDITOR) }
        AppScreen.BOARD_SETTINGS -> BoardSettingsScreen(vm) { vm.navigate(AppScreen.EDITOR) }
        AppScreen.LAB -> LabScreen(vm) { vm.navigate(AppScreen.EDITOR) }
    }
}
