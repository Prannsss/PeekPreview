package com.peekpreview.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.peekpreview.BuildConfig
import com.peekpreview.data.PeekPreferences
import com.peekpreview.util.NodeExtractionUtils
import com.peekpreview.util.Packages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The core service. Watches the enabled messaging apps for a long-press,
 * extracts the pressed row's text, and asks OverlayManager to arm the peek
 * handle (the user then drags the handle up to reveal the full bubble).
 *
 * Also implements Lifecycle / ViewModelStore / SavedStateRegistry owners so it
 * can host the overlay's ComposeView (see OverlayManager).
 *
 * --- Trigger detection ---------------------------------------------------
 * TYPE_VIEW_LONG_CLICKED is the ideal signal; RecyclerView rows often swallow
 * it, so TYPE_VIEW_ACCESSIBILITY_FOCUSED on a clickable row is the fallback.
 * (See git history / original notes for why we don't reconstruct held gestures.)
 *
 * --- Meta apps -----------------------------------------------------------
 * Messenger and Instagram are React Native and mix DMs into a broader nav
 * structure. Before doing any row extraction we confirm the ACTIVE window is
 * actually a DM inbox / conversation surface (isDmSurface), so we don't misfire
 * on the Instagram feed, Stories, or Messenger calls/tabs.
 *
 * --- Peek = open the chat behind the overlay ------------------------------
 * The inbox row only exposes a snippet, so when the user taps Peek the overlay
 * throws up an OPAQUE full-screen backdrop and we open the chat behind it
 * (close the app's long-press sheet, tap the row), read the real conversation
 * from the now-active chat tree, and hand it to the overlay's modal. The user
 * never sees the chat open; on dismiss we BACK to the inbox and mark the row
 * unread again (via the app's own long-press sheet) before the overlay fades,
 * so the conversation looks untouched. Read receipts still went out the moment
 * the chat opened — that part is out of our hands.
 */
class PeekAccessibilityService :
    AccessibilityService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val vmStore = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = vmStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var overlay: OverlayManager<PeekAccessibilityService>

    // Latest state, read synchronously on the hot event path.
    private val enabledPackages = MutableStateFlow<Set<String>>(emptySet())
    private val debugLogging = MutableStateFlow(false)

    override fun onCreate() {
        savedStateController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayManager(this, this, scope)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        PeekPreferences.enabledPackagesFlow(this)
            .onEach { enabledPackages.value = it }
            .launchIn(scope)
        PeekPreferences.debugLoggingFlow(this)
            .onEach { debugLogging.value = it }
            .launchIn(scope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        // Cheapest early-out: is this package currently enabled to peek?
        if (pkg !in enabledPackages.value) return
        // While the preview is open we're the ones driving the target app (the
        // exit sequence long-presses a row) — synthetic gestures must not
        // re-trigger a peek.
        if (overlay.isPreviewShowing) return

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "event=${AccessibilityEvent.eventTypeToString(event.eventType)} pkg=$pkg cls=${event.className} src=${event.source != null}")
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> handleTrigger(event, pkg)

            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ->
                if (looksLikeConversationRow(event)) handleTrigger(event, pkg)

            // NOTE: deliberately NOT dismissing on TYPE_WINDOW_CONTENT_CHANGED.
            // The target app's own long-press bottom sheet fires it, which used
            // to delete our Peek button the moment their modal opened. Overlay
            // lifetime is handled by its own timeouts + outside-tap instead.
        }
    }

    private fun handleTrigger(event: AccessibilityEvent, pkg: String) {
        // Confirm we're on a DM surface before touching the row (Meta apps).
        if (!isDmSurface(pkg)) return

        val source = event.source ?: return

        if (BuildConfig.DEBUG && debugLogging.value) {
            Log.d(TAG, "long-press on $pkg — node tree:\n${NodeExtractionUtils.dumpTree(source)}")
        }

        val content = NodeExtractionUtils.extractRowText(source) ?: return // empty -> do nothing
        val rowBounds = Rect(content.bounds)
        scope.launch {
            overlay.show(
                content = content,
                onPeek = { onResult -> openChatAndExtract(pkg, rowBounds, onResult) },
                onExitToInbox = { exitChatAndMarkUnread(pkg, rowBounds) },
            )
        }
    }

    /** Runs while the overlay's opaque backdrop covers the screen: close the
     *  target app's own long-press sheet, tap the row's on-screen position to
     *  open the chat (a real tap gesture is more reliable than ACTION_CLICK on
     *  Meta's React Native rows), then poll the active window for the opened
     *  conversation and deliver it (null = give up, modal shows an error).
     *  ponytail: assumes the list hasn't scrolled since the long-press, so the
     *  row is still at [rowBounds]; the sheet-close BACK doesn't scroll it.
     *  We also can't verify the tap landed on a chat vs. still-on-inbox — the
     *  fixed delays make that the overwhelmingly common case. */
    private fun openChatAndExtract(pkg: String, rowBounds: Rect, onResult: (List<String>?) -> Unit) {
        scope.launch {
            delay(150) // overlay's opaque backdrop renders before anything moves behind it
            performGlobalAction(GLOBAL_ACTION_BACK) // dismiss the app's long-press sheet
            delay(350) // let the sheet finish closing
            // If that BACK left the app (no sheet was open), do NOT tap blindly —
            // we'd be tapping the launcher. Give up; the modal shows the error.
            if (rootInActiveWindow?.packageName?.toString() != pkg) {
                onResult(null)
                return@launch
            }
            val path = Path().apply {
                moveTo(rowBounds.exactCenterX(), rowBounds.exactCenterY())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            delay(800) // let the chat screen open behind the overlay

            var messages: List<String>? = null
            repeat(3) {
                if (messages == null) {
                    val root = rootInActiveWindow
                    if (root?.packageName?.toString() == pkg) {
                        if (BuildConfig.DEBUG && debugLogging.value) {
                            Log.d(TAG, "chat tree:\n${NodeExtractionUtils.dumpTree(root)}")
                        }
                        messages = NodeExtractionUtils.extractConversation(root)
                    }
                    if (messages == null) delay(400)
                }
            }
            onResult(messages)
        }
    }

    /** The preview is closing: leave the chat and restore the row's unread
     *  badge, all while the overlay's opaque backdrop still covers the screen.
     *  BACK returns to the inbox, a synthetic long-press on the row opens the
     *  app's own sheet, and its "Mark as unread" item gets clicked. Read
     *  receipts already went out when the chat opened — this only restores the
     *  visual unread state so the conversation looks untouched. */
    private suspend fun exitChatAndMarkUnread(pkg: String, rowBounds: Rect) {
        performGlobalAction(GLOBAL_ACTION_BACK) // chat -> inbox
        delay(800) // inbox settles
        longPress(rowBounds)
        delay(800) // the 650ms hold finishes; the sheet starts sliding up
        // Poll for the sheet's "unread" item instead of trusting a fixed delay —
        // sheet animation time varies a lot between devices. ~2.4s worst case.
        var clicked = false
        repeat(8) {
            if (!clicked) {
                clicked = clickMarkUnread(pkg)
                if (!clicked) delay(300)
            }
        }
        if (!clicked) {
            performGlobalAction(GLOBAL_ACTION_BACK) // no such item; just close the sheet
        }
        delay(600) // let the sheet fully close before the overlay starts fading
    }

    private fun longPress(bounds: Rect) {
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 650)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Bounded DFS over the active window for a clickable item whose text/desc
     *  mentions "unread" (Messenger and Instagram both word it that way in
     *  their row sheets) and clicks it. */
    private fun clickMarkUnread(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 400) {
            val node = stack.removeLast()
            visited++
            val label = buildString {
                append(node.text ?: "").append(' ').append(node.contentDescription ?: "")
            }.lowercase()
            if ("unread" in label) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    /**
     * Is the ACTIVE window a DM inbox / conversation list for [pkg]?
     *  - Messenger: messaging-first; accept as long as the active window is orca.
     *  - Instagram: must find a DM hint in the tree (resource-id/class/desc
     *    containing "direct"/"thread"/"inbox"), because IG buries DMs inside a
     *    feed-centric activity and we don't want to peek on the feed or Stories.
     */
    private fun isDmSurface(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        return when (pkg) {
            Packages.MESSENGER -> true
            Packages.INSTAGRAM -> treeHasHint(root, INSTAGRAM_DM_HINTS, maxNodes = 400)
            else -> false
        }
    }

    /** Bounded DFS: true if any node's resource-id / class / contentDescription
     *  contains one of [hints] (lowercased). Bounded so a huge RN tree can't
     *  stall the event thread. */
    private fun treeHasHint(root: AccessibilityNodeInfo, hints: List<String>, maxNodes: Int): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            val haystack = buildString {
                append(node.viewIdResourceName ?: "")
                append(' ').append(node.className ?: "")
                append(' ').append(node.contentDescription ?: "")
            }.lowercase()
            if (hints.any { it in haystack }) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    private fun looksLikeConversationRow(event: AccessibilityEvent): Boolean {
        val node = event.source ?: return false
        return node.isClickable && node.childCount >= 1
    }

    override fun onInterrupt() { /* required override; nothing to interrupt */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        vmStore.clear()
        scope.cancel()
        return super.onUnbind(intent)
    }

    private companion object {
        const val TAG = "PeekPreview"
        val INSTAGRAM_DM_HINTS = listOf("direct", "thread", "inbox", "dm")
    }
}
