package com.peekpreview.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulls readable text out of an accessibility node subtree.
 *
 * IMPORTANT LIMITATION: what's available depends entirely on what each app
 * exposes in its accessibility tree, which is not a public contract and can
 * change with any app update. Meta's apps (Messenger, Instagram) are React
 * Native, so they often DON'T assign stable resource-ids and lean on
 * contentDescription instead — that's why collect() reads text OR
 * contentDescription from every node rather than looking up known ids. We
 * filter out purely structural labels (bare timestamps, single emoji) so the
 * body pick isn't a "5m" or a "😂".
 *
 * Degrades gracefully: if nothing usable is found, returns null and the caller
 * shows nothing rather than an empty bubble.
 */
object NodeExtractionUtils {

    private const val MAX_SCAN_NODES = 800
    private const val MAX_CONVERSATION_PIECES = 30

    // Bare clock time: "9:41", "9:41 PM".
    private val CLOCK_RE = Regex("""^\d{1,2}:\d{2}(\s?[AaPp][Mm])?$""")
    // Relative/short time: "5m", "2h", "3d", "1w", "Now", "Yesterday".
    private val RELATIVE_RE = Regex("""^(\d+\s?[smhdwSMHDW]|now|yesterday)$""", RegexOption.IGNORE_CASE)

    /** Depth-first collection of every non-blank, non-structural text /
     *  contentDescription in the subtree, de-duplicated and in visual order. */
    fun extractRowText(root: AccessibilityNodeInfo?): PeekContent? {
        if (root == null) return null

        val pieces = LinkedHashSet<String>()
        collect(root, pieces)
        if (pieces.isEmpty()) return null

        // Heuristic: the first piece is usually the sender/title; everything
        // after it is the latest text the row exposes (snippet, reactions,
        // attachment labels...) shown as a scrollable message list.
        val (sender, body) = selectSenderAndBody(pieces.toList()) ?: return null
        val messages = pieces.drop(1).ifEmpty { listOf(body) }
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        return PeekContent(sender = sender, messages = messages, bounds = bounds)
    }

