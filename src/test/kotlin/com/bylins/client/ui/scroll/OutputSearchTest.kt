package com.bylins.client.ui.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OutputSearchTest {

    private val text = "foo bar Foo baz foo"
    private val lines = listOf("foo bar", "Foo baz", "foo")

    private fun OutputSearch.update(query: String, lines: List<CharSequence> = this@OutputSearchTest.lines, firstSeq: Long = 0) =
        update(query, firstSeq, lines)

    @Test
    fun `plain case-insensitive finds all`() {
        val m = OutputSearch.findMatches(text, "foo", caseSensitive = false, useRegex = false)
        assertEquals(listOf(0 to 3, 8 to 11, 16 to 19), m)
    }

    @Test
    fun `plain case-sensitive respects case`() {
        val m = OutputSearch.findMatches(text, "Foo", caseSensitive = true, useRegex = false)
        assertEquals(listOf(8 to 11), m)
    }

    @Test
    fun `empty query or text yields nothing`() {
        assertTrue(OutputSearch.findMatches(text, "", false, false).isEmpty())
        assertTrue(OutputSearch.findMatches("", "foo", false, false).isEmpty())
    }

    @Test
    fun `no match yields nothing`() {
        assertTrue(OutputSearch.findMatches(text, "zzz", false, false).isEmpty())
    }

    @Test
    fun `non-overlapping matches`() {
        val m = OutputSearch.findMatches("aaaa", "aa", false, false)
        assertEquals(listOf(0 to 2, 2 to 4), m)
    }

    @Test
    fun `regex matches`() {
        val m = OutputSearch.findMatches("a1 b22 c333", "\\d+", false, true)
        assertEquals(listOf(1 to 2, 4 to 6, 8 to 11), m)
    }

    @Test
    fun `invalid regex yields nothing and no crash`() {
        assertTrue(OutputSearch.findMatches(text, "(unclosed", false, true).isEmpty())
    }

    @Test
    fun `update sets matches by line and selects first`() {
        val s = OutputSearch()
        s.update("foo")
        assertEquals(listOf(SearchMatch(0, 0, 3), SearchMatch(1, 0, 3), SearchMatch(2, 0, 3)), s.matches)
        assertEquals(0, s.currentIndex)
        assertEquals(SearchMatch(0, 0, 3), s.current)
        assertTrue(s.isActive)
    }

    @Test
    fun `matches carry absolute line numbers`() {
        val s = OutputSearch()
        s.update("baz", firstSeq = 40)
        assertEquals(listOf(SearchMatch(41, 4, 7)), s.matches)
    }

    @Test
    fun `next and prev wrap around`() {
        val s = OutputSearch()
        s.update("foo")
        s.next(); assertEquals(1, s.currentIndex)
        s.next(); assertEquals(2, s.currentIndex)
        s.next(); assertEquals(0, s.currentIndex) // wrap
        s.prev(); assertEquals(2, s.currentIndex) // wrap back
    }

    @Test
    fun `update keeps current place when query unchanged and text grows`() {
        val s = OutputSearch()
        s.update("foo")
        s.next() // строка 1
        s.update("foo", lines + "foo again") // тот же запрос, больше текста
        assertEquals(1, s.currentIndex)
        assertEquals(4, s.count)
    }

    @Test
    fun `current place stays at its line when a line is inserted before the prompt`() {
        // addLocalOutput вставляет строку перед промптом: место (номер строки,
        // столбец) помнится, и текущим становится ближайшее совпадение не
        // раньше него — во вставленной строке, а не первое сверху
        val s = OutputSearch()
        s.update("foo")
        s.next(); s.next() // строка 2, промпт
        s.update("foo", listOf("foo bar", "Foo baz", "[отчёт] foo", "foo"))
        assertEquals(SearchMatch(2, 8, 11), s.current)
    }

    @Test
    fun `current place survives eviction from the top`() {
        val s = OutputSearch()
        s.update("foo")
        s.next() // строка 1
        s.update("foo", lines.drop(1) + "foo tail", firstSeq = 1)
        assertEquals(SearchMatch(1, 0, 3), s.current)
        assertEquals(0, s.currentIndex)
    }

    @Test
    fun `update resets index when query changes`() {
        val s = OutputSearch()
        s.update("foo")
        s.next()
        s.update("bar")
        assertEquals(0, s.currentIndex)
        assertEquals(1, s.count)
    }

    @Test
    fun `unchanged lines are not scanned again`() {
        // На приход текста заново просматриваются только изменившиеся строки:
        // иначе открытый поиск стоил бы прохода по всей истории на каждый кусок
        val s = OutputSearch()
        val spy = SpyLine("foo spied")
        s.update("foo", listOf("foo bar", spy))
        val scannedBefore = spy.reads

        s.update("foo", listOf("foo bar", spy, "foo new"))

        assertEquals(scannedBefore, spy.reads)
        assertEquals(3, s.count)
    }

    @Test
    fun `changed options rescan everything`() {
        val s = OutputSearch()
        val spy = SpyLine("Foo spied")
        s.update("foo", listOf(spy))
        val scannedBefore = spy.reads

        s.caseSensitive = true
        s.update("foo", listOf(spy))

        assertTrue(spy.reads > scannedBefore)
        assertEquals(0, s.count)
    }

    @Test
    fun `matchesBetween returns matches of the line range`() {
        val s = OutputSearch()
        s.update("foo", firstSeq = 10)
        assertEquals(listOf(SearchMatch(11, 0, 3)), s.matchesBetween(11, 11))
        assertEquals(listOf(SearchMatch(11, 0, 3), SearchMatch(12, 0, 3)), s.matchesBetween(11, 50))
        assertTrue(s.matchesBetween(20, 30).isEmpty())
        assertTrue(s.matchesBetween(12, 11).isEmpty())
    }

    @Test
    fun `no matches gives index -1 and null current`() {
        val s = OutputSearch()
        s.update("zzz")
        assertEquals(-1, s.currentIndex)
        assertNull(s.current)
    }

    @Test
    fun `regex error flag set on invalid pattern`() {
        val s = OutputSearch()
        s.useRegex = true
        s.update("(bad")
        assertTrue(s.regexError)
        assertEquals(0, s.count)
    }

    @Test
    fun `clear resets state`() {
        val s = OutputSearch()
        s.update("foo")
        s.clear()
        assertEquals("", s.query)
        assertEquals(0, s.count)
        assertEquals(-1, s.currentIndex)
        assertFalse(s.isActive)
    }

    /** Строка, считающая обращения к своему тексту: просматривали её или нет. */
    private class SpyLine(private val text: String) : CharSequence {
        var reads = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char = text[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
        override fun toString(): String { reads++; return text }
    }
}
