package com.frameender.pocketstash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.frameender.pocketstash.R

/** A selectable highlight color. [dim] is used for containers like selected chips. */
data class Accent(val key: String, val label: String, val main: Color, val dim: Color, val on: Color = Color(0xFF15110C))

private fun accent(key: String, label: String, hex: Long): Accent {
    val main = Color(hex)
    // Dim = the accent mixed 45% into the ink background.
    val bg = Color(0xFF0E0F12)
    val dim = Color(
        red = bg.red + (main.red - bg.red) * 0.45f,
        green = bg.green + (main.green - bg.green) * 0.45f,
        blue = bg.blue + (main.blue - bg.blue) * 0.45f,
    )
    return Accent(key, label, main, dim)
}

object Accents {
    val all = listOf(
        accent("amber", "Amber", 0xFFF2A93B),
        accent("sakura", "Sakura", 0xFFF48FB1),
        accent("coral", "Coral", 0xFFFF8A65),
        accent("crimson", "Crimson", 0xFFEF5D5D),
        accent("gold", "Gold", 0xFFE8C547),
        accent("lime", "Lime", 0xFFA8D65C),
        accent("mint", "Mint", 0xFF6FD6B0),
        accent("teal", "Teal", 0xFF4FC3C0),
        accent("sky", "Sky", 0xFF6FB6F5),
        accent("periwinkle", "Periwinkle", 0xFF8C9EFF),
        accent("lavender", "Lavender", 0xFFB39DFF),
        accent("orchid", "Orchid", 0xFFD98CF0),
        accent("rose", "Rose", 0xFFFF6F91),
        accent("tangerine", "Tangerine", 0xFFFFA24C),
        accent("seafoam", "Seafoam", 0xFF8EE3C8),
        accent("silver", "Silver", 0xFFC9CED6),
    )

    private val state = mutableStateOf(all.first())

    /** Read during composition, so changing it recomposes everything that uses it. */
    val current: Accent get() = state.value

    fun byKey(key: String?): Accent = all.firstOrNull { it.key == key } ?: all.first()
    fun select(key: String?) {
        val next = byKey(key)
        if (state.value != next) state.value = next
    }
}

// Workshop palette: ink surfaces, user-selectable accent.
object Ink {
    val Bg = Color(0xFF0E0F12)
    val Surface = Color(0xFF16181D)
    val Raised = Color(0xFF1F2229)
    val Line = Color(0xFF2C3039)
    val Text = Color(0xFFECE7DF)
    val Muted = Color(0xFF9A958C)
    val Red = Color(0xFFE5534B)
    val Green = Color(0xFF5BBF7A)
    val Teal = Color(0xFF7DB8B5)
    val Violet = Color(0xFFB394E8)
    val Blue = Color(0xFF6FA8F0)
    val Gold = Color(0xFFE6C15A)
    val Surface3 = Color(0xFF282C35)

    // The accent. Name kept as "Amber" so every screen follows the picker.
    val Amber: Color get() = Accents.current.main
    val AmberDim: Color get() = Accents.current.dim
    val OnAmber: Color get() = Accents.current.on
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
    val a = Accents.current
    val scheme = darkColorScheme(
        primary = a.main,
        onPrimary = a.on,
        primaryContainer = a.dim,
        onPrimaryContainer = Ink.Text,
        secondary = a.main,
        onSecondary = a.on,
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
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
