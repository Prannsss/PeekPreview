package com.peekpreview.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pulls readable text out of an accessibility node subtree.
 *
 * IMPORTANT LIMITATION: what's available depends entirely on what each app
 * exposes in its accessibility tree, which is not a public contract and can
 * change with any app update. Google Messages surfaces sender/snippet/timestamp
 * as clean text nodes. Meta's apps (Messenger, Instagram) are React Native, so
 * they often DON'T assign stable resource-ids and lean on contentDescription
 * instead — that's why collect() reads text OR contentDescription from every
 * node rather than looking up known ids. We filter out purely structural labels
 * (bare timestamps, single emoji) so the body pick isn't a "5m" or a "😂".
 *
 * Degrades gracefully: if nothing usable is found, returns null and the caller
 * shows nothing rather than an empty bubble.
 */
object NodeExtractionUtils {

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

        // Heuristic: the first piece is usually the sender/title, the longest of
        // the rest is usually the message snippet. Timestamps are short, so the
        // longest remaining string is a reasonable "body" pick.
        val (sender, body) = selectSenderAndBody(pieces.toList()) ?: return null
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        return PeekContent(sender = sender, snippet = body, bounds = bounds)
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

/** Extracted, ready-to-render peek content plus where on screen the row was. */
data class PeekContent(
    val sender: String,
    val snippet: String,
    val bounds: Rect,
)
