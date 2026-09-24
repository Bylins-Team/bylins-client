package com.bylins.client.ui.scroll

import androidx.compose.ui.graphics.Color
import com.bylins.client.ui.AnsiParser
import com.bylins.client.ui.AnsiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Разбор ANSI по строкам с переносом состояния.
 *
 * Смысл: разбирать заново только изменившиеся строки, не теряя цвет,
 * который сервер включил в одной строке и выключил в другой.
 */
class LineParseCacheTest {

    private val esc = '\u001B'
    private val parser = AnsiParser()
    private val cache = LineParseCache { text, state -> parser.parse(text, state) }

    private fun snapshot(firstSeq: Long, vararg lines: String) = LineSnapshot(firstSeq, lines.toList())

    @Test
    fun `первое обновление разбирает всё`() {
        val window = cache.update(snapshot(0, "а", "б", "в"))

        assertEquals(3, window.parsedCount)
        assertEquals(listOf("а", "б", "в"), window.lines.map { it.plain })
    }

    @Test
    fun `дописанный промпт разбирается заново, остальные — те же объекты`() {
        val first = cache.update(snapshot(0, "комната", "Вых:"))

        val second = cache.update(snapshot(0, "комната", "Вых:СВЮЗ>"))

        assertEquals(1, second.parsedCount)
        assertSame(first.lines[0], second.lines[0])
        assertEquals("Вых:СВЮЗ>", second.lines[1].plain)
    }

    @Test
    fun `цвет переносится между строками`() {
        val window = cache.update(snapshot(0, "$esc[32mзелёная", "и эта тоже$esc[0m", "а эта нет"))

        assertEquals(Color(0xFF00CD00), window.lines[1].annotated.spanStyles.single().item.color)
        assertEquals(0, window.lines[2].annotated.spanStyles.size)
        assertEquals(AnsiState.RESET, window.lines[2].stateOut)
    }

    @Test
    fun `сменилось состояние на конце предыдущей — следующая разбирается заново`() {
        // Промпт без перевода строки получил цвет: строка после него (её
        // вставит addLocalOutput) должна увидеть новый цвет на входе
        cache.update(snapshot(0, "$esc[32mзелёная", "хвост"))

        val window = cache.update(snapshot(0, "$esc[32mзелёная$esc[0m", "хвост"))

        assertEquals(2, window.parsedCount)
        assertEquals(0, window.lines[1].annotated.spanStyles.size)
    }

    @Test
    fun `вытеснение сверху сдвигает окно, разбор остаётся своим`() {
        val before = cache.update(snapshot(0, "а", "б", "в"))

        val after = cache.update(snapshot(1, "б", "в", "г"))

        assertEquals(1, after.parsedCount)
        assertSame(before.lines[1], after.lines[0])
        assertSame(before.lines[2], after.lines[1])
    }

    @Test
    fun `вставка перед промптом — сдвинутый промпт не путается с прежней строкой под тем же номером`() {
        val before = cache.update(snapshot(0, "а", "Вых:>"))

        val after = cache.update(snapshot(0, "а", "[отчёт]", "Вых:>"))

        assertEquals(listOf("а", "[отчёт]", "Вых:>"), after.lines.map { it.plain })
        assertSame(before.lines[0], after.lines[0])
        assertNotSame(before.lines[1], after.lines[1])
        assertEquals(2, after.parsedCount)
    }

    @Test
    fun `тот же текст другим объектом — разбор переиспользуется по содержимому`() {
        // Снимок из другого источника (восстановленный лог) — строки равны, но
        // не те же объекты; сверка по содержимому дешёвая и даёт тот же ответ
        val before = cache.update(LineSnapshot(0, listOf(String(charArrayOf('а')))))

        val after = cache.update(LineSnapshot(0, listOf(String(charArrayOf('а')))))

        assertEquals(0, after.parsedCount)
        assertSame(before.lines[0], after.lines[0])
    }

    @Test
    fun `пустой снимок`() {
        cache.update(snapshot(0, "а"))

        val window = cache.update(LineSnapshot.EMPTY)

        assertEquals(0, window.lineCount)
        assertEquals(0, window.parsedCount)
    }

    @Test
    fun `номер строки за пределами окна зажимается в окно`() {
        val window = cache.update(snapshot(10, "а", "б"))

        assertEquals(0, window.indexOfSeq(3))
        assertEquals(1, window.indexOfSeq(11))
        assertEquals(1, window.indexOfSeq(99))
    }
}
