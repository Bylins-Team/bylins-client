package com.bylins.client.ui.scroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Буфер вывода по строкам.
 *
 * Прежний буфер был одной строкой, и соглашения о строках задавал он:
 * последняя строка — незавершённая, текст с переводом строки на конце
 * кончается пустой строкой, вставка перед промптом. Здесь проверяется, что
 * строковый буфер держит те же соглашения — на них стоят автоскролл,
 * выделение и seq, — и что снимок дешёвый: строки в нём те же объекты.
 */
class LineBufferTest {

    @Test
    fun `текст режется на строки, последняя — незавершённая`() {
        val buffer = LineBuffer(100)

        buffer.append("раз\nдва\nтри")

        assertEquals(listOf("раз", "два", "три"), buffer.snapshot().lines)
    }

    @Test
    fun `перевод строки на конце не заводит пустую строку`() {
        val buffer = LineBuffer(100)

        buffer.append("раз\n")

        assertEquals(listOf("раз"), buffer.snapshot().lines)

        // Строка появляется вместе с текстом, который в неё пришёл
        buffer.append("два")
        assertEquals(listOf("раз", "два"), buffer.snapshot().lines)
    }

    @Test
    fun `пустые строки внутри текста остаются`() {
        val buffer = LineBuffer(100)

        buffer.append("раз\n\nтри\n")

        assertEquals(listOf("раз", "", "три"), buffer.snapshot().lines)
    }

    @Test
    fun `ответ сервера не удлиняет буфер на лишнюю строку`() {
        // Так выглядит любая команда: в буфере промпт, ответ начинается с перевода
        // строки, который его закрывает. Раньше на этом месте появлялась пустая строка,
        // и весь вывод дёргался вверх на строку, а потом обратно.
        val buffer = LineBuffer(100)
        buffer.append("Базарная площадь\n")
        buffer.append("630H 179M Вых:СВЮЗ> ")
        val beforeAnswer = buffer.lineCount

        buffer.append("\r\n")

        assertEquals(beforeAnswer, buffer.lineCount, "лишняя пустая строка в конце")

        buffer.append("Вы посмотрели вокруг.\r\n630H 179M Вых:СВЮЗ> ")
        assertEquals(beforeAnswer + 2, buffer.lineCount)
        assertEquals("630H 179M Вых:СВЮЗ> ", buffer.lastLine)
    }

    @Test
    fun `следующий кусок дописывается к незавершённой строке`() {
        val buffer = LineBuffer(100)

        buffer.append("Вых:")
        buffer.append("СВЮЗ>")
        buffer.append("\nдальше")

        assertEquals(listOf("Вых:СВЮЗ>", "дальше"), buffer.snapshot().lines)
    }

    @Test
    fun `вставка перед промптом — промпт остаётся последним`() {
        val buffer = LineBuffer(100)
        buffer.append("комната\nВых:>")

        buffer.insertBeforeIncomplete("[#perf] отчёт\nвторая строка")

        assertEquals(listOf("комната", "[#perf] отчёт", "вторая строка", "Вых:>"), buffer.snapshot().lines)
    }

    @Test
    fun `вставка без промпта — просто добавление завершённой строкой`() {
        val buffer = LineBuffer(100)
        buffer.append("комната\n")

        buffer.insertBeforeIncomplete("сообщение")

        assertEquals(listOf("комната", "сообщение"), buffer.snapshot().lines)
    }

    @Test
    fun `вставка в пустой буфер`() {
        val buffer = LineBuffer(100)

        buffer.insertBeforeIncomplete("сообщение")

        assertEquals(listOf("сообщение"), buffer.snapshot().lines)
    }

    @Test
    fun `лишние строки вытесняются сверху, номер первой растёт`() {
        val buffer = LineBuffer(3)

        buffer.append("1\n2\n3\n4\n5")

        val snapshot = buffer.snapshot()
        assertEquals(listOf("3", "4", "5"), snapshot.lines)
        assertEquals(2L, snapshot.firstSeq)
        assertEquals(4L, snapshot.lastSeq)
    }

    @Test
    fun `уменьшение предела вытесняет при следующем добавлении`() {
        val buffer = LineBuffer(100)
        buffer.append("1\n2\n3\n4\n5")

        buffer.maxLines = 2
        buffer.append("6")

        assertEquals(listOf("4", "56"), buffer.snapshot().lines)
        assertEquals(3L, buffer.firstSeq)
    }

    @Test
    fun `очистка считает строки вытесненными — номера монотонны`() {
        val buffer = LineBuffer(100)
        buffer.append("1\n2\n3")

        buffer.clear()
        buffer.append("4")

        assertEquals(3L, buffer.snapshot().firstSeq)
        assertEquals(listOf("4"), buffer.snapshot().lines)
    }

    @Test
    fun `снимок не меняется после следующих добавлений`() {
        val buffer = LineBuffer(100)
        buffer.append("1\n2")
        val before = buffer.snapshot()

        buffer.append("хвост\n3")

        assertEquals(listOf("1", "2"), before.lines)
        assertEquals(listOf("1", "2хвост", "3"), buffer.snapshot().lines)
    }

    @Test
    fun `неизменённые строки в снимках — те же объекты`() {
        // На этом стоит вся цена обновления: разбор, разметка и поиск
        // сверяют строки по ссылке и переделывают только изменившиеся
        val buffer = LineBuffer(100)
        buffer.append("1\n2\nпромпт")
        val before = buffer.snapshot()

        buffer.append(">")
        val after = buffer.snapshot()

        assertSame(before.lines[0], after.lines[0])
        assertSame(before.lines[1], after.lines[1])
        assertEquals("промпт>", after.lines[2])
    }

    @Test
    fun `пустой текст ничего не меняет`() {
        val buffer = LineBuffer(100)

        buffer.append("")

        assertTrue(buffer.isEmpty)
        assertTrue(buffer.snapshot().isEmpty)
    }

    @Test
    fun `снимок из текста — как из пустого буфера`() {
        assertEquals(listOf("а", "б", ""), LineSnapshot.of("а\nб\n").lines)
        assertTrue(LineSnapshot.of("").isEmpty)
        assertEquals(7L, LineSnapshot.of("а", firstSeq = 7).firstSeq)
    }
}
