package com.peekpreview.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeExtractionUtilsTest {

    @Test fun empty_returns_null() {
        assertNull(NodeExtractionUtils.selectSenderAndBody(emptyList()))
    }

    @Test fun single_piece_doubles_as_sender_and_body() {
        assertEquals("Alex" to "Alex", NodeExtractionUtils.selectSenderAndBody(listOf("Alex")))
    }

    @Test fun picks_first_as_sender_and_longest_rest_as_body() {
        val pieces = listOf("Alex Rivera", "9:41", "Running about 10 min late — grab us a table?")
        assertEquals(
            "Alex Rivera" to "Running about 10 min late — grab us a table?",
            NodeExtractionUtils.selectSenderAndBody(pieces),
        )
    }

    @Test fun chat_controls_are_excluded_by_class() {
        listOf(
            "android.widget.Button",
            "android.widget.ImageButton",
            "android.widget.EditText",
        ).forEach { assertTrue("expected control: $it", NodeExtractionUtils.isChatControl(it)) }

        listOf("android.widget.TextView", "android.view.ViewGroup", null).forEach {
            assertFalse("expected content node: $it", NodeExtractionUtils.isChatControl(it))
        }
    }

    @Test fun composer_labels_are_rejected_but_messages_kept() {
        // Messenger/Instagram composer + call-bar contentDescriptions.
        listOf(
            "Open gallery",
            "Record a voice clip",
            "Send message",
            "Choose an emoji or sticker",
            "Send a like or press and hold for more options",
            "Voice message",
            "Audio call",
            "Video call",
        ).forEach { assertTrue("expected chrome: $it", NodeExtractionUtils.isComposerLabel(it)) }

        listOf(
            "Running about 10 min late — grab us a table?",
            "See you tomorrow at the gym",
            "ok",
        ).forEach { assertFalse("expected message: $it", NodeExtractionUtils.isComposerLabel(it)) }
    }

    @Test fun date_headers_and_profile_links_are_noise() {
        listOf(
            "Today at 9:41 PM",
            "Yesterday 21:30",
            "Jul 10, 2026, 9:41 PM",
            "THU 9:41 PM",
            "Wednesday",
            "Open Sarah Smith's profile",
            "Open profile",
        ).forEach { assertTrue("expected noise: $it", NodeExtractionUtils.isChatNoise(it)) }

        listOf(
            "Meet me at 9:41 tonight",
            "The profile looks good",
            "Open the door when you arrive",
            "See you Sunday!",
        ).forEach { assertFalse("expected message: $it", NodeExtractionUtils.isChatNoise(it)) }
    }

    @Test fun structural_labels_are_filtered() {
        // timestamps, relative times, single emoji/symbols -> structural
        listOf("9:41", "9:41 PM", "5m", "2h", "Now", "Yesterday", "😂", "•").forEach {
            assertTrue("expected structural: $it", NodeExtractionUtils.isStructuralLabel(it))
        }
        // real content -> kept
        listOf("Alex Rivera", "See you at 9:41 tonight", "ok").forEach {
            assertFalse("expected content: $it", NodeExtractionUtils.isStructuralLabel(it))
        }
    }
}
