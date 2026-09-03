package dev.swart.inklab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.swart.inklab.ui.theme.InkColors
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.swart.inklab.ui.screens.EditorScreen
import dev.swart.inklab.ui.screens.BoardSettingsScreen
import dev.swart.inklab.ui.screens.BoardsScreen
import dev.swart.inklab.ui.screens.SettingsScreen

@Composable
fun InkLabApp(vm: EditorViewModel = viewModel()) {
    Box(Modifier.fillMaxSize().background(InkColors.Paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "screen"
        ) { screen ->
            when (screen) {
                AppScreen.EDITOR -> EditorScreen(vm)
                AppScreen.BOARDS -> BoardsScreen(vm) { vm.navigate(AppScreen.EDITOR) }
                AppScreen.SETTINGS -> SettingsScreen(vm) { vm.navigate(AppScreen.EDITOR) }
                AppScreen.BOARD_SETTINGS -> BoardSettingsScreen(vm) { vm.navigate(AppScreen.EDITOR) }
            }
        }
    }
}
