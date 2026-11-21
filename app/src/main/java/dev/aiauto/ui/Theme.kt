package dev.aiauto.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Light, dark, or follow the phone. */
enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/**
 * The accent used for actions and highlights, with matching containers for light and dark.
 * Each light shade keeps white text on it, and itself as small text on a pill tinted with it, at 4.5:1 or more
 * (WCAG AA). Check a new or changed shade with that rule before adding it.
 */
enum class Accent(
    val label: String,
    val light: Color,
    val lightContainer: Color,
    val onLightContainer: Color,
    val dark: Color,
    val darkContainer: Color,
    val onDarkContainer: Color,
) {
    Clay("Clay", Color(0xFF9F502F), Color(0xFFF3DED3), Color(0xFF5A2A16), Color(0xFFDB8B66), Color(0xFF4A2A1C), Color(0xFFF6DCCF)),
    Ocean("Ocean", Color(0xFF386A9F), Color(0xFFD6E4F3), Color(0xFF14304F), Color(0xFF8DB4E2), Color(0xFF1F3550), Color(0xFFD6E4F3)),
    Forest("Forest", Color(0xFF486F4D), Color(0xFFDCE9D9), Color(0xFF1D3A22), Color(0xFF93BE96), Color(0xFF263D29), Color(0xFFDCE9D9)),
    Plum("Plum", Color(0xFF8A4F7D), Color(0xFFF0DCEB), Color(0xFF3F1838), Color(0xFFCB9BC0), Color(0xFF452A40), Color(0xFFF0DCEB)),
    Slate("Slate", Color(0xFF5B6572), Color(0xFFE1E4E8), Color(0xFF222831), Color(0xFFA9B3BF), Color(0xFF333A43), Color(0xFFE1E4E8)),
}

// A warm, paper-and-clay base: calm enough to glance at, with one accent for actions.
private fun lightScheme(accent: Accent) = lightColorScheme(
    primary = accent.light,
    onPrimary = Color.White,
    primaryContainer = accent.lightContainer,
    onPrimaryContainer = accent.onLightContainer,
    background = Color(0xFFF6F2EA),
    onBackground = Color(0xFF2A2825),
    surface = Color(0xFFFCFAF6),
    onSurface = Color(0xFF2A2825),
    surfaceVariant = Color(0xFFEDE7DC),
    onSurfaceVariant = Color(0xFF6E6960),
    outline = Color(0xFFD9D2C5),
    error = Color(0xFFB3261E),
)

private fun darkScheme(accent: Accent) = darkColorScheme(
    primary = accent.dark,
    onPrimary = Color(0xFF1A1714),
    primaryContainer = accent.darkContainer,
    onPrimaryContainer = accent.onDarkContainer,
    background = Color(0xFF1E1D1B),
    onBackground = Color(0xFFECE8E0),
    surface = Color(0xFF282724),
    onSurface = Color(0xFFECE8E0),
    surfaceVariant = Color(0xFF34322E),
    onSurfaceVariant = Color(0xFFADA79C),
    outline = Color(0xFF45423C),
    error = Color(0xFFF2B8B5),
)

private val Serif = FontFamily.Serif

private val AppType = Typography(
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AiAutoTheme(
    mode: ThemeMode = ThemeMode.System,
    accent: Accent = Accent.Clay,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) darkScheme(accent) else lightScheme(accent),
        typography = AppType,
        shapes = AppShapes,
        content = content,
    )
}
