package com.peekpreview.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Expressive type scale. M3 Expressive leans on larger, more confident
// display/headline sizes and heavier weights than the 2021 baseline. We only
// override the styles the app uses as "hero" text; everything else inherits the
// Compose Material 3 defaults (which are already the current scale).
//
// ponytail: overriding a handful of roles, not redefining all 15 — the defaults
// are fine for body/label. Add more roles here only if a screen needs them.
val PeekTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 40.sp,
            lineHeight = 48.sp,
        ),
        headlineMedium = headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        titleLarge = titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
        ),
        labelLarge = labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

// Kept for callers that want to build a one-off style off the base font.
val DefaultExpressiveStyle = TextStyle(fontFamily = FontFamily.Default)
