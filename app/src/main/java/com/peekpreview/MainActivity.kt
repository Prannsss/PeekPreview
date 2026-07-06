package com.peekpreview

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.peekpreview.data.PeekPreferences
import com.peekpreview.service.PeekForegroundService
import com.peekpreview.ui.screens.HomeScreen
import com.peekpreview.ui.screens.PermissionSetupScreen
import com.peekpreview.ui.screens.PermissionsState
import com.peekpreview.ui.theme.PeekPreviewTheme
import com.peekpreview.util.PermissionUtils
import com.peekpreview.util.SUPPORTED_APPS
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PeekPreviewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PeekApp()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PeekApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission snapshot, refreshed every time the app is resumed (i.e. when the
    // user returns from a Settings deep-link).
    var permissions by remember { mutableStateOf(readPermissions(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = readPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val masterEnabled by PeekPreferences.masterEnabledFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val debugLogging by PeekPreferences.debugLoggingFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val noticeDismissed by PeekPreferences.noticeDismissedFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)

    // Per-app enabled states, in SUPPORTED_APPS order (stable list -> stable
    // composition), zipped with their TargetApp.
    val appStates = SUPPORTED_APPS.map { app ->
        val on by PeekPreferences.appEnabledFlow(context, app.pkg)
            .collectAsStateWithLifecycle(initialValue = true)
        app to on
    }

    // Start/stop the transparency notification to match the master toggle.
    DisposableEffect(masterEnabled, permissions.allGranted) {
        if (masterEnabled && permissions.allGranted) {
            PeekForegroundService.start(context)
        } else {
            PeekForegroundService.stop(context)
        }
        onDispose { }
    }

    // Runtime notification permission launcher (Android 13+).
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissions = readPermissions(context) }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PermissionUtils.openAppNotificationSettings(context)
        }
    }

    // Onboard on first launch until everything is granted; "Skip"/"Done" dismisses.
    var onboardingDismissed by rememberSaveable { mutableStateOf(false) }
    val showOnboarding = !onboardingDismissed && !permissions.allGranted

    if (showOnboarding) {
        PermissionSetupScreen(
            permissions = permissions,
            onGrantAccessibility = { PermissionUtils.openAccessibilitySettings(context) },
            onGrantOverlay = { PermissionUtils.openOverlaySettings(context) },
            onGrantNotifications = { requestNotifications() },
            onDone = { onboardingDismissed = true },
        )
    } else {
        HomeScreen(
            masterChecked = masterEnabled && permissions.allGranted,
            onMasterChange = { want -> scope.launch { PeekPreferences.setMasterEnabled(context, want) } },
            appStates = appStates,
            onAppToggle = { pkg, want -> scope.launch { PeekPreferences.setAppEnabled(context, pkg, want) } },
            permissions = permissions,
            onFixAccessibility = { PermissionUtils.openAccessibilitySettings(context) },
            onFixOverlay = { PermissionUtils.openOverlaySettings(context) },
            onFixNotifications = { requestNotifications() },
            showNotice = !noticeDismissed,
            onDismissNotice = { scope.launch { PeekPreferences.setNoticeDismissed(context) } },
            showDeveloper = BuildConfig.DEBUG,
            debugLogging = debugLogging,
            onDebugToggle = { want -> scope.launch { PeekPreferences.setDebugLogging(context, want) } },
        )
    }
}

private fun readPermissions(context: android.content.Context) = PermissionsState(
    accessibility = PermissionUtils.isAccessibilityEnabled(context),
    overlay = PermissionUtils.canDrawOverlays(context),
    notifications = PermissionUtils.hasNotificationPermission(context),
)
