package io.synctuary.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color tokens for the Synctuary brand, matching the values pinned in
 * docs/android-ui-mockups.html (M3 dark theme generated from seed #5E35B1).
 *
 * The mockup is the source of truth for visual review; whenever a token
 * changes, update both files in lock-step.
 */

// Surface tones
val SynctuaryBackground       = Color(0xFF141218)
val SynctuarySurface          = Color(0xFF1D1B20)
val SynctuarySurfaceContainer = Color(0xFF211F26) // +1 elevation
val SynctuarySurface2         = Color(0xFF2B2930) // +2 elevation
val SynctuarySurface3         = Color(0xFF322F37) // +3 elevation (FAB, app bar)
val SynctuarySurface4         = Color(0xFF38353D)

// On-surface text
val SynctuaryOnBackground     = Color(0xFFE6E0E9)
val SynctuaryOnSurface        = Color(0xFFE6E0E9)
val SynctuaryOnSurfaceVariant = Color(0xFFCAC4D0)
val SynctuaryOutline          = Color(0xFF938F99)
val SynctuaryOutlineVariant   = Color(0xFF49454F)

// Primary (purple, tonal palette 80 in dark)
val SynctuaryPrimary          = Color(0xFFCFBCFF)
val SynctuaryOnPrimary        = Color(0xFF381E72)
val SynctuaryPrimaryContainer = Color(0xFF4F378B)
val SynctuaryOnPrimaryC       = Color(0xFFEADDFF)

// Secondary
val SynctuarySecondary        = Color(0xFFCCC2DC)
val SynctuaryOnSecondary      = Color(0xFF332D41)
val SynctuarySecondaryC       = Color(0xFF4A4458)
val SynctuaryOnSecondaryC     = Color(0xFFE8DEF8)

// Tertiary (warm pink for image hints)
val SynctuaryTertiary         = Color(0xFFEFB8C8)
val SynctuaryOnTertiary       = Color(0xFF492532)
val SynctuaryTertiaryC        = Color(0xFF633B48)
val SynctuaryOnTertiaryC      = Color(0xFFFFD8E4)

// Status
val SynctuaryError            = Color(0xFFF2B8B5)
val SynctuaryOnError          = Color(0xFF601410)
val SynctuaryErrorContainer   = Color(0xFF8C1D18)
val SynctuaryOnErrorContainer = Color(0xFFF9DEDC)
val SynctuarySuccess          = Color(0xFF7DD58E)
