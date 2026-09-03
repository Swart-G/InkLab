package dev.swart.inklab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.InkLabApp
import dev.swart.inklab.ui.theme.InkLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel = viewModel()
            val darkTheme = vm.inputPreferences.darkTheme
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            InkLabTheme(darkTheme = darkTheme) { InkLabApp(vm) }
        }
    }
}
