package top.pmh13.mctier.ui

import org.junit.Assert.*
import org.junit.Test

class ChatMediaLayoutTest {
    @Test fun voiceWidthIsBounded() {
        assertEquals(144f, voiceBubbleWidth(0f), .01f)
        assertTrue(voiceBubbleWidth(4f) > voiceBubbleWidth(1f))
        assertEquals(240f, voiceBubbleWidth(10f), .01f)
        assertEquals(voiceBubbleWidth(10f), voiceBubbleWidth(60f), .01f)
        assertEquals(144f, voiceBubbleWidth(Float.NaN), .01f)
    }
    @Test fun emptySheetsAreHidden() {
        val sections = listOf("--- 1 ---\n\t\n", "--- 2 ---\n0\tvalue", "--- 3 ---\n")
        assertEquals(listOf(sections[1]), nonEmptySheets(sections))
    }
}
