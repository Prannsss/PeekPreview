package com.peekpreview.ui.components

import android.graphics.Rect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.peekpreview.util.PeekContent
import kotlinx.coroutines.launch

/**
 * The actual peek bubble. Rendered BOTH in the real overlay window
 * (OverlayManager) and in the in-app demo card below, so what the user previews
 * is pixel-identical to what they get.
 *
 * scale/alpha are driven by the caller's spring Animatable in the real overlay;
 * the demo passes 1f/1f (or animates them itself).
 */
@Composable
fun PeekBubble(
    content: PeekContent,
    scale: Float = 1f,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .scale(scale)
            .alpha(alpha)
            .widthIn(max = 300.dp),
        shape = PeekBubbleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.ChatBubble,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    content.sender,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    content.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val demoContent = PeekContent(
    sender = "Alex Rivera",
    snippet = "Running about 10 min late — grab us a table? 🙌",
    bounds = Rect(),
)

/** In-app demo so users see the feature before enabling it. Springs in the same
 *  way the real overlay does, so the preview matches the live behavior. */
@Composable
fun PeekOverlayDemo(modifier: Modifier = Modifier) {
    val scale = remember { Animatable(0.9f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
        alpha.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
    }
    PeekBubble(content = demoContent, scale = scale.value, alpha = alpha.value, modifier = modifier)
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PeekBubblePreview() {
    PeekPreviewTheme { PeekOverlayDemo(Modifier.padding(24.dp)) }
}