    /**
     * Pure heuristic, split out so it's unit-testable without a live node tree:
     * first piece = sender/title, longest of the rest = message body (timestamps
     * are short, so "longest remaining" is a decent body pick). If there's only
     * one piece, it doubles as both.
     */
    fun selectSenderAndBody(pieces: List<String>): Pair<String, String>? {
        if (pieces.isEmpty()) return null
        val sender = pieces.first()
        val body = pieces.drop(1).maxByOrNull { it.length } ?: sender
        return sender to body
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return
        (node.text ?: node.contentDescription)?.toString()?.trim()?.let {
            if (it.isNotEmpty() && !isStructuralLabel(it)) out.add(it)
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out)
        }
    }

    /**
     * True for nodes that carry layout/decoration rather than message content:
     * bare timestamps and single-symbol/emoji labels. Kept conservative — we'd
     * rather show a slightly noisy preview than filter out real text.
     */
    fun isStructuralLabel(raw: String): Boolean {
        val t = raw.trim()
        if (t.length <= 1) return true
        if (CLOCK_RE.matches(t) || RELATIVE_RE.matches(t)) return true
        // Single emoji / symbol-only short token: no letters or digits at all.
        if (t.length <= 3 && t.none { it.isLetterOrDigit() }) return true
        return false
    }

    /**
     * Extracts the visible messages from an OPEN conversation screen (the
     * overlay opens the chat behind its opaque backdrop, then calls this).
     *
     * Collection is scoped to the LARGEST scrollable subtree — the message
     * list — which structurally excludes the composer bar and top bar where
     * the "Open gallery / voice clip / Send" buttons live. If no scrollable
     * node is found we fall back to the whole tree and lean on the
     * [isComposerLabel] blocklist instead.
     */
    fun extractConversation(root: AccessibilityNodeInfo?): List<String>? {
        if (root == null) return null
        val listRoot = findLargestScrollable(root)
        val pieces = LinkedHashSet<String>()
        collectConversation(listRoot ?: root, pieces, strict = listRoot == null, budget = intArrayOf(MAX_SCAN_NODES))
        // Latest messages sit at the bottom of the list; DFS order ≈ visual order.
        return pieces.toList().takeLast(MAX_CONVERSATION_PIECES).ifEmpty { null }
    }

    /** Bounded DFS for the scrollable node with the largest on-screen area. */
    private fun findLargestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        val rect = Rect()
        while (stack.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = stack.removeLast()
            visited++
            if (node.isScrollable) {
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height()
                if (area > bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return best
    }

    /** Like [collect], but for a chat screen: skips control widgets (and their
     *  subtrees) and, in [strict] fallback mode, known composer/top-bar labels. */
    private fun collectConversation(
        node: AccessibilityNodeInfo?,
        out: MutableSet<String>,
        strict: Boolean,
        budget: IntArray,
    ) {
        if (node == null || budget[0]-- <= 0) return
        if (isChatControl(node.className?.toString())) return // button/composer: skip whole subtree
        (node.text ?: node.contentDescription)?.toString()?.trim()?.let {
            if (it.isNotEmpty() && !isStructuralLabel(it) && !isChatNoise(it) &&
                !(strict && isComposerLabel(it))
            ) out.add(it)
        }
        for (i in 0 until node.childCount) {
            collectConversation(node.getChild(i), out, strict, budget)
        }
    }

    /** Control widgets that never carry message text: buttons and the composer
     *  field. Class-name based so it holds across app updates and locales. */
    fun isChatControl(className: String?): Boolean {
        val cls = className ?: return false
        return "Button" in cls || "EditText" in cls
    }

    // ponytail: English-phrase blocklist, only used when no scrollable message
    // list was found (strict fallback). Localized Messenger builds slip past it;
    // the structural (scrollable-subtree) path is the real filter.
    private val CHAT_CHROME_RE = Regex(
        """gallery|sticker|emoji|\bgif\b|voice (clip|message)|camera|take a photo|audio call|video call|send message|send a like|more options""",
        RegexOption.IGNORE_CASE,
    )

    /** True for short labels that look like chat-screen chrome (composer / call
     *  buttons) rather than a message. */
    fun isComposerLabel(label: String): Boolean =
        label.length <= 48 && CHAT_CHROME_RE.containsMatchIn(label)

    // Date/time section headers inside a chat: "Today at 9:41 PM",
    // "Jul 10, 2026, 9:41 PM", "THU 9:41 PM", "Wednesday", "Yesterday 21:30".
    // Anchored on a leading month/weekday word so real messages don't match.
    private val DATE_HEADER_RE = Regex(
        """^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|mon|tue|wed|thu|fri|sat|sun|today|yesterday)[a-z]*( \d{1,2})?(,? \d{4})?(,| at)? ?(\d{1,2}:\d{2}(\s?[ap]m)?)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Chat-screen noise that isn't a message: date/time headers and the
     *  "Open {name}'s profile" link Meta puts inside the message list. */
    fun isChatNoise(label: String): Boolean {
        val t = label.trim()
        if (DATE_HEADER_RE.matches(t)) return true
        if (t.length <= 80 && t.startsWith("open", ignoreCase = true) &&
            t.contains("profile", ignoreCase = true)
        ) return true
        return false
    }

    /**
     * Dumps the full node subtree to a string for the dev logging mode. Meta's
     * trees vary a lot between app versions, so this lets you see exactly what
     * Messenger/Instagram expose on the installed build and tune the heuristics.
     */
    fun dumpTree(node: AccessibilityNodeInfo?, depth: Int = 0, sb: StringBuilder = StringBuilder()): String {
        if (node == null) return sb.toString()
        val indent = "  ".repeat(depth)
        sb.append(indent)
            .append("cls=").append(node.className)
            .append(" id=").append(node.viewIdResourceName)
            .append(" text=").append(node.text)
            .append(" desc=").append(node.contentDescription)
            .append(" clickable=").append(node.isClickable)
            .append('\n')
        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), depth + 1, sb)
        }
        return sb.toString()
    }
}

/** Extracted, ready-to-render peek content plus where on screen the row was.
 *  ponytail: [messages] is only what the conversation ROW exposes (usually the
 *  latest snippet + labels) — reading the full thread would require opening the
 *  chat, which defeats the point of a peek. */
data class PeekContent(
    val sender: String,
    val messages: List<String>,
    val bounds: Rect,
)
