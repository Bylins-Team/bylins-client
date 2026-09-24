package com.bylins.client.ui.components.output

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.bylins.client.ui.AnsiParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Разбиение размеченного окна на строки.
 *
 * Первая версия резала через `AnnotatedString.subSequence`, который на каждый
 * срез перебирает все отрезки окна: на ста тысячах строк UI-поток ушёл в это
 * на девять минут. Линейный проход должен давать ровно тот же результат, что
 * и срез, — это и проверяется, срезом как эталоном.
 */
class SplitLinesTest {

    /** Эталон: то, что даёт subSequence — медленно, но бесспорно. */
    private fun reference(annotated: AnnotatedString): List<AnnotatedString> {
        val text = annotated.text
        if (text.isEmpty()) return emptyList()
        val lines = ArrayList<AnnotatedString>()
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            if (newline == -1) {
                lines.add(annotated.subSequence(start, text.length))
                return lines
            }
            lines.add(annotated.subSequence(start, newline))
            start = newline + 1
        }
    }

    private fun assertSameAsReference(annotated: AnnotatedString) {
        val expected = reference(annotated)
        val actual = splitLines(annotated)
        assertEquals(expected.map { it.text }, actual.map { it.text }, "тексты строк")
        assertEquals(
            expected.map { it.spanStyles.map { s -> Triple(s.item, s.start, s.end) } },
            actual.map { it.spanStyles.map { s -> Triple(s.item, s.start, s.end) } },
            "отрезки строк"
        )
    }

    @Test
    fun `простой текст без раскраски`() {
        assertSameAsReference(AnnotatedString("раз\nдва\n\nтри"))
    }

    @Test
    fun `пустой текст — ни одной строки, завершающий перевод — пустая последняя`() {
        assertEquals(0, splitLines(AnnotatedString("")).size)
        assertEquals(listOf("а", ""), splitLines(AnnotatedString("а\n")).map { it.text })
    }

    @Test
    fun `отрезок через границу строк режется на две части`() {
        // Цвет переносится между строками: отрезок начинается на одной, кончается на другой
        val annotated = buildAnnotatedString {
            append("ab")
            withStyle(SpanStyle(color = Color.Red)) { append("cd\nef") }
            append("gh")
        }

        assertSameAsReference(annotated)
        val lines = splitLines(annotated)
        assertEquals(listOf(2 to 4), lines[0].spanStyles.map { it.start to it.end })
        assertEquals(listOf(0 to 2), lines[1].spanStyles.map { it.start to it.end })
    }

    @Test
    fun `отрезок ровно на перевод строки не даёт пустых отрезков`() {
        val annotated = buildAnnotatedString {
            append("ab")
            withStyle(SpanStyle(color = Color.Blue)) { append("\n") }
            append("cd")
        }

        assertSameAsReference(annotated)
    }

    @Test
    fun `настоящий вывод игры совпадает с эталоном`() {
        val esc = '\u001B'
        val text = buildString {
            repeat(300) { i ->
                append("$esc[3${i % 7}mСтрока $i$esc[0m обычный $esc[1;35mяркий$esc[0m хвост\n")
            }
            append("$esc[32mВых:СВЮЗ>$esc[0m")
        }

        assertSameAsReference(AnsiParser().parse(text))
    }
}
