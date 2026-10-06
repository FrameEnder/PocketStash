package com.frameender.pocketstash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.frameender.pocketstash.R

// Workshop palette: ink surfaces, amber accent.
object Ink {
    val Bg = Color(0xFF0E0F12)
    val Surface = Color(0xFF16181D)
    val Raised = Color(0xFF1F2229)
    val Line = Color(0xFF2C3039)
    val Text = Color(0xFFECE7DF)
    val Muted = Color(0xFF9A958C)
    val Amber = Color(0xFFF2A93B)
    val AmberDim = Color(0xFF8A5F1E)
    val Red = Color(0xFFE5534B)
    val Green = Color(0xFF5BBF7A)
}

val Grotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Light),
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold),
)

private val scheme = darkColorScheme(
    primary = Ink.Amber,
    onPrimary = Ink.Bg,
    primaryContainer = Ink.AmberDim,
    onPrimaryContainer = Ink.Text,
    secondary = Ink.Amber,
    onSecondary = Ink.Bg,
    secondaryContainer = Ink.Raised,
    onSecondaryContainer = Ink.Text,
    tertiary = Ink.Green,
    background = Ink.Bg,
    onBackground = Ink.Text,
    surface = Ink.Bg,
    onSurface = Ink.Text,
    surfaceVariant = Ink.Raised,
    onSurfaceVariant = Ink.Muted,
    surfaceContainerLowest = Ink.Bg,
    surfaceContainerLow = Ink.Surface,
    surfaceContainer = Ink.Surface,
    surfaceContainerHigh = Ink.Raised,
    surfaceContainerHighest = Ink.Raised,
    outline = Ink.Line,
    outlineVariant = Ink.Line,
    error = Ink.Red,
)

private fun s(size: Int, weight: FontWeight, family: FontFamily = Grotesk, line: Int = (size * 1.3).toInt()) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val typography = Typography(
    displaySmall = s(32, FontWeight.Bold),
    headlineMedium = s(26, FontWeight.Bold),
    headlineSmall = s(22, FontWeight.SemiBold),
    titleLarge = s(20, FontWeight.SemiBold),
    titleMedium = s(16, FontWeight.SemiBold),
    titleSmall = s(14, FontWeight.Medium),
    bodyLarge = s(16, FontWeight.Normal),
    bodyMedium = s(14, FontWeight.Normal),
    bodySmall = s(12, FontWeight.Normal),
    labelLarge = s(14, FontWeight.Medium),
    labelMedium = s(12, FontWeight.Medium, Mono),
    labelSmall = s(11, FontWeight.Medium, Mono),
)

@Composable
fun PocketStashTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
