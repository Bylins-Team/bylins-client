package com.bylins.client.ui.scroll

import androidx.compose.ui.text.AnnotatedString
import com.bylins.client.ui.AnsiParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Кэш разметки по строкам.
 *
 * Смысл всей затеи — размечать только то, что видно и изменилось: окно
 * вывода размечалось целиком на каждое обновление, и тысяча строк игры
 * стоила 84 мс, а сто тысяч живых разметок — 9 ГБ (#19). Здесь проверяется
 * именно это: что не изменилось — не размечается заново, что не видно —
 * не размечается вовсе, а высота, раз измеренная, не теряется.
 */
class LineLayoutCacheTest {

    /** Заглушка разметки: у каждой строки своя высота, чтобы видеть, чья она. */
    private class Layout(val text: String, val height: Float)

    private var measured = 0
    private val parser = AnsiParser()
    private val parseCache = LineParseCache { text, state -> parser.parse(text, state) }
    private val cache = LineLayoutCache<Layout>()

    private fun parsed(firstSeq: Long, vararg lines: String) = parseCache.update(LineSnapshot(firstSeq, lines.toList()))

    /** Оценка — 100 плюс длина, разметка — 10 плюс длина: по высоте видно, что было. */
    private fun update(
        window: ParsedWindow,
        wanted: List<IntRange> = listOf(0 until window.lineCount),
        key: Any = "w100"
    ) = cache.update(
        parsed = window,
        key = key,
        wanted = wanted,
        estimate = { 100f + it.length },
        measure = { a: AnnotatedString -> measured++; Layout(a.text, 10f + a.length) },
        heightOf = { it.height }
    )

    @Test
    fun `первое обновление размечает всё затребованное`() {
        val window = update(parsed(0, "а", "б", "в"))

        assertEquals(3, window.measuredCount)
        assertEquals(3, window.lineCount)
        assertEquals(listOf(0f, 11f, 22f), (0 until 3).map { window.topOf(it) })
        assertEquals(33f, window.totalHeight)
    }

    @Test
    fun `новая строка внизу — размечается только она`() {
        update(parsed(0, "а", "б", "в"))
        measured = 0

        val window = update(parsed(0, "а", "б", "в", "г"))

        assertEquals(1, window.measuredCount)
        assertEquals(1, measured)
    }

    @Test
    fun `неизменившаяся строка переиспользует ту же разметку`() {
        val first = update(parsed(0, "а", "б"))
        val second = update(parsed(0, "а", "б", "в"))

        assertSame(first.layoutAt(0), second.layoutAt(0))
        assertSame(first.layoutAt(1), second.layoutAt(1))
    }

    @Test
    fun `дописанный промпт размечается заново`() {
        update(parsed(0, "а", "Вых:"))
        measured = 0

        val window = update(parsed(0, "а", "Вых:СВЮЗ>"))

        assertEquals(1, window.measuredCount)
        assertEquals("Вых:СВЮЗ>", window.layoutAt(1)!!.text)
    }

    @Test
    fun `вставка перед промптом сдвигает номера, и сдвинутая строка не путается с прежней`() {
        update(parsed(0, "а", "Вых:>"))
        measured = 0

        val window = update(parsed(0, "а", "[#perf] отчёт", "Вых:>"))

        assertEquals(listOf("а", "[#perf] отчёт", "Вых:>"), (0 until 3).map { window.lineAt(it).plain })
        assertEquals(2, window.measuredCount)
    }

    @Test
    fun `вытеснение сверху сдвигает окно, а разметка остаётся своей`() {
        val before = update(parsed(0, "а", "б", "в"))
        measured = 0

        val after = update(parsed(1, "б", "в", "г"))

        assertEquals(1, after.measuredCount)
        assertSame(before.layoutAt(1), after.layoutAt(0))
        assertEquals(1L, after.firstSeq)
    }

    @Test
    fun `смена ширины или стиля перечёркивает весь кэш`() {
        update(parsed(0, "а", "б"), key = "w100")
        measured = 0

        val window = update(parsed(0, "а", "б"), key = "w200")

        assertEquals(2, window.measuredCount)
    }

    @Test
    fun `тот же текст с другим цветом — другая строка`() {
        val esc = '\u001B'
        update(parsed(0, "$esc[31mВых:>"))
        measured = 0

        val window = update(parsed(0, "$esc[32mВых:>"))

        assertEquals(1, window.measuredCount)
    }

    @Test
    fun `вне затребованных областей строки стоят по оценке и без разметки`() {
        val window = update(parsed(0, "а", "бб", "ввв", "гггг"), wanted = listOf(2..3))

        assertEquals(2, window.measuredCount)
        assertNull(window.layoutAt(0))
        assertNull(window.layoutAt(1))
        assertNotNull(window.layoutAt(2))
        // Оценки: 101, 102; разметки: 13, 14
        assertEquals(listOf(0f, 101f, 203f, 216f), (0 until 4).map { window.topOf(it) })
        assertEquals(230f, window.totalHeight)
        assertFalse(cache.isExact(0))
        assertTrue(cache.isExact(2))
    }

    @Test
    fun `вышедшая из области строка отпускает разметку, но высота остаётся точной`() {
        // Это и есть память: разметки живут только около вьюпорта, а высоты —
        // всегда, иначе стопка прыгала бы при каждом уходе строки из области
        update(parsed(0, "а", "бб", "ввв"), wanted = listOf(0..2))
        measured = 0

        val window = update(parsed(0, "а", "бб", "ввв"), wanted = listOf(2..2))

        assertNull(window.layoutAt(0))
        assertTrue(cache.isExact(0))
        assertFalse(cache.hasLayout(0))
        assertEquals(11f, window.heightOf(0))
        assertEquals(0, measured)
    }

    @Test
    fun `вернувшаяся в область строка размечается заново, но остаётся той же высоты`() {
        update(parsed(0, "а", "бб", "ввв"), wanted = listOf(0..2))
        update(parsed(0, "а", "бб", "ввв"), wanted = listOf(2..2))
        measured = 0

        val window = update(parsed(0, "а", "бб", "ввв"), wanted = listOf(0..0))

        assertEquals(1, measured)
        assertNotNull(window.layoutAt(0))
        assertEquals(11f, window.heightOf(0))
    }

    @Test
    fun `области сдвигаются вместе с окном при вытеснении`() {
        update(parsed(0, "а", "б", "в", "г"), wanted = listOf(2..3))
        measured = 0

        // Вытеснены две строки: прежняя область 2..3 — теперь 0..1, она же и затребована
        val window = update(parsed(2, "в", "г", "д"), wanted = listOf(0..1))

        assertEquals(0, measured)
        assertNotNull(window.layoutAt(0))
        assertNotNull(window.layoutAt(1))
    }

    @Test
    fun `разметка на месте запоминается в кэше`() {
        val window = update(parsed(0, "а", "бб", "ввв"), wanted = listOf(2..2))
        measured = 0

        val layout = window.layoutOrMeasure(0)

        assertEquals("а", layout.text)
        assertSame(layout, window.layoutOrMeasure(0))
        assertEquals(1, measured)
        assertTrue(cache.isExact(0))
        assertTrue(cache.hasLayout(0))
    }

    @Test
    fun `область за краями окна зажимается`() {
        val window = update(parsed(0, "а", "б"), wanted = listOf(-5..10))

        assertEquals(2, window.measuredCount)
    }

    @Test
    fun `пустое окно`() {
        val window = update(parsed(0))

        assertEquals(0, window.lineCount)
        assertEquals(0f, window.totalHeight)
        assertEquals(-1, window.indexAt(0f))
    }
}
