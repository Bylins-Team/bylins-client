package com.bylins.client.ui.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputSelectionTest {

    private val lines = listOf("abc", "def", "ghi") // seq 0..2 при firstSeq=0

    private fun OutputSelection.copy(firstSeq: Long = 0, lines: List<String> = this@OutputSelectionTest.lines) =
        copyText(firstSeq, lines.size) { lines[it] }

    @Test
    fun `fresh selection is empty`() {
        val s = OutputSelection()
        assertTrue(s.isEmpty)
        assertNull(s.normalized())
        assertEquals("", s.copy())
    }

    @Test
    fun `single click without drag selects nothing`() {
        val s = OutputSelection()
        s.start(SelPoint(1, 1))
        assertTrue(s.isEmpty)
        assertNull(s.normalized())
    }

    @Test
    fun `selection is normalized regardless of drag direction`() {
        val s = OutputSelection()
        s.start(SelPoint(2, 1))
        s.extendTo(SelPoint(0, 0)) // тянем вверх
        assertEquals("abc\ndef\ng", s.copy())
    }

    @Test
    fun `selection spans multiple lines`() {
        val s = OutputSelection()
        s.start(SelPoint(0, 1))
        s.extendTo(SelPoint(1, 2))
        assertEquals(SelPoint(0, 1) to SelPoint(1, 2), s.normalized())
        assertEquals("bc\nde", s.copy())
    }

    @Test
    fun `selection within one line`() {
        val s = OutputSelection()
        s.start(SelPoint(1, 0))
        s.extendTo(SelPoint(1, 2))
        assertEquals("de", s.copy())
    }

    @Test
    fun `column beyond line end is clamped`() {
        val s = OutputSelection()
        s.start(SelPoint(0, 0))
        s.extendTo(SelPoint(0, 100))
        assertEquals("abc", s.copy())
    }

    @Test
    fun `selection start clamps to buffer start after eviction`() {
        val s = OutputSelection()
        s.start(SelPoint(0, 1))   // строка, которая позже будет вытеснена
        s.extendTo(SelPoint(2, 2))
        // firstSeq=2 → строки 0,1 вытеснены, в буфере только "ghi"
        assertEquals("gh", s.copy(firstSeq = 2, lines = listOf("ghi")))
    }

    @Test
    fun `selection entirely below buffer copies nothing`() {
        val s = OutputSelection()
        s.start(SelPoint(10, 0))
        s.extendTo(SelPoint(12, 3))
        assertEquals("", s.copy())
    }

    @Test
    fun `selectAll covers whole buffer`() {
        val s = OutputSelection()
        s.selectAll(firstSeq = 0, lineCount = 3)
        assertEquals("abc\ndef\nghi", s.copy())
    }

    @Test
    fun `selectAll on empty buffer clears`() {
        val s = OutputSelection()
        s.start(SelPoint(0, 0))
        s.extendTo(SelPoint(1, 1))
        s.selectAll(firstSeq = 0, lineCount = 0)
        assertTrue(s.isEmpty)
    }

    @Test
    fun `clear empties selection`() {
        val s = OutputSelection()
        s.start(SelPoint(0, 0))
        s.extendTo(SelPoint(1, 1))
        s.clear()
        assertTrue(s.isEmpty)
        assertEquals("", s.copy())
    }
}
