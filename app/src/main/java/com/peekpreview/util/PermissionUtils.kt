package com.peekpreview.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.peekpreview.service.PeekAccessibilityService

/**
 * Permission checks + deep-links to the relevant system settings screens.
 * All three permissions here are "go to Settings" grants except notifications
 * on 13+, which is a runtime request handled by the Activity.
 */
object PermissionUtils {

    /** Is our AccessibilityService actually enabled by the user? */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PeekAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        // The setting is a colon-separated list of ComponentNames.
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** "Draw over other apps". Always true implicitly below Android 6, but our
     *  minSdk is 26 so canDrawOverlays is always available. */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** POST_NOTIFICATIONS is only a gate on Android 13+ (TIRAMISU). */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** All three required permissions present -> feature can be enabled. */
    fun allGranted(context: Context): Boolean =
        isAccessibilityEnabled(context) &&
            canDrawOverlays(context) &&
            hasNotificationPermission(context)

    // --- Deep links ---------------------------------------------------------

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Falls back to the app's notification settings page (used when the runtime
     *  request can't be shown, e.g. user selected "don't ask again"). */
    fun openAppNotificationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
