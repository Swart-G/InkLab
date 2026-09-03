package dev.swart.inklab.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object InkColors {
    var Paper = Color(0xFFF5F2EC)
    var PaperRaised = Color(0xFFFBF9F5)
    var Ink = Color(0xFF24262B)
    var Muted = Color(0xFF77766F)
    var Line = Color(0xFFE4DED4)
    var Accent = Color(0xFF6157D9)
    var AccentSoft = Color(0xFFE9E6FF)
    var Mint = Color(0xFFDDF4EA)
    var Rose = Color(0xFFFFE4E6)

    fun useDark(enabled: Boolean) {
        Paper = if (enabled) Color(0xFF17181C) else Color(0xFFF5F2EC)
        PaperRaised = if (enabled) Color(0xFF222329) else Color(0xFFFBF9F5)
        Ink = if (enabled) Color(0xFFF2F0EB) else Color(0xFF24262B)
        Muted = if (enabled) Color(0xFFA8A6A0) else Color(0xFF77766F)
        Line = if (enabled) Color(0xFF373840) else Color(0xFFE4DED4)
        Accent = if (enabled) Color(0xFF9A91FF) else Color(0xFF6157D9)
        AccentSoft = if (enabled) Color(0xFF34304F) else Color(0xFFE9E6FF)
        Mint = if (enabled) Color(0xFF21443A) else Color(0xFFDDF4EA)
        Rose = if (enabled) Color(0xFF5A3038) else Color(0xFFFFE4E6)
    }
}

private val Scheme = lightColorScheme(
    primary = InkColors.Accent,
    onPrimary = Color.White,
    primaryContainer = InkColors.AccentSoft,
    onPrimaryContainer = InkColors.Ink,
    background = InkColors.Paper,
    onBackground = InkColors.Ink,
    surface = InkColors.PaperRaised,
    onSurface = InkColors.Ink,
    outline = InkColors.Line
)

private fun darkScheme() = darkColorScheme(
    primary = InkColors.Accent,
    onPrimary = Color(0xFF17131F),
    primaryContainer = InkColors.AccentSoft,
    onPrimaryContainer = InkColors.Ink,
    background = InkColors.Paper,
    onBackground = InkColors.Ink,
    surface = InkColors.PaperRaised,
    onSurface = InkColors.Ink,
    outline = InkColors.Line
)

@Composable
fun InkLabTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    InkColors.useDark(darkTheme)
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme() else Scheme,
        typography = Typography(
            headlineLarge = Typography().headlineLarge.copy(letterSpacing = (-0.8).sp),
            titleLarge = Typography().titleLarge.copy(letterSpacing = (-0.25).sp)
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp)
        ),
        content = content
    )
}
