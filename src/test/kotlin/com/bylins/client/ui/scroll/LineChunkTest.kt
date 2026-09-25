package com.bylins.client.ui.scroll

import com.bylins.client.ui.AnsiParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Куски буфера: закрытый кусок не меняется и в следующих снимках остаётся
 * тем же объектом, а разбор берёт его целиком, не глядя на строки.
 *
 * Это и есть цена обновления при большой истории: построчная сверка по
 * ссылкам по всему буферу стоила 2–4 мс на ста тысячах строк промахами по
 * памяти; по кускам — сотни сравнений вместо ста тысяч.
 */
class LineChunkTest {

    private val size = LineChunk.SIZE

    @Test
    fun `заполнившийся хвост закрывается куском, и кусок в снимках тот же`() {
        val buffer = LineBuffer(10_000)
        repeat(size + 1) { buffer.append("строка $it\n") }
        val before = buffer.snapshot()

        buffer.append("ещё\n")
        val after = buffer.snapshot()

        assertEquals(2, before.chunks.size)
        assertSame(before.chunks[0], after.chunks[0])
        assertEquals(size, after.chunks[0].size)
        assertEquals("строка 0", after.lines[0])
        assertEquals("ещё", after.lines[size + 1])
    }

    @Test
    fun `вытеснение сдвигает начало первого куска, не переписывая его`() {
        val buffer = LineBuffer(size + 10)
        repeat(size + 20) { buffer.append("строка $it\n") }
        val before = buffer.snapshot()

        buffer.append("ещё\n")
        val after = buffer.snapshot()

        assertSame(before.chunks[0], after.chunks[0])
        assertEquals(before.offset + 1, after.offset)
        assertEquals(before.firstSeq + 1, after.firstSeq)
        assertEquals(size + 10, after.lineCount)
        assertEquals("строка ${after.firstSeq}", after.lines[0])
    }

    @Test
    fun `полностью вытесненный кусок уходит, номера строк не сбиваются`() {
        val buffer = LineBuffer(size)
        repeat(3 * size) { buffer.append("строка $it\n") }

        val snapshot = buffer.snapshot()

        assertEquals(size, snapshot.lineCount)
        for (i in 0 until size) assertEquals("строка ${snapshot.firstSeq + i}", snapshot.lines[i], "строка $i")
        assertTrue(snapshot.chunks.all { it.size == size } || snapshot.chunks.last().size < size)
        assertEquals(snapshot.chunks[0].firstSeq + snapshot.offset, snapshot.firstSeq)
    }

    @Test
    fun `вставка перед промптом переливает хвост через границу куска`() {
        val buffer = LineBuffer(10_000)
        repeat(size - 1) { buffer.append("строка $it\n") }
        buffer.append("Вых:>")

        buffer.insertBeforeIncomplete("раз\nдва\nтри")

        val snapshot = buffer.snapshot()
        assertEquals(size + 3, snapshot.lineCount)
        assertEquals(listOf("раз", "два", "три", "Вых:>"), (size - 1 until size + 3).map { snapshot.lines[it] })
        assertEquals(size, snapshot.chunks[0].size)
    }

    @Test
    fun `очистка и заполнение заново — куски с новыми номерами`() {
        val buffer = LineBuffer(10_000)
        repeat(size + 5) { buffer.append("строка $it\n") }
        buffer.clear()
        buffer.append("новая")

        val snapshot = buffer.snapshot()

        assertEquals((size + 5).toLong(), snapshot.firstSeq)
        assertEquals(listOf("новая"), snapshot.lines)
    }

    @Test
    fun `снимок из списка режется на куски по размеру`() {
        val lines = (0 until 2 * size + 3).map { "с$it" }

        val snapshot = LineSnapshot(7, lines)

        assertEquals(listOf(size, size, 3), snapshot.chunks.map { it.size })
        assertEquals(listOf(7L, 7L + size, 7L + 2 * size), snapshot.chunks.map { it.firstSeq })
        assertEquals(lines, snapshot.lines)
    }

    @Test
    fun `разбор берёт неизменённый кусок целиком, а хвост — построчно`() {
        val parser = AnsiParser()
        val cache = LineParseCache { text, state -> parser.parse(text, state) }
        val buffer = LineBuffer(10_000)
        repeat(size + 3) { buffer.append("\u001B[32mстрока $it\u001B[0m\n") }
        val before = cache.update(buffer.snapshot())

        buffer.append("хвост")
        val after = cache.update(buffer.snapshot())

        assertSame(before.chunks[0], after.chunks[0])
        assertNotSame(before.chunks[1], after.chunks[1])
        assertEquals(1, after.parsedCount)
        assertSame(before.lines[size + 1], after.lines[size + 1])
    }

    @Test
    fun `первый кусок после вытеснения остаётся со своим состоянием на входе`() {
        // Текст перед первым куском вытеснен; состояние, с которым его
        // разбирали, — единственно верное, и кусок не разбирается заново
        val parser = AnsiParser()
        val cache = LineParseCache { text, state -> parser.parse(text, state) }
        val buffer = LineBuffer(size + 5)
        buffer.append("\u001B[31m")
        repeat(size + 4) { buffer.append("строка $it\n") }
        val before = cache.update(buffer.snapshot())

        buffer.append("ещё\n")
        val after = cache.update(buffer.snapshot())

        assertSame(before.chunks[0], after.chunks[0])
        // Разобрана только дописанная строка
        assertEquals(1, after.parsedCount)
        assertEquals(1, after.lines[0].annotated.spanStyles.size, "цвет из вытесненного текста сохранился")
    }

    @Test
    fun `сменился выход предыдущего куска — следующий разбирается заново`() {
        val parser = AnsiParser()
        val cache = LineParseCache { text, state -> parser.parse(text, state) }
        val chunkA = LineChunk(0, Array(size) { "строка $it" })
        val chunkB = LineChunk(size.toLong(), arrayOf("хвост"))
        cache.update(LineSnapshot(0L, listOf(chunkA, chunkB), 0))

        val recolored = LineChunk(0, Array(size) { if (it == size - 1) "\u001B[32mстрока $it" else "строка $it" })
        val after = cache.update(LineSnapshot(0L, listOf(recolored, chunkB), 0))

        assertEquals(2, after.parsedCount)
        assertEquals(1, after.lines[size].annotated.spanStyles.size)
    }
}
