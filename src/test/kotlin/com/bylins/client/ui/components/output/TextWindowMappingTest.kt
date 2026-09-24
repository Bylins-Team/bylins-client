package com.bylins.client.ui.components.output

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.bylins.client.ui.AnsiParser
import com.bylins.client.ui.scroll.LineLayoutCache
import com.bylins.client.ui.scroll.LineParseCache
import com.bylins.client.ui.scroll.LineSnapshot
import com.bylins.client.ui.scroll.SearchMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Перевод «пиксель ↔ символ» по строкам — на настоящей разметке.
 *
 * Стопка и кэш проверены с заглушками; здесь то, что заглушкой не проверить:
 * что найденная строка и её собственная разметка дают те же ответы, что
 * раньше давала общая, — верх строки по номеру, символ по пикселю, пути
 * подсветки, включая переносы длинных строк.
 */
@OptIn(ExperimentalTextApi::class)
class TextWindowMappingTest {

    private val density = Density(1f)
    private val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
    private val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 18.sp)
    private val parser = AnsiParser()

    private fun window(firstSeq: Long, vararg lines: String, widthPx: Int = 400, wanted: List<IntRange>? = null): TextWindow {
        val parsed = LineParseCache { text, state -> parser.parse(text, state) }.update(LineSnapshot(firstSeq, lines.toList()))
        return LineLayoutCache<TextLayoutResult>().update(
            parsed = parsed,
            key = widthPx,
            wanted = wanted ?: listOf(0 until parsed.lineCount),
            estimate = { 18f },
            measure = { measurer.measure(it, style, softWrap = true, constraints = Constraints(maxWidth = widthPx)) },
            heightOf = { it.size.height.toFloat() }
        )
    }

    @Test
    fun `верх строки по номеру — её место в стопке`() {
        val w = window(10, "abc", "", "defgh")

        // Внутри своей разметки первая визуальная строка начинается не с нуля
        // (line-height раздаёт зазор сверху), и это одно и то же смещение у всех
        // строк. Якорю важна разница между строками — она и должна равняться
        // высотам в стопке
        val base = w.anchorToPx(10, 0)
        assertEquals(w.topOf(1) - w.topOf(0), w.anchorToPx(11, 0) - base)
        assertEquals(w.topOf(2) - w.topOf(0), w.anchorToPx(12, 0) - base)
        assertTrue(w.topOf(2) > w.topOf(1) && w.topOf(1) > 0f, "строки не сложились стопкой")
    }

    @Test
    fun `пиксель внутри строки переводится в её номер`() {
        val w = window(10, "abc", "", "defgh")

        assertEquals(10L to 0, w.pxToAnchor(0f))
        assertEquals(11L, w.pxToAnchor(w.topOf(1) + 1f).first)
        assertEquals(12L, w.pxToAnchor(w.topOf(2) + 1f).first)
    }

    @Test
    fun `указатель правее текста — конец строки, левее — начало`() {
        val w = window(10, "abc", "", "defgh")

        assertEquals(3, w.pointToSelPoint(10_000f, w.topOf(0) + 1f).col)
        assertEquals(0, w.pointToSelPoint(-10f, w.topOf(0) + 1f).col)
        // Пустая строка: столбец только нулевой
        assertEquals(0, w.pointToSelPoint(10_000f, w.topOf(1) + 1f).col)
    }

    @Test
    fun `в перенесённой строке якорь на второй визуальной строке ниже первой`() {
        // Ширина в 60px вмещает лишь несколько символов — строка переносится.
        // Камень №7: якорь должен указывать на визуальную строку, а не на
        // начало логической, иначе позиция дрожит при новом тексте
        val w = window(0, "abcdefghijklmnopqrstuvwxyz", widthPx = 60)

        val first = w.anchorToPx(0, 0)
        val later = w.anchorToPx(0, 20)
        assertTrue(later > first, "символ в перенесённой части оказался на той же высоте: $first == $later")
        assertTrue(w.heightOf(0) > 18f, "строка не перенеслась")
    }

    @Test
    fun `путь выделения через несколько строк не пуст, за пределами окна — пуст`() {
        val w = window(10, "abc", "", "defgh")

        assertNotNull(w.pathForRange(10, 1, 12, 2), "выделение через три строки")
        assertNotNull(w.pathForRange(5, 0, 10, 2), "начало в вытесненной строке подтягивается к окну")
        assertNull(w.pathForRange(20, 0, 25, 0), "выделение целиком ниже окна")
        assertNull(w.pathForRange(10, 2, 10, 2), "пустой диапазон")
    }

    @Test
    fun `путь выделения строится только для видимых строк`() {
        val w = window(10, "abc", "", "defgh")

        assertNotNull(w.pathForRange(10, 0, 12, 5, fromIndex = 2, toIndex = 2), "видна последняя")
        assertNull(w.pathForRange(10, 0, 10, 3, fromIndex = 2, toIndex = 2), "выделена первая, видна последняя")
    }

    @Test
    fun `совпадение поиска — путь в своей строке`() {
        val w = window(10, "abc", "", "defgh")

        assertNotNull(w.pathForMatch(SearchMatch(12, 2, 4)))
        assertNull(w.pathForMatch(SearchMatch(12, 3, 3)), "пустое")
        assertNull(w.pathForMatch(SearchMatch(20, 0, 2)), "вне окна")
    }

    @Test
    fun `неразмеченная строка размечается на месте, когда её спрашивают`() {
        val w = window(10, "abc", "", "defgh", wanted = listOf(2..2))

        assertNull(w.layoutAt(0))
        assertEquals(3, w.pointToSelPoint(10_000f, w.topOf(0) + 1f).col)
        assertNotNull(w.layoutAt(0))
        assertNotNull(w.pathForRange(10, 0, 10, 3))
    }

    @Test
    fun `пустое окно отвечает началом, а не падает`() {
        val w = window(7)

        assertEquals(7L to 0, w.pxToAnchor(100f))
        assertEquals(0f, w.anchorToPx(9, 3))
        assertNull(w.pathForRange(0, 0, 100, 0))
    }

    @Test
    fun `оценка высоты по словам совпадает с разметкой моноширинного текста`() {
        // Ширина образца — по той же разметке, что и у панели
        val charWidth = measurer.measure("0", style, softWrap = false).size.width
        val widthPx = charWidth * 20
        val lines = listOf(
            "короткая",
            "ровно двадцать симв.",
            "слова переносятся по пробелу когда не влезают",
            "оченьдлинноесловобезпробеловкотороервётсяпоширине",
            "",
            "x".repeat(20) + " y"
        )
        val w = window(0, *lines.toTypedArray(), widthPx = widthPx)

        for ((i, line) in lines.withIndex()) {
            val expected = w.layoutAt(i)!!.lineCount
            assertEquals(expected, estimateVisualLines(line, 20), "строка «$line»")
        }
    }
}
