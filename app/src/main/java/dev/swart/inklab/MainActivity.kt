package dev.swart.inklab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.swart.inklab.ui.EditorViewModel
import dev.swart.inklab.ui.InkLabApp
import dev.swart.inklab.ui.theme.InkLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorViewModel = viewModel()
            InkLabTheme(darkTheme = vm.inputPreferences.darkTheme) { InkLabApp(vm) }
        }
    }
}
