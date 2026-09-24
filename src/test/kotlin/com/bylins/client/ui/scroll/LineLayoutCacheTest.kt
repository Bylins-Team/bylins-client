package com.bylins.client.ui.scroll

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Кэш разметки по строкам.
 *
 * Смысл всей затеи — размечать только изменившееся: окно вывода размечалось
 * целиком на каждое обновление, и тысяча строк игры стоила 84 мс (#19).
 * Здесь проверяется именно это: что не изменилось — не размечается заново.
 */
class LineLayoutCacheTest {

    /** Заглушка разметки: у каждой строки своя высота, чтобы видеть, чья она. */
    private class Layout(val text: String, val height: Float)

    private var measured = 0
    private val measure: (AnnotatedString) -> Layout = { a -> measured++; Layout(a.text, 10f + a.length) }
    private val heightOf: (Layout) -> Float = { it.height }
    private val cache = LineLayoutCache<Layout>()

    private fun plain(vararg lines: String) = lines.map { AnnotatedString(it) }

    private fun update(firstSeq: Long, lines: List<AnnotatedString>, key: Any = "w100") =
        cache.update(firstSeq, lines, key, measure, heightOf)

    @Test
    fun `первое обновление размечает всё`() {
        val window = update(0, plain("а", "б", "в"))

        assertEquals(3, window.measuredCount)
        assertEquals(3, window.lineCount)
    }

    @Test
    fun `новая строка внизу — размечается только она`() {
        update(0, plain("а", "б", "в"))
        measured = 0

        val window = update(0, plain("а", "б", "в", "г"))

        assertEquals(1, window.measuredCount)
        assertEquals(1, measured)
    }

    @Test
    fun `неизменившаяся строка переиспользует ту же разметку`() {
        val first = update(0, plain("а", "б"))
        val second = update(0, plain("а", "б", "в"))

        assertSame(first.lines[0].layout, second.lines[0].layout)
        assertSame(first.lines[1].layout, second.lines[1].layout)
    }

    @Test
    fun `дописанный промпт размечается заново`() {
        // Последняя строка — промпт без перевода строки, к ней дописывается
        // текст: под тем же номером теперь другое содержимое
        update(0, plain("а", "Вых:"))
        measured = 0

        val window = update(0, plain("а", "Вых:СВЮЗ>"))

        assertEquals(1, window.measuredCount)
        assertEquals("Вых:СВЮЗ>", window.lines[1].annotated.text)
    }

    @Test
    fun `вставка перед промптом сдвигает номера, и сдвинутая строка не путается с прежней`() {
        // addLocalOutput вставляет строку ПЕРЕД промптом: номер 1 теперь у
        // вставленной, промпт уехал на 2. Сверка по содержимому, не по номеру
        update(0, plain("а", "Вых:>"))
        measured = 0

        val window = update(0, plain("а", "[#perf] отчёт", "Вых:>"))

        assertEquals(listOf("а", "[#perf] отчёт", "Вых:>"), window.lines.map { it.annotated.text })
        // Строка «а» переиспользована, две другие размечены
        assertEquals(2, window.measuredCount)
    }

    @Test
    fun `вытеснение сверху сдвигает окно, а разметка остаётся своей`() {
        val before = update(0, plain("а", "б", "в"))
        measured = 0

        // Первая строка вытеснена: окно теперь начинается с номера 1
        val after = update(1, plain("б", "в", "г"))

        assertEquals(1, after.measuredCount)
        assertSame(before.lines[1].layout, after.lines[0].layout)
        assertEquals(1L, after.firstSeq)
    }

    @Test
    fun `смена ширины или стиля перечёркивает весь кэш`() {
        update(0, plain("а", "б"), key = "w100")
        measured = 0

        val window = update(0, plain("а", "б"), key = "w200")

        assertEquals(2, window.measuredCount)
    }

    @Test
    fun `тот же текст с другим цветом — другая строка`() {
        // Сверять только текст было бы дешевле, но раскраска — часть разметки:
        // после «#perf slow» промпт может перекраситься, не меняя букв
        val red = buildAnnotatedString { withStyle(SpanStyle(color = Color.Red)) { append("Вых:>") } }
        val green = buildAnnotatedString { withStyle(SpanStyle(color = Color.Green)) { append("Вых:>") } }
        update(0, listOf(red))
        measured = 0

        val window = update(0, listOf(green))

        assertEquals(1, window.measuredCount)
    }

    @Test
    fun `позиции строк складываются из высот`() {
        val window = update(0, plain("а", "бб", "ввв")) // высоты 11, 12, 13

        assertEquals(listOf(0f, 11f, 23f), window.lines.map { it.top })
        assertEquals(36f, window.totalHeight)
        assertEquals(1, window.indexAt(11f))
        assertEquals(2, window.indexAt(100f))
    }

    @Test
    fun `номер строки за пределами окна зажимается в окно`() {
        val window = update(10, plain("а", "б"))

        assertEquals(0, window.indexOfSeq(3))   // вытеснена сверху
        assertEquals(1, window.indexOfSeq(11))
        assertEquals(1, window.indexOfSeq(99))  // ещё не пришла
    }

    @Test
    fun `пустое окно`() {
        val window = update(0, emptyList())

        assertEquals(0, window.lineCount)
        assertEquals(0f, window.totalHeight)
        assertEquals(-1, window.indexAt(0f))
    }
}
