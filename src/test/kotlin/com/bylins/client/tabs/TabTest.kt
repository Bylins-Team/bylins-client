package com.bylins.client.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class TabTest {

    @Test
    fun `tab starts with empty content`() {
        val tab = Tab(id = "test", name = "Test")
        assertEquals("", tab.snapshot.value.text())
    }

    @Test
    fun `appendText adds text to tab`() {
        val tab = Tab(id = "test", name = "Test")
        tab.appendText("Hello")

        assertEquals("Hello", tab.snapshot.value.text())
    }

    @Test
    fun `appendText handles multiple lines`() {
        val tab = Tab(id = "test", name = "Test")
        tab.appendText("Line1\nLine2")

        assertTrue(tab.snapshot.value.text().contains("Line1"))
        assertTrue(tab.snapshot.value.text().contains("Line2"))
    }

    @Test
    fun `content is published immediately`() {
        val tab = Tab(id = "test", name = "Test")
        tab.appendText("First")

        assertEquals("First", tab.snapshot.value.text())

        tab.appendText("Second")

        assertTrue(tab.snapshot.value.text().contains("Second"))
    }

    @Test
    fun `clear removes all content`() {
        val tab = Tab(id = "test", name = "Test")
        tab.appendText("Some content")
        tab.clear()

        assertEquals("", tab.snapshot.value.text())
    }

    @Test
    fun `tab respects maxLines limit`() {
        val tab = Tab(id = "test", name = "Test", maxLines = 5)

        for (i in 1..10) {
            tab.appendText("Line $i")
        }

        val lines = tab.snapshot.value.text().lines()
        assertTrue(lines.size <= 5)
    }

    @Test
    fun `captureAndTransform returns null for non-matching line`() {
        val tab = Tab(
            id = "test",
            name = "Test",
            filters = listOf(TabFilter("pattern".toRegex()))
        )

        val result = tab.captureAndTransform("no match here", "no match here")
        assertNull(result)
    }

    @Test
    fun `captureAndTransform returns line for matching pattern`() {
        val tab = Tab(
            id = "test",
            name = "Test",
            filters = listOf(TabFilter("hello".toRegex()))
        )

        val result = tab.captureAndTransform("hello world", "hello world")
        assertEquals("hello world", result)
    }

    @Test
    fun `captureAndTransform applies replacement`() {
        val tab = Tab(
            id = "test",
            name = "Test",
            filters = listOf(TabFilter("(\\w+) says: (.+)".toRegex(), replacement = "[$1] $2"))
        )

        val result = tab.captureAndTransform("John says: hello", "John says: hello")
        assertEquals("[John] hello", result)
    }

    @Test
    fun `captureAndTransform matches with colors when enabled`() {
        val tab = Tab(
            id = "test",
            name = "Test",
            filters = listOf(TabFilter("\u001B\\[32m".toRegex(), matchWithColors = true))
        )

        val cleanLine = "Green text"
        val rawLine = "\u001B[32mGreen text\u001B[0m"

        val result = tab.captureAndTransform(cleanLine, rawLine)
        assertEquals(rawLine, result)
    }

    @Test
    fun `captureAndTransform does not match colors when disabled`() {
        val tab = Tab(
            id = "test",
            name = "Test",
            filters = listOf(TabFilter("\u001B\\[32m".toRegex(), matchWithColors = false))
        )

        val cleanLine = "Green text"
        val rawLine = "\u001B[32mGreen text\u001B[0m"

        val result = tab.captureAndTransform(cleanLine, rawLine)
        assertNull(result) // Should not match because matchWithColors is false
    }

    @Test
    fun `consecutive empty lines are skipped`() {
        val tab = Tab(id = "test", name = "Test")
        tab.appendText("Line1\n\n\n\nLine2")

        val content = tab.snapshot.value.text()
        // Should not have multiple consecutive empty lines
        assertTrue(!content.contains("\n\n\n"))
    }
}

class TabSnapshotTest {

    @Test
    fun `snapshot is empty before any append`() {
        val tab = Tab(id = "test", name = "Test")
        val snap = tab.snapshot.value
        assertEquals("", snap.text())
        assertEquals(0L, snap.firstSeq)
        assertEquals(0, snap.lineCount)
    }

    @Test
    fun `snapshot reflects text firstSeq and lineCount after append`() {
        val tab = Tab(id = "test", name = "Test")
        tab.appendText("a\nb\nc")

        val snap = tab.snapshot.value
                assertEquals(0L, snap.firstSeq)
        assertEquals(3, snap.lineCount)
    }

    @Test
    fun `firstSeq is zero while buffer is under maxLines`() {
        val tab = Tab(id = "test", name = "Test", maxLines = 100)
        for (i in 1..10) tab.appendText("Line $i")

        assertEquals(0L, tab.snapshot.value.firstSeq)
        assertEquals(10, tab.snapshot.value.lineCount)
    }

