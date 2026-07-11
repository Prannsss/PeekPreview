package com.peekpreview.util

/** A messaging app PeekPreview can peek into. */
data class TargetApp(val pkg: String, val displayName: String)

// The supported apps, in the order shown in the UI. Single source of truth for
// both the settings list and the accessibility service's per-app gating.
// To add an app later: add it here AND to packageNames in
// accessibility_service_config.xml.
// ponytail: Google Messages temporarily dropped — only Messenger + Instagram
// under test for now.
val SUPPORTED_APPS = listOf(
    TargetApp("com.facebook.orca", "Messenger"),
    TargetApp("com.instagram.android", "Instagram"),
)

object Packages {
    const val MESSENGER = "com.facebook.orca"
    const val INSTAGRAM = "com.instagram.android"
}
