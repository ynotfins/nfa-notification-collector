package com.nfaalerts.collector.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class RgdsThemeMode(
    val dataTheme: String,
    val displayName: String,
) {
    PrimaryLight("primary-light", "NFA Light"),
    SecondaryLight("secondary-light", "Light-Orange"),
    PrimaryDark("primary-dark", "Dark"),
    SecondaryDark("secondary-dark", "Dark-Pink"),
    ;

    companion object {
        fun fromDataTheme(value: String): RgdsThemeMode = entries.firstOrNull { it.dataTheme == value } ?: PrimaryLight
    }
}

data class RgdsSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

data class RgdsSpacing(
    val xxxs: Dp = 2.dp,
    val xxs: Dp = 4.dp,
    val xs: Dp = 6.dp,
    val sm: Dp = 10.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 18.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 30.dp,
    val cardGap: Dp = 12.dp,
    val metadataGap: Dp = 8.dp,
    val touchTarget: Dp = 48.dp,
    val buttonXl: Dp = 56.dp,
    val iconXl: Dp = 24.dp,
    val iconHuge: Dp = 64.dp,
    val cardMinHeight: Dp = 160.dp,
)

data class RgdsElevation(
    val none: Dp = 0.dp,
    val subtle: Dp = 1.dp,
    val card: Dp = 4.dp,
    val navigation: Dp = 12.dp,
    val floating: Dp = 18.dp,
)

private data class RgdsThemeTokens(
    val colorScheme: androidx.compose.material3.ColorScheme,
    val semanticColors: RgdsSemanticColors,
)

private val sharedSemanticColors =
    RgdsSemanticColors(
        success = Color(0xFF16A34A),
        onSuccess = Color(0xFFFFFFFF),
        successContainer = Color(0xFFD3EDD6),
        onSuccessContainer = Color(0xFF001805),
        warning = Color(0xFFF97316),
        onWarning = Color(0xFF0F172A),
        warningContainer = Color(0xFFFFDCCB),
        onWarningContainer = Color(0xFF0F172A),
        info = Color(0xFF2563EB),
        onInfo = Color(0xFFFFFFFF),
        infoContainer = Color(0xFFD7E5FF),
        onInfoContainer = Color(0xFF000213),
    )

