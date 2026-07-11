package com.peekpreview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * First-run onboarding. Three full-screen steps (accessibility, overlay,
 * notifications), each explaining *why* the permission is needed and deep-linking
 * to the relevant Settings screen. The caller passes the grant callbacks and a
 * live [granted] snapshot so a step's button flips to "Continue" once its
 * permission lands (state is rechecked on ON_RESUME by MainActivity).
 */
private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val actionLabel: String,
)

@Composable
fun PermissionSetupScreen(
    permissions: PermissionsState,
    onGrantAccessibility: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantNotifications: () -> Unit,
    onDone: () -> Unit,
) {
    val steps = listOf(
        OnboardingStep(
            icon = Icons.Filled.Accessibility,
            title = "Read the row you long-press",
            body = "PeekPreview uses an Accessibility Service to read the text of the " +
                "conversation you long-press in Facebook Messenger or Instagram Direct — " +
                "only in response to that long-press. It does not log or send your " +
                "messages anywhere; nothing leaves your device.",
            actionLabel = "Open Accessibility settings",
        ),
        OnboardingStep(
            icon = Icons.Filled.Layers,
            title = "Draw the peek bubble",
            body = "To float the preview on top of Messenger or Instagram, PeekPreview needs " +
                "permission to display over other apps.",
            actionLabel = "Allow display over apps",
        ),
        OnboardingStep(
            icon = Icons.Filled.Notifications,
            title = "Show a status notification",
            body = "A quiet, ongoing notification tells you the feature is on and lets you " +
                "turn it off in one tap. On Android 13+ this needs your permission.",
            actionLabel = "Allow notifications",
        ),
    )
    val grantedFor = listOf(
        permissions.accessibility,
        permissions.overlay,
        permissions.notifications,
    )
    val actionFor = listOf(onGrantAccessibility, onGrantOverlay, onGrantNotifications)

    val pagerState = rememberPagerState(pageCount = { steps.size })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            StepPage(step = steps[page], granted = grantedFor[page])
        }

        val isLast = pagerState.currentPage == steps.lastIndex
        val stepGranted = grantedFor[pagerState.currentPage]

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDone) { Text("Skip") }

            Button(
                onClick = {
                    when {
                        !stepGranted -> actionFor[pagerState.currentPage]()
                        isLast -> onDone()
                        else -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
            ) {
                Text(
                    when {
                        !stepGranted -> steps[pagerState.currentPage].actionLabel
                        isLast -> "Done"
                        else -> "Continue"
                    },
                )
            }
        }
    }
}

@Composable
private fun StepPage(step: OnboardingStep, granted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Expressive full-bleed circular illustration holder.
        Surface(
            shape = CircleShape,
            color = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier.size(160.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    step.icon,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(40.dp))
        Text(
            step.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            step.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (granted) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Granted ✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
