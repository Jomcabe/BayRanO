package com.bayrano.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Mint,
    secondary = Mint,
    background = DeepSurface,
    surface = DeepSurface,
)

private val LightColors = lightColorScheme(
    primary = ForestGreen,
    secondary = ForestGreen,
    background = LightSurface,
    surface = LightSurface,
)

@Composable
fun BayRanOTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
