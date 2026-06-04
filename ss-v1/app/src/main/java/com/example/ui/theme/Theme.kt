package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary            = Brand600,
    onPrimary          = Neutral50,
    primaryContainer   = Brand100,
    onPrimaryContainer = Brand900,
    secondary          = Neutral600,
    onSecondary        = Neutral50,
    secondaryContainer = Neutral150,
    onSecondaryContainer = Neutral900,
    tertiary           = Accent500,
    onTertiary         = Neutral50,
    tertiaryContainer  = Accent100,
    onTertiaryContainer = Brand900,
    error              = Error500,
    onError            = Neutral50,
    errorContainer     = Error100,
    onErrorContainer   = Error500,
    background         = Neutral50,
    onBackground       = Neutral950,
    surface            = Neutral50,
    onSurface          = Neutral950,
    surfaceVariant     = Neutral100,
    onSurfaceVariant   = Neutral600,
    outline            = Neutral300,
    outlineVariant     = Neutral200,
    inverseSurface     = Neutral900,
    inverseOnSurface   = Neutral100,
    inversePrimary     = Brand200,
)

private val DarkColorScheme = darkColorScheme(
    primary            = Brand300,
    onPrimary          = Brand900,
    primaryContainer   = Brand800,
    onPrimaryContainer = Brand100,
    secondary          = Neutral300,
    onSecondary        = Neutral900,
    secondaryContainer = Neutral800,
    onSecondaryContainer = Neutral100,
    tertiary           = Accent400,
    onTertiary         = Neutral950,
    tertiaryContainer  = Accent500,
    onTertiaryContainer = Neutral50,
    error              = Error100,
    onError            = Error500,
    errorContainer     = Error500,
    onErrorContainer   = Error100,
    background         = Neutral950,
    onBackground       = Neutral100,
    surface            = Neutral900,
    onSurface          = Neutral100,
    surfaceVariant     = Neutral800,
    onSurfaceVariant   = Neutral300,
    outline            = Neutral600,
    outlineVariant     = Neutral700,
    inverseSurface     = Neutral150,
    inverseOnSurface   = Neutral900,
    inversePrimary     = Brand600,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // disabled — use brand colors
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}
