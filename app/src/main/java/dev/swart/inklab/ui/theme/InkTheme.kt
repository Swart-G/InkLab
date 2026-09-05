package dev.swart.inklab.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class InkPalette(
    val Paper: Color,
    val PaperRaised: Color,
    val Ink: Color,
    val Muted: Color,
    val Line: Color,
    val Accent: Color,
    val AccentSoft: Color,
    val Mint: Color,
    val Rose: Color,
    val dark: Boolean = false
)
private val LightPalette = InkPalette(Color(0xFFF5F2EC), Color(0xFFFBF9F5), Color(0xFF24262B), Color(0xFF77766F), Color(0xFFE4DED4), Color(0xFF6157D9), Color(0xFFE9E6FF), Color(0xFFDDF4EA), Color(0xFFFFE4E6))
private val DarkPalette = InkPalette(Color(0xFF17181C), Color(0xFF222329), Color(0xFFF2F0EB), Color(0xFFA8A6A0), Color(0xFF373840), Color(0xFF9A91FF), Color(0xFF34304F), Color(0xFF21443A), Color(0xFF5A3038), dark = true)
val LocalNightPaper = androidx.compose.runtime.staticCompositionLocalOf { true }
val LocalInkPalette = androidx.compose.runtime.staticCompositionLocalOf { LightPalette }
object InkColors {
    val Paper: Color @Composable get() = LocalInkPalette.current.Paper
    val PaperRaised: Color @Composable get() = LocalInkPalette.current.PaperRaised
    val Ink: Color @Composable get() = LocalInkPalette.current.Ink
    val Muted: Color @Composable get() = LocalInkPalette.current.Muted
    val Line: Color @Composable get() = LocalInkPalette.current.Line
    val Accent: Color @Composable get() = LocalInkPalette.current.Accent
    val AccentSoft: Color @Composable get() = LocalInkPalette.current.AccentSoft
    val Mint: Color @Composable get() = LocalInkPalette.current.Mint
    val Rose: Color @Composable get() = LocalInkPalette.current.Rose
}

fun paperDisplayColor(color: Color, night: Boolean): Color =
    if (night && neutralColor(color) && color.red > 0.5f) Color(0xFF25262B) else color
fun inkDisplayColor(color: Color, paper: Color, night: Boolean): Color =
    if (night && neutralColor(color) && color.red < 0.5f && paper.red < 0.5f) Color(0xFFEAE8E2) else color
private fun neutralColor(color: Color) = maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) < 0.12f

private fun lightScheme(palette: InkPalette) = lightColorScheme(
    primary = palette.Accent,
    onPrimary = Color.White,
    primaryContainer = palette.AccentSoft,
    onPrimaryContainer = palette.Ink,
    background = palette.Paper,
    onBackground = palette.Ink,
    surface = palette.PaperRaised,
    onSurface = palette.Ink,
    surfaceVariant = palette.PaperRaised,
    onSurfaceVariant = palette.Ink,
    outline = palette.Line
)

private fun darkScheme(palette: InkPalette) = darkColorScheme(
    primary = palette.Accent,
    onPrimary = Color(0xFF17131F),
    primaryContainer = palette.AccentSoft,
    onPrimaryContainer = palette.Ink,
    background = palette.Paper,
    onBackground = palette.Ink,
    surface = palette.PaperRaised,
    onSurface = palette.Ink,
    surfaceVariant = palette.PaperRaised,
    onSurfaceVariant = palette.Ink,
    outline = palette.Line
)

@Composable
fun InkLabTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    androidx.compose.runtime.CompositionLocalProvider(LocalInkPalette provides palette) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme(palette) else lightScheme(palette),
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
        content = { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides palette.Ink) { content() } }
    )
}
}
