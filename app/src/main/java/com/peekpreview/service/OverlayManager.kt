package com.peekpreview.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.peekpreview.ui.components.PeekHandle
import com.peekpreview.ui.components.PeekModal
import com.peekpreview.ui.theme.PeekPreviewTheme
import com.peekpreview.util.PeekContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the floating peek overlay, a two-stage interaction:
 *
 *  1. ARM ([show]): the accessibility trigger fired (the long-press that ALSO
 *     opens the target app's own action sheet — we don't fight it). We show a
 *     prominent "Peek" BUTTON at the TOP-CENTER of the screen, well clear of
 *     the bottom-sheet modals Meta slides up. TYPE_APPLICATION_OVERLAY
 *     z-orders above the app's sheet, so the button rides on top of it.
 *     Dismissed by tapping anywhere outside it, or a timeout.
 *  2. REVEAL ([reveal]): the user taps the button. A full-screen OPAQUE
 *     backdrop fades in with a white modal card on top. Behind that backdrop
 *     the service opens the real chat (onPeek) and delivers the conversation,
 *     which the modal shows once loaded — the user never sees the chat open.
 *     Tapping the scrim (or a failsafe timeout) first BACKs to the inbox
 *     behind the still-opaque backdrop (onExitToInbox), then fades out.
 *     Tapping the modal itself just removes the overlay, landing the user in
 *     the chat that's already open behind it.
 *
 *  The preview window is FLAG_SECURE: it never appears in screenshots or screen
 *  recordings — it's a privacy peek, not shareable content. It stays
 *  FLAG_NOT_FOCUSABLE so the target app keeps input focus and the service's
 *  rootInActiveWindow keeps pointing at the chat we open behind the backdrop.
 */
