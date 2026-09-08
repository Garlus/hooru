package com.purepixel.camera.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.purepixel.camera.R

val DepartureMono = FontFamily(
    Font(R.font.departure_mono_regular, FontWeight.Normal)
)

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold)
)

private val DefaultTypography = Typography()

val HooruTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.copy(fontFamily = DepartureMono),
    displayMedium = DefaultTypography.displayMedium.copy(fontFamily = DepartureMono),
    displaySmall = DefaultTypography.displaySmall.copy(fontFamily = DepartureMono),
    headlineLarge = DefaultTypography.headlineLarge.copy(fontFamily = DepartureMono),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontFamily = DepartureMono),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontFamily = DepartureMono),
    titleLarge = DefaultTypography.titleLarge.copy(fontFamily = DepartureMono),
    titleMedium = DefaultTypography.titleMedium.copy(fontFamily = DepartureMono),
    titleSmall = DefaultTypography.titleSmall.copy(fontFamily = DepartureMono),
    bodyLarge = DefaultTypography.bodyLarge.copy(fontFamily = PlexSans),
    bodyMedium = DefaultTypography.bodyMedium.copy(fontFamily = PlexSans),
    bodySmall = DefaultTypography.bodySmall.copy(fontFamily = PlexSans),
    labelLarge = DefaultTypography.labelLarge.copy(fontFamily = DepartureMono),
    labelMedium = DefaultTypography.labelMedium.copy(fontFamily = DepartureMono),
    labelSmall = DefaultTypography.labelSmall.copy(fontFamily = DepartureMono)
)