    @Test
    fun `firstSeq increments by number of evicted lines`() {
        val tab = Tab(id = "test", name = "Test", maxLines = 5)
        for (i in 1..10) tab.appendText("Line $i")

        val snap = tab.snapshot.value
        assertEquals(5, snap.lineCount)
        assertEquals(5L, snap.firstSeq) // 10 строк добавлено, 5 вытеснено
        assertEquals(9L, snap.geometry.lastSeq)
    }

    @Test
    fun `clear keeps seq monotonic`() {
        val tab = Tab(id = "test", name = "Test")
        tab.appendText("a\nb\nc")
        tab.clear()

        val snap = tab.snapshot.value
        assertEquals("", snap.text())
        assertEquals(0, snap.lineCount)
        assertEquals(3L, snap.firstSeq) // 3 строки «вытеснены» очисткой
    }
}

class TabDtoTest {

    @Test
    fun `TabDto toTab creates correct Tab`() {
        val dto = TabDto(
            id = "test",
            name = "Test Tab",
            captureMode = "COPY",
            maxLines = 500
        )

        val tab = dto.toTab()

        assertEquals("test", tab.id)
        assertEquals("Test Tab", tab.name)
        assertEquals(CaptureMode.COPY, tab.captureMode)
        assertEquals(500, tab.maxLines)
    }

    @Test
    fun `TabDto toTab restores content`() {
        val dto = TabDto(
            id = "test",
            name = "Test",
            content = "Saved content"
        )

        val tab = dto.toTab()

        assertEquals("Saved content", tab.snapshot.value.text())
    }

    @Test
    fun `TabDto fromTab preserves all fields`() {
        val tab = Tab(
            id = "test",
            name = "Test Tab",
            captureMode = CaptureMode.MOVE,
            maxLines = 300,
            persistContent = true
        )
        tab.appendText("Some content")

        val dto = TabDto.fromTab(tab)

        assertEquals("test", dto.id)
        assertEquals("Test Tab", dto.name)
        assertEquals("MOVE", dto.captureMode)
        assertEquals(300, dto.maxLines)
        assertEquals("Some content", dto.content)
        assertTrue(dto.persistContent)
    }

    @Test
    fun `TabDto does not save log unless tab allows it`() {
        // По умолчанию лог не сохраняется: иначе серверный текст утекал бы
        // в общий конфиг и появлялся на других серверах
        val tab = Tab(id = "test", name = "Test Tab")
        tab.appendText("Some content")

        assertNull(TabDto.fromTab(tab).content)
    }

    @Test
    fun `profile log is stored in profile, not in tab definition`() {
        // Глобальная вкладка с профильным логом: сам лог хранится в профиле
        // сервера (ConnectionProfile.tabLogs), поэтому в TabDto его быть не должно
        val tab = Tab(
            id = "test",
            name = "Test Tab",
            profileLog = true,
            persistContent = true
        )
        tab.appendText("Some content")

        assertNull(TabDto.fromTab(tab).content)
    }

    @Test
    fun `profile tab keeps its log inside profile definition`() {
        // Профильная вкладка целиком лежит в профиле — лог хранится вместе с ней
        val tab = Tab(
            id = "test",
            name = "Test Tab",
            profileTab = true,
            profileLog = true,
            persistContent = true
        )
        tab.appendText("Some content")

        assertEquals("Some content", TabDto.fromTab(tab).content)
    }

    @Test
    fun `TabDto handles ONLY captureMode gracefully`() {
        // ONLY was removed, should default to COPY
        val dto = TabDto(
            id = "test",
            name = "Test",
            captureMode = "ONLY"
        )

        val tab = dto.toTab()
        assertEquals(CaptureMode.COPY, tab.captureMode)
    }
}

class TabFilterTest {

    @Test
    fun `transform returns null for non-matching text`() {
        val filter = TabFilter("hello".toRegex())
        val result = filter.transform("goodbye", "goodbye")
        assertNull(result)
    }

    @Test
    fun `transform returns rawLine for matching text without replacement`() {
        val filter = TabFilter("hello".toRegex())
        val result = filter.transform("hello world", "\u001B[32mhello world\u001B[0m")
        assertEquals("\u001B[32mhello world\u001B[0m", result)
    }

    @Test
    fun `transform applies replacement with capture groups`() {
        val filter = TabFilter("(\\d+) items".toRegex(), replacement = "Count: $1")
        val result = filter.transform("You have 42 items", "You have 42 items")
        assertEquals("Count: 42", result)
    }

    @Test
    fun `transform uses rawLine when matchWithColors is true`() {
        val filter = TabFilter("\u001B".toRegex(), matchWithColors = true)
        val result = filter.transform("clean", "\u001B[32mcolored\u001B[0m")
        assertEquals("\u001B[32mcolored\u001B[0m", result)
    }

    @Test
    fun `transform uses cleanLine when matchWithColors is false`() {
        val filter = TabFilter("colored".toRegex(), matchWithColors = false)
        val result = filter.transform("colored", "\u001B[32mcolored\u001B[0m")
        assertEquals("\u001B[32mcolored\u001B[0m", result)
    }
}
