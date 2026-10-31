package app.voicedrop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF0A7D68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF3E6),
    onPrimaryContainer = Color(0xFF00382D),
    secondary = Color(0xFF4A635C),
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFFE2553A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD1),
    onTertiaryContainer = Color(0xFF3D0700),
    background = Color(0xFFF6F8F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFDCE5E1),
    onSurfaceVariant = Color(0xFF59625F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F6F5),
    surfaceContainer = Color(0xFFEDF1EF),
    surfaceContainerHigh = Color(0xFFE7ECEA),
    surfaceContainerHighest = Color(0xFFE1E7E4),
    outlineVariant = Color(0xFFD0D8D4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4FD8B4),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF0D4D40),
    onPrimaryContainer = Color(0xFFCFF3E6),
    secondary = Color(0xFFB0CCC3),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFFF8C70),
    onTertiary = Color(0xFF5E1100),
    tertiaryContainer = Color(0xFF7D2A17),
    onTertiaryContainer = Color(0xFFFFDAD1),
    background = Color(0xFF0C1110),
    surface = Color(0xFF0F1513),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFA9B4AF),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF141B19),
    surfaceContainer = Color(0xFF18201E),
    surfaceContainerHigh = Color(0xFF1F2826),
    surfaceContainerHighest = Color(0xFF283230),
    outlineVariant = Color(0xFF3A4441),
)

@Immutable
data class ChatColors(
    val wallpaper: Color,
    val outgoing: Color,
    val onOutgoing: Color,
    val incoming: Color,
    val onIncoming: Color,
    val meta: Color,
    val read: Color,
    val quoteOut: Color,
    val quoteIn: Color,
)

private val LightChat = ChatColors(
    wallpaper = Color(0xFFEFEAE2),
    outgoing = Color(0xFFD7F7E6),
    onOutgoing = Color(0xFF111B21),
    incoming = Color.White,
    onIncoming = Color(0xFF111B21),
    meta = Color(0xFF667781),
    read = Color(0xFF27A3E8),
    quoteOut = Color(0x14000000),
    quoteIn = Color(0x0D000000),
)

private val DarkChat = ChatColors(
    wallpaper = Color(0xFF0B1210),
    outgoing = Color(0xFF115443),
    onOutgoing = Color(0xFFE9EDEF),
    incoming = Color(0xFF1C2523),
    onIncoming = Color(0xFFE9EDEF),
    meta = Color(0xFF9AA8A3),
    read = Color(0xFF53BDEB),
    quoteOut = Color(0x29000000),
    quoteIn = Color(0x1FFFFFFF),
)

val LocalChatColors = staticCompositionLocalOf { LightChat }

/** Stable per-person colors for group sender names and avatar fallbacks. */
val nameColors = listOf(
    Color(0xFFE2553A), Color(0xFF1F8FD6), Color(0xFF9B51E0), Color(0xFF0A9D72),
    Color(0xFFD4881A), Color(0xFFD63F86), Color(0xFF2E9AA6), Color(0xFF6E7BD9),
)

fun colorFor(key: String): Color = nameColors[(key.hashCode() and Int.MAX_VALUE) % nameColors.size]

private val type = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp),
    )
}

val MetaText = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)

@Composable
fun VoiceDropTheme(dynamic: ColorScheme? = null, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = dynamic ?: if (dark) Dark else Light
    CompositionLocalProvider(LocalChatColors provides if (dark) DarkChat else LightChat) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
