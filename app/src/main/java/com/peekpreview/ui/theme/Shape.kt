package com.peekpreview.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Expressive shape scale: rounder, more organic corners than the 2021 baseline.
// M3 Expressive also ships MaterialShapes (cookie/clover/pill blobs) via the
// material3 1.4 API — great for decorative art, overkill for card corners, so
// we use the shape *scale* here and reserve MaterialShapes for illustrations
// (see PermissionSetupScreen).
//
// ponytail: a bumped RoundedCornerShape scale is the whole ask for "expressive
// corners". No custom Shape classes needed.
val PeekShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

// Explicit tokens for the hero elements so their intent is obvious at the call
// site (and so the overlay bubble matches the in-app demo exactly).
val ToggleCardShape = RoundedCornerShape(32.dp)
val PeekBubbleShape = RoundedCornerShape(topStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 28.dp)