class OverlayManager<Owner>(
    private val context: Context,
    private val owner: Owner,
    private val scope: CoroutineScope,
) where Owner : LifecycleOwner, Owner : SavedStateRegistryOwner, Owner : ViewModelStoreOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var handleView: ComposeView? = null
    private var fullView: ComposeView? = null
    private var timeoutJob: Job? = null

    private var dismissRequested by mutableStateOf(false) // drives the exit animation
    private var peekMessages by mutableStateOf<List<String>?>(null) // null = still loading
    private var peekFailed by mutableStateOf(false)

    /** True while the full preview is up (including its exit sequence) — the
     *  service must ignore triggers then, since it's driving the app itself. */
    val isPreviewShowing: Boolean get() = fullView != null

    /** Stage 1 (arm): show the Peek button top-center, above the app's modal.
     *  [onPeek] opens the chat behind the backdrop and delivers the conversation
     *  (null = extraction failed); [onExitToInbox] BACKs out of that chat and
     *  restores the row's unread state (suspends until it's done). */
    fun show(content: PeekContent, onPeek: ((List<String>?) -> Unit) -> Unit, onExitToInbox: suspend () -> Unit) {
        hideAll()
        dismissRequested = false

        val view = newComposeView().apply {
            setContent {
                PeekPreviewTheme {
                    val scale = remember { Animatable(0.7f) }
                    LaunchedEffect(Unit) {
                        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                    PeekHandle(
                        onClick = { reveal(content, onPeek, onExitToInbox) },
                        modifier = Modifier.scale(scale.value),
                    )
                }
            }
            // FLAG_WATCH_OUTSIDE_TOUCH delivers ACTION_OUTSIDE for any tap that
            // isn't on the button: the user is doing something else, so leave.
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss()
                false
            }
        }
        windowManager.addView(view, handleParams())
        handleView = view
        startTimeout(HANDLE_TIMEOUT_MS)
    }

    /** Stage 2: swap the button for the opaque backdrop + modal, and kick
     *  off the background chat-open. */
    private fun reveal(
        content: PeekContent,
        onPeek: ((List<String>?) -> Unit) -> Unit,
        onExitToInbox: suspend () -> Unit,
    ) {
        timeoutJob?.cancel()
        handleView?.let { runCatching { windowManager.removeView(it) } }
        handleView = null
        if (fullView != null) return

        peekMessages = null
        peekFailed = false

        val params = fullParams()
        val view = newComposeView()
        view.setContent {
            PeekPreviewTheme {
                // NO entry animation: the preview must be there, fully opaque, the
                // instant it appears. [exit] only drives the fade-OUT (after the
                // mark-unread sequence has finished behind the backdrop).
                val exit = remember { Animatable(1f) }
                LaunchedEffect(Unit) {
                    // Handshake: wait for two real rendered frames so the opaque
                    // backdrop is guaranteed on screen — only then may the service
                    // start driving the app behind it. A fixed delay isn't enough
                    // on devices where the first Compose frame is slow.
                    withFrameNanos { }
                    withFrameNanos { }
                    onPeek { result ->
                        when {
                            // Overlay already dismissed mid-load: the chat we opened
                            // has nobody looking at it — back out of it right away.
                            fullView == null -> if (result != null) scope.launch { onExitToInbox() }
                            result == null -> peekFailed = true
                            else -> peekMessages = result
                        }
                        // Loading is over: let the window take touches again so the
                        // scrim / modal taps work (it was touch-transparent during
                        // the chat-open).
                        if (fullView === view) {
                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                            runCatching { windowManager.updateViewLayout(view, params) }
                        }
                    }
                }
                LaunchedEffect(dismissRequested) {
                    if (dismissRequested) {
                        // Leave the chat while the backdrop is still opaque so
                        // the user never sees it. Only if we actually got in — a
                        // stray BACK from the inbox would minimize the app.
                        if (peekMessages != null) {
                            // The exit sequence long-presses the row to mark it
                            // unread; that synthetic gesture must pass through us.
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            runCatching { windowManager.updateViewLayout(view, params) }
                            runCatching { onExitToInbox() }
                        }
                        // Only now, with the inbox restored underneath, fade out.
                        exit.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                        hideAll()
                    }
                }
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .background(Backdrop.copy(alpha = exit.value))
                        .pointerInput(Unit) { detectTapGestures { dismiss() } }, // tap scrim = dismiss
                ) {
                    PeekModal(
                        sender = content.sender,
                        messages = peekMessages,
                        failed = peekFailed,
                        scale = 0.9f + 0.1f * exit.value,
                        alpha = exit.value,
                        // Land in the already-open chat — overlay just goes away.
                        onClick = { if (peekMessages != null) hideAll() },
                        // Requested geometry: a big center-band modal, inset 20%
                        // top / 30% bottom / 5% each side of the screen.
                        modifier = Modifier
                            .padding(
                                start = maxWidth * 0.05f,
                                end = maxWidth * 0.05f,
                                top = maxHeight * 0.20f,
                                bottom = maxHeight * 0.30f,
                            )
                            .fillMaxSize(),
                    )
                }
            }
        }
        windowManager.addView(view, params)
        fullView = view
        startTimeout(PREVIEW_TIMEOUT_MS)
        // onPeek is invoked from the composition above, after the first frames render.
    }

    /** Animate out / remove. Safe to call repeatedly. */
    fun dismiss() {
        if (fullView != null) dismissRequested = true else hideAll()
    }

    private fun startTimeout(ms: Long) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch(Dispatchers.Main) {
            delay(ms)
            dismiss()
        }
    }

    private fun hideAll() {
        timeoutJob?.cancel()
        fullView?.let { runCatching { windowManager.removeView(it) } }
        handleView?.let { runCatching { windowManager.removeView(it) } }
        fullView = null
        handleView = null
    }

    private fun newComposeView() = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    /** Tight window around the Peek button, pinned TOP-CENTER — clear of both
     *  the conversation rows and the bottom-sheet modals. NOT_FOCUSABLE /
     *  NOT_TOUCH_MODAL so the app (and its modal) stay fully usable around it;
     *  WATCH_OUTSIDE_TOUCH so any outside tap dismisses it. */
    private fun handleParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (72 * density).toInt() // below the status bar, above the app's header
        }

    /** Full-screen preview window. NOT_FOCUSABLE (the chat behind must stay the
     *  active window) and SECURE so the peeked content never lands in
     *  screenshots/recordings. The backdrop composable is opaque, so no
     *  blur-behind — nothing behind it should ever be visible.
     *
     *  Starts NOT_TOUCHABLE: while the chat is being opened behind the backdrop,
     *  the service dispatches a tap gesture at the row's position, and synthetic
     *  gestures land on the topmost TOUCHABLE window — which would be us,
     *  self-dismissing the overlay instead of opening the chat. Once loading
     *  resolves, reveal() flips the flag off so scrim/modal taps work. */
    private fun fullParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Kill the system's default window enter/exit fade: during it the
            // window is semi-transparent, which exposed the chat opening behind
            // the "opaque" backdrop. android.R.style.Animation = empty style.
            windowAnimations = android.R.style.Animation
        }

    companion object {
        private const val HANDLE_TIMEOUT_MS = 6_000L   // Peek button lingers over the app's modal
        private const val PREVIEW_TIMEOUT_MS = 20_000L // failsafe; scrim tap is the real dismiss
        private val Backdrop = Color(0xFF17181C)       // fully opaque — hides the chat behind
    }
}