private val themeTokens =
    mapOf(
        RgdsThemeMode.PrimaryLight to
            RgdsThemeTokens(
                colorScheme =
                    lightColorScheme(
                        primary = Color(0xFF2563EB),
                        onPrimary = Color(0xFFFFFFFF),
                        primaryContainer = Color(0xFFD7E5FF),
                        onPrimaryContainer = Color(0xFF000213),
                        secondary = Color(0xFFEF4444),
                        onSecondary = Color(0xFFFFFFFF),
                        secondaryContainer = Color(0xFFFED2CD),
                        onSecondaryContainer = Color(0xFF2A0002),
                        tertiary = Color(0xFF16A34A),
                        onTertiary = Color(0xFFFFFFFF),
                        tertiaryContainer = Color(0xFFD3EDD6),
                        onTertiaryContainer = Color(0xFF001805),
                        error = Color(0xFFDC2626),
                        onError = Color(0xFFFFFFFF),
                        errorContainer = Color(0xFFFED2CD),
                        onErrorContainer = Color(0xFF2A0002),
                        surface = Color(0xFFFFFFFF),
                        onSurface = Color(0xFF0F172A),
                        surfaceContainer = Color(0xFFF1F2F3),
                        surfaceContainerHigh = Color(0xFFEAEBEC),
                        surfaceContainerHighest = Color(0xFFE3E4E6),
                        outline = Color(0xFF71757C),
                        outlineVariant = Color(0xFFC8CACE),
                    ),
                semanticColors = sharedSemanticColors,
            ),
        RgdsThemeMode.SecondaryLight to
            RgdsThemeTokens(
                colorScheme =
                    lightColorScheme(
                        primary = Color(0xFFF97316),
                        onPrimary = Color(0xFF0F172A),
                        primaryContainer = Color(0xFFFFDCCB),
                        onPrimaryContainer = Color(0xFF7B3300),
                        secondary = Color(0xFFA14C00),
                        onSecondary = Color(0xFFFFFFFF),
                        secondaryContainer = Color(0xFFFEEEE6),
                        onSecondaryContainer = Color(0xFF7B3300),
                        tertiary = Color(0xFF2563EB),
                        onTertiary = Color(0xFFFFFFFF),
                        tertiaryContainer = Color(0xFFD3EDD6),
                        onTertiaryContainer = Color(0xFF16A34A),
                        error = Color(0xFFDC2626),
                        onError = Color(0xFFFFFFFF),
                        errorContainer = Color(0xFFFED2CD),
                        onErrorContainer = Color(0xFF2A0002),
                        surface = Color(0xFFFEEEE6),
                        onSurface = Color(0xFF0F172A),
                        surfaceContainer = Color(0xFFFFFFFF),
                        surfaceContainerHigh = Color(0xFFF8F8F9),
                        surfaceContainerHighest = Color(0xFFF1F2F3),
                        outline = Color(0xFFA14C00),
                        outlineVariant = Color(0xFFFFCAAF),
                    ),
                semanticColors = sharedSemanticColors,
            ),
        RgdsThemeMode.PrimaryDark to
            RgdsThemeTokens(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFFA6C4FF),
                        onPrimary = Color(0xFF020306),
                        primaryContainer = Color(0xFF212429),
                        onPrimaryContainer = Color(0xFFA6C4FF),
                        secondary = Color(0xFFC9CACD),
                        onSecondary = Color(0xFF020306),
                        secondaryContainer = Color(0xFF15181E),
                        onSecondaryContainer = Color(0xFFE3E4E6),
                        tertiary = Color(0xFFA0D3A8),
                        onTertiary = Color(0xFF020306),
                        tertiaryContainer = Color(0xFFCDE6D0),
                        onTertiaryContainer = Color(0xFF001805),
                        error = Color(0xFFDC2626),
                        onError = Color(0xFFFFFFFF),
                        errorContainer = Color(0xFFFED2CD),
                        onErrorContainer = Color(0xFF2A0002),
                        surface = Color(0xFF020306),
                        onSurface = Color(0xFFFFFFFF),
                        surfaceContainer = Color(0xFF0B0D13),
                        surfaceContainerHigh = Color(0xFF0F1217),
                        surfaceContainerHighest = Color(0xFF15181E),
                        outline = Color(0xFF909296),
                        outlineVariant = Color(0xFF212429),
                    ),
                semanticColors = sharedSemanticColors,
            ),
        RgdsThemeMode.SecondaryDark to
            RgdsThemeTokens(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFFF97316),
                        onPrimary = Color(0xFF000000),
                        primaryContainer = Color(0xFF7B3300),
                        onPrimaryContainer = Color(0xFFFFCAAF),
                        secondary = Color(0xFFA14C00),
                        onSecondary = Color(0xFFFFFFFF),
                        secondaryContainer = Color(0xFF040404),
                        onSecondaryContainer = Color(0xFFFFCAAF),
                        tertiary = Color(0xFFA6C4FF),
                        onTertiary = Color(0xFF000000),
                        tertiaryContainer = Color(0xFF16A34A),
                        onTertiaryContainer = Color(0xFFD3EDD6),
                        error = Color(0xFFDC2626),
                        onError = Color(0xFFFFFFFF),
                        errorContainer = Color(0xFFFED2CD),
                        onErrorContainer = Color(0xFF2A0002),
                        surface = Color(0xFF000000),
                        onSurface = Color(0xFFFFFFFF),
                        surfaceContainer = Color(0xFF020202),
                        surfaceContainerHigh = Color(0xFF040404),
                        surfaceContainerHighest = Color(0xFF090909),
                        outline = Color(0xFFD06100),
                        outlineVariant = Color(0xFF040404),
                    ),
                semanticColors = sharedSemanticColors,
            ),
    )

private val rgdsTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontSize = 26.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
            ),
        headlineMedium =
            TextStyle(
                fontSize = 23.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.15).sp,
            ),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
        bodyMedium =
            TextStyle(
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
            ),
        bodySmall =
            TextStyle(
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.1.sp,
            ),
        labelLarge =
            TextStyle(
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            ),
        labelSmall =
            TextStyle(
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.35.sp,
            ),
    )

private val rgdsShapes =
    Shapes(
        extraSmall = RoundedCornerShape(13.dp),
        small = RoundedCornerShape(18.dp),
        medium = RoundedCornerShape(24.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(30.dp),
    )

private val LocalRgdsMode = staticCompositionLocalOf { RgdsThemeMode.PrimaryLight }
private val LocalRgdsColors = staticCompositionLocalOf { sharedSemanticColors }
private val LocalRgdsSpacing = staticCompositionLocalOf { RgdsSpacing() }
private val LocalRgdsElevation = staticCompositionLocalOf { RgdsElevation() }

object RgdsTheme {
    val mode: RgdsThemeMode
        @Composable get() = LocalRgdsMode.current
    val colors: RgdsSemanticColors
        @Composable get() = LocalRgdsColors.current
    val spacing: RgdsSpacing
        @Composable get() = LocalRgdsSpacing.current
    val elevation: RgdsElevation
        @Composable get() = LocalRgdsElevation.current
}

@Composable
fun NfaCollectorTheme(
    mode: RgdsThemeMode = RgdsThemeMode.PrimaryLight,
    content: @Composable () -> Unit,
) {
    val tokens = themeTokens.getValue(mode)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalRgdsMode provides mode,
        LocalRgdsColors provides tokens.semanticColors,
        LocalRgdsSpacing provides RgdsSpacing(),
        LocalRgdsElevation provides RgdsElevation(),
    ) {
        MaterialTheme(
            colorScheme = tokens.colorScheme,
            typography = rgdsTypography,
            shapes = rgdsShapes,
            content = content,
        )
    }
}
