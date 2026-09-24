package com.bylins.client.ui.components.output

import androidx.compose.ui.text.AnnotatedString
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
import com.bylins.client.ui.scroll.LineLayoutCache
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

    private fun window(firstSeq: Long, vararg lines: String, widthPx: Int = 400): TextWindow =
        LineLayoutCache<TextLayoutResult>().update(
            firstSeq = firstSeq,
            lines = lines.map { AnnotatedString(it) },
            key = widthPx,
            measure = { measurer.measure(it, style, softWrap = true, constraints = Constraints(maxWidth = widthPx)) },
            heightOf = { it.size.height.toFloat() }
        )

    @Test
    fun `верх строки по номеру — её место в стопке`() {
        val w = window(10, "abc", "", "defgh")

        // Внутри своей разметки первая визуальная строка начинается не с нуля
        // (line-height раздаёт зазор сверху), и это одно и то же смещение у всех
        // строк. Якорю важна разница между строками — она и должна равняться
        // высотам в стопке
        val base = w.anchorToPx(10, 0)
        assertEquals(w.lines[1].top - w.lines[0].top, w.anchorToPx(11, 0) - base)
        assertEquals(w.lines[2].top - w.lines[0].top, w.anchorToPx(12, 0) - base)
        assertTrue(w.lines[2].top > w.lines[1].top && w.lines[1].top > 0f, "строки не сложились стопкой")
    }

    @Test
    fun `пиксель внутри строки переводится в её номер`() {
        val w = window(10, "abc", "", "defgh")

        assertEquals(10L to 0, w.pxToAnchor(0f))
        assertEquals(11L, w.pxToAnchor(w.lines[1].top + 1f).first)
        assertEquals(12L, w.pxToAnchor(w.lines[2].top + 1f).first)
    }

    @Test
    fun `указатель правее текста — конец строки, левее — начало`() {
        val w = window(10, "abc", "", "defgh")

        assertEquals(3, w.pointToSelPoint(10_000f, w.lines[0].top + 1f).col)
        assertEquals(0, w.pointToSelPoint(-10f, w.lines[0].top + 1f).col)
        // Пустая строка: столбец только нулевой
        assertEquals(0, w.pointToSelPoint(10_000f, w.lines[1].top + 1f).col)
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
        assertTrue(w.lines[0].height > 18f, "строка не перенеслась")
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
    fun `совпадение поиска по смещениям переводится в строки`() {
        val text = "abc\n\ndefgh"
        val w = window(10, "abc", "", "defgh")
        val starts = lineStarts(text)

        assertEquals(listOf(0, 4, 5), starts.toList())
        assertEquals(2, lineIndexOf(starts, text.indexOf("fg")))
        assertNotNull(w.pathForOffsets(starts, text.indexOf("fg"), text.indexOf("fg") + 2))
        // Совпадение через границу строк — путь есть, из кусков
        assertNotNull(w.pathForOffsets(starts, 1, 7))
        assertNull(w.pathForOffsets(starts, 3, 3))
    }

    @Test
    fun `пустое окно отвечает началом, а не падает`() {
        val w = window(7)

        assertEquals(7L to 0, w.pxToAnchor(100f))
        assertEquals(0f, w.anchorToPx(9, 3))
        assertNull(w.pathForRange(0, 0, 100, 0))
    }
}
