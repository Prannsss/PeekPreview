package com.peekpreview.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.peekpreview.ui.theme.PeekBubbleShape
import com.peekpreview.ui.theme.PeekPreviewTheme
import kotlinx.coroutines.delay

/**
 * The peek modal: a solid card (white in light mode, dark surface in dark mode
 * — it follows the phone's system theme via PeekPreviewTheme) with the sender's
 * name up top and the conversation below as chat bubbles. Rendered BOTH in the
 * real overlay window (OverlayManager) and in the in-app demo card, so what the
 * user previews is pixel-identical to what they get.
 *
 * [messages] is null while the chat is still being opened/read behind the
 * overlay (loading spinner); [failed] shows the give-up message instead.
 * scale/alpha are driven by the caller's spring Animatable in the real overlay;
 * the demo passes 1f/1f. [onClick] (overlay only) lands the user in the chat
 * that's already open behind the backdrop.
 */
@Composable
fun PeekModal(
    sender: String,
    messages: List<String>?,
    failed: Boolean = false,
    scale: Float = 1f,
    alpha: Float = 1f,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 12.dp,
        // Size comes entirely from [modifier]: the overlay hands us its big
        // center band, the demo a fillMaxWidth card.
        modifier = modifier
            .scale(scale)
            .alpha(alpha)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                sender,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when {
                failed -> Text(
                    "Couldn't read this conversation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                messages == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = muted,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("Peeking…", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                else -> Column(
                    Modifier
                        // Take whatever height the card was given; latest
                        // messages sit at the bottom, so start scrolled there.
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState(), reverseScrolling = true),
                ) {
                    messages.forEach { msg ->
                        Surface(
                            shape = PeekBubbleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** The prominent "Peek" button shown near the pressed row, riding above the
 *  target app's own long-press modal. Tap it to open the full [PeekModal]
 *  preview. Rendered in the real overlay and the demo. */
@Composable
fun PeekHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text("Peek", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val demoMessages = listOf(
    "Hey! Are we still on for tonight?",
    "Running about 10 min late — grab us a table? 🙌",
)

/** In-app demo of the real interaction: tap the Peek button to reveal the
 *  modal, which auto-dismisses after a few seconds (same as the overlay). */
@Composable
fun PeekOverlayDemo(modifier: Modifier = Modifier) {
    var revealed by remember { mutableStateOf(false) }

    LaunchedEffect(revealed) {
        if (revealed) { delay(4_000); revealed = false }
    }

    Box(modifier.fillMaxWidth().height(220.dp)) {
        if (revealed) {
            PeekModal(
                sender = "Alex Rivera",
                messages = demoMessages,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
        } else {
            PeekHandle(
                onClick = { revealed = true },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PeekModalPreview() {
    PeekPreviewTheme { PeekOverlayDemo(Modifier.padding(24.dp)) }
}
