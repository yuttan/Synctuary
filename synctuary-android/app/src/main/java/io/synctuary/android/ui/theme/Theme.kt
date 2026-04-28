package io.synctuary.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The Synctuary M3 theme.
 *
 * Default is **dark** (per the mockup); a light variant is provided so the
 * "follow system" path works for users who prefer it. Light tokens are kept
 * roughly aligned with the dark palette but with inverted lightness, not
 * regenerated from the seed — once the light theme starts shipping in
 * production we'll lock the values via Material Theme Builder and swap.
 */
private val SynctuaryDarkScheme = darkColorScheme(
    primary             = SynctuaryPrimary,
    onPrimary           = SynctuaryOnPrimary,
    primaryContainer    = SynctuaryPrimaryContainer,
    onPrimaryContainer  = SynctuaryOnPrimaryC,
    secondary           = SynctuarySecondary,
    onSecondary         = SynctuaryOnSecondary,
    secondaryContainer  = SynctuarySecondaryC,
    onSecondaryContainer = SynctuaryOnSecondaryC,
    tertiary            = SynctuaryTertiary,
    onTertiary          = SynctuaryOnTertiary,
    tertiaryContainer   = SynctuaryTertiaryC,
    onTertiaryContainer = SynctuaryOnTertiaryC,
    error               = SynctuaryError,
    onError             = SynctuaryOnError,
    errorContainer      = SynctuaryErrorContainer,
    onErrorContainer    = SynctuaryOnErrorContainer,
    background          = SynctuaryBackground,
    onBackground        = SynctuaryOnBackground,
    surface             = SynctuarySurface,
    onSurface           = SynctuaryOnSurface,
    surfaceVariant      = SynctuarySurface2,
    onSurfaceVariant    = SynctuaryOnSurfaceVariant,
    outline             = SynctuaryOutline,
    outlineVariant      = SynctuaryOutlineVariant,
)

private val SynctuaryLightScheme = lightColorScheme(
    primary            = SynctuaryPrimaryContainer,
    onPrimary          = SynctuaryOnPrimaryC,
    primaryContainer   = SynctuaryPrimary,
    onPrimaryContainer = SynctuaryOnPrimary,
    background         = androidx.compose.ui.graphics.Color(0xFFFFFBFE),
    onBackground       = androidx.compose.ui.graphics.Color(0xFF1D1B20),
    surface            = androidx.compose.ui.graphics.Color(0xFFFFFBFE),
    onSurface          = androidx.compose.ui.graphics.Color(0xFF1D1B20),
)

@Composable
fun SynctuaryTheme(
    // Default-dark; flip to follow system once the light palette is locked.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SynctuaryDarkScheme else SynctuaryLightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = SynctuaryTypography,
        content     = content,
    )
}
