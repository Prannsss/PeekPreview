package com.peekpreview.service

import android.accessibilityservice.AccessibilityService
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The core service. Watches the enabled messaging apps for a long-press,
 * extracts the pressed row's text, and asks OverlayManager to draw a peek bubble.
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
 * on the Instagram feed, Stories, or Messenger calls/tabs. Google Messages is
 * messaging-only, so it always passes.
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

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> handleTrigger(event, pkg)

            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ->
                if (looksLikeConversationRow(event)) handleTrigger(event, pkg)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (::overlay.isInitialized) overlay.dismiss()
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
        scope.launch { overlay.show(content) }
    }

    /**
     * Is the ACTIVE window a DM inbox / conversation list for [pkg]?
     *  - Messages: always (whole app is messaging).
     *  - Messenger: messaging-first; accept as long as the active window is orca.
     *  - Instagram: must find a DM hint in the tree (resource-id/class/desc
     *    containing "direct"/"thread"/"inbox"), because IG buries DMs inside a
     *    feed-centric activity and we don't want to peek on the feed or Stories.
     */
    private fun isDmSurface(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        return when (pkg) {
            Packages.MESSAGES, Packages.MESSENGER -> true
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
