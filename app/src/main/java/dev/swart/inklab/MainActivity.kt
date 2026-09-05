package dev.swart.inklab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.InkLabApp
import dev.swart.inklab.ui.theme.InkColors
import dev.swart.inklab.ui.theme.InkLabTheme

class MainActivity : ComponentActivity() {
    private var editor: EditorViewModel? = null
    override fun onStop() { editor?.flush(); super.onStop() }
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel = viewModel()
            editor = vm
            val darkTheme = if (vm.inputPreferences.systemTheme) androidx.compose.foundation.isSystemInDarkTheme() else vm.inputPreferences.darkTheme
            InkLabTheme(darkTheme = darkTheme) {
                val palette = dev.swart.inklab.ui.theme.LocalInkPalette.current
                SideEffect {
                    window.statusBarColor = palette.Paper.toArgb()
                    window.navigationBarColor = palette.Paper.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(dev.swart.inklab.ui.theme.LocalNightPaper provides vm.inputPreferences.nightPaper) { InkLabApp(vm) }
            }
        }
    }
}
