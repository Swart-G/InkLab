package dev.swart.inklab.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object InkColors {
    val Paper = Color(0xFFF5F2EC)
    val PaperRaised = Color(0xFFFBF9F5)
    val Ink = Color(0xFF24262B)
    val Muted = Color(0xFF77766F)
    val Line = Color(0xFFE4DED4)
    val Accent = Color(0xFF6157D9)
    val AccentSoft = Color(0xFFE9E6FF)
    val Mint = Color(0xFFDDF4EA)
    val Rose = Color(0xFFFFE4E6)
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

@Composable
fun InkLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
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
