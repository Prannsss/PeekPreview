package com.peekpreview.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.peekpreview.ui.components.PeekBubble
import com.peekpreview.ui.theme.PeekPreviewTheme
import com.peekpreview.util.PeekContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the single floating peek bubble window.
 *
 * Hosting Compose in a raw WindowManager window (no Activity) needs three
 * ViewTree owners set manually, because ComposeView expects them and there's no
 * Activity to provide them:
 *   - LifecycleOwner            -> so composition runs (must be >= STARTED)
 *   - ViewModelStoreOwner       -> so remember-backed state has a store
 *   - SavedStateRegistryOwner   -> so rememberSaveable / SavedState works
 * The AccessibilityService implements all three (see PeekAccessibilityService),
 * so we pass it straight through here rather than building throwaway owners.
 *
 * `Owner` must be all three at once; the constructor bound enforces that at
 * compile time.
 *
 * ponytail: one reused window, added/removed on show/hide, not a pool. A user
 * long-presses one row at a time.
 */
class OverlayManager<Owner>(
    private val context: Context,
    private val owner: Owner,
    private val scope: CoroutineScope,
) where Owner : LifecycleOwner, Owner : SavedStateRegistryOwner, Owner : ViewModelStoreOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null

    // Drives the exit animation before the window is actually removed.
    private var dismissRequested by mutableStateOf(false)

    fun show(content: PeekContent) {
        hideImmediate()          // re-showing swaps content
        dismissRequested = false

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                PeekPreviewTheme {
                    val scale = remember { Animatable(0.85f) }
                    val alpha = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                        launch { alpha.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    }
                    LaunchedEffect(dismissRequested) {
                        if (dismissRequested) {
                            launch { alpha.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
                            scale.animateTo(0.9f, spring(stiffness = Spring.StiffnessMedium))
                            hideImmediate()
                        }
                    }
                    PeekBubble(content = content, scale = scale.value, alpha = alpha.value)
                }
            }
        }

        windowManager.addView(view, buildParams(content.bounds))
        composeView = view

        // Timeout fallback auto-dismiss (see class-level note on release detection).
        scope.launch(Dispatchers.Main) {
            delay(AUTO_DISMISS_MS)
            dismiss()
        }
    }

    /** Animate out, then remove. Safe to call repeatedly / when already hidden. */
    fun dismiss() {
        if (composeView != null) dismissRequested = true
    }

    private fun hideImmediate() {
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
    }

    private fun buildParams(rowBounds: Rect): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE: never steal input from Messages.
            // NOT_TOUCH_MODAL: taps outside the bubble pass through to the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rowBounds.left + 24        // anchor just below the row, nudged in
            y = rowBounds.bottom + 8
        }
    }

    companion object {
        private const val AUTO_DISMISS_MS = 3_000L
    }
}
