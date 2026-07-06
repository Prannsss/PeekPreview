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
