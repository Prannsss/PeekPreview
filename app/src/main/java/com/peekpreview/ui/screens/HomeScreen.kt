package com.peekpreview.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.peekpreview.R
import com.peekpreview.ui.components.PeekOverlayDemo
import com.peekpreview.ui.components.PermissionStatusRow
import com.peekpreview.ui.components.ToggleCard
import com.peekpreview.ui.theme.PeekBubbleShape
import com.peekpreview.util.TargetApp

/** Immutable snapshot of the three permission grants, recomputed on ON_RESUME. */
data class PermissionsState(
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = false,
) {
    val allGranted: Boolean get() = accessibility && overlay && notifications
}

@Composable
fun HomeScreen(
    masterChecked: Boolean,
    onMasterChange: (Boolean) -> Unit,
    appStates: List<Pair<TargetApp, Boolean>>,
    onAppToggle: (pkg: String, Boolean) -> Unit,
    permissions: PermissionsState,
    onFixAccessibility: () -> Unit,
    onFixOverlay: () -> Unit,
    onFixNotifications: () -> Unit,
    showNotice: Boolean,
    onDismissNotice: () -> Unit,
    showDeveloper: Boolean,
    debugLogging: Boolean,
    onDebugToggle: (Boolean) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))

        ToggleCard(
            checked = masterChecked,
            onCheckedChange = onMasterChange,
            enabled = permissions.allGranted,
            helperText = stringResource(R.string.perm_missing_helper),
        )

        AnimatedVisibility(visible = showNotice) {
            NoticeCard(onDismiss = onDismissNotice)
        }

        // --- Per-app toggles -------------------------------------------------
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.apps_header), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.apps_subhead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        appStates.forEach { (app, on) ->
            AppToggleRow(
                label = app.displayName,
                checked = on,
                // Per-app switches only matter when the master gate is on.
                enabled = permissions.allGranted && masterChecked,
                onCheckedChange = { onAppToggle(app.pkg, it) },
            )
        }

        // --- Permissions -----------------------------------------------------
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.permissions_header), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))

        PermissionRowAnimated(0) {
            PermissionStatusRow(stringResource(R.string.perm_accessibility), permissions.accessibility, onFixAccessibility)
        }
        PermissionRowAnimated(1) {
            PermissionStatusRow(stringResource(R.string.perm_overlay), permissions.overlay, onFixOverlay)
        }
        PermissionRowAnimated(2) {
            PermissionStatusRow(stringResource(R.string.perm_notifications), permissions.notifications, onFixNotifications)
        }

        // --- Preview ---------------------------------------------------------
        Spacer(Modifier.height(32.dp))
        Text("Preview", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "This is what a peek looks like:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PeekOverlayDemo()
        }

        // --- Developer (debug builds only) -----------------------------------
        if (showDeveloper) {
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.developer_header), style = MaterialTheme.typography.titleLarge)
            AppToggleRow(
                label = stringResource(R.string.debug_logging_label),
                description = stringResource(R.string.debug_logging_desc),
                checked = debugLogging,
                enabled = true,
                onCheckedChange = onDebugToggle,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AppToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun NoticeCard(onDismiss: () -> Unit) {
    Column {
        Spacer(Modifier.height(16.dp))
        Card(
            shape = PeekBubbleShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.notice_text), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.notice_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun PermissionRowAnimated(index: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it / (index + 2) },
        exit = fadeOut(),
    ) { content() }
}
