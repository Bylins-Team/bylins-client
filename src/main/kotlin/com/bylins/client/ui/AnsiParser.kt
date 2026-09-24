package com.bylins.client.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Состояние раскраски между кусками текста.
 *
 * Цвет в ANSI не знает строк: сервер включил зелёный в одной строке, а
 * выключил через три. Поэтому строка разбирается с состоянием на конце
 * предыдущей и отдаёт своё — по нему разбирается следующая. Меняется в
 * норме только последняя строка, и разбирать заново нужно только её.
 */
data class AnsiState(
    val foreground: Color? = null,
    val background: Color? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
) {
    val isPlain: Boolean
        get() = foreground == null && background == null && !bold && !italic && !underline

    companion object {
        val RESET = AnsiState()
    }
}

/** Результат разбора одной строки: раскрашенный текст и состояние на её конце. */
class ParsedAnsi(val annotated: AnnotatedString, val stateOut: AnsiState)

class AnsiParser {
    private val ESC = '\u001B'
    private val TAB_WIDTH = 8 // Стандартная ширина табуляции

    // Кеш для переиспользования SpanStyle объектов (КРИТИЧНО для производительности).
    // Ключ — само состояние: собирать строку-ключ на каждый отрезок было бы
    // мусором на каждую строку вывода
    private val spanStyleCache = HashMap<AnsiState, SpanStyle>()

    // Состояния канонические: одно и то же состояние — один объект. Кэш
    // разбора сверяет состояние на входе строки со ста тысячами прошлых, и
    // сверка по ссылке — единственная, которая ничего не стоит: `==` у
    // data class с `Color?` боксит цвет на каждое сравнение
    private val stateCache = HashMap<AnsiState, AnsiState>().apply { put(AnsiState.RESET, AnsiState.RESET) }

    private fun canonical(state: AnsiState): AnsiState = stateCache.getOrPut(state) { state }

    // ANSI 16 базовых цветов (стандартная VGA/xterm палитра)
    private val ansi16Colors = mapOf(
        30 to Color(0xFF555555), // Black (темно-серый, чтобы видеть на чёрном фоне) (85,85,85)
        31 to Color(0xFFCD0000), // Red (205,0,0)
        32 to Color(0xFF00CD00), // Green (0,205,0)
        33 to Color(0xFFCDCD00), // Yellow (205,205,0)
        34 to Color(0xFF0000EE), // Blue (0,0,238)
        35 to Color(0xFFCD00CD), // Magenta (205,0,205)
        36 to Color(0xFF00CDCD), // Cyan (0,205,205)
        37 to Color(0xFFE5E5E5), // White (229,229,229)

        // Bright colors (максимальная яркость)
        90 to Color(0xFF7F7F7F), // Bright Black (Gray) (127,127,127)
        91 to Color(0xFFFF0000), // Bright Red (255,0,0)
        92 to Color(0xFF00FF00), // Bright Green (0,255,0)
        93 to Color(0xFFFFFF00), // Bright Yellow (255,255,0)
        94 to Color(0xFF5C5CFF), // Bright Blue (92,92,255)
        95 to Color(0xFFFF00FF), // Bright Magenta (255,0,255)
        96 to Color(0xFF00FFFF), // Bright Cyan (0,255,255)
        97 to Color(0xFFFFFFFF), // Bright White (255,255,255)
    )

    // ANSI 256 color palette (упрощённая версия)
    private fun get256Color(code: Int): Color {
        return when (code) {
            in 0..15 -> ansi16Colors[code + 30] ?: Color.White
            in 16..231 -> {
                // 216 color cube (6x6x6)
                val idx = code - 16
                val r = ((idx / 36) % 6) * 51
                val g = ((idx / 6) % 6) * 51
                val b = (idx % 6) * 51
                Color(r, g, b)
            }
            in 232..255 -> {
                // Grayscale
                val gray = 8 + (code - 232) * 10
                Color(gray, gray, gray)
            }
            else -> Color.White
        }
    }

    /**
     * Заменяет табуляции на пробелы с учётом позиции в строке (tab stops)
     */
    private fun expandTabs(text: String): String {
        if (!text.contains('\t')) return text

        val result = StringBuilder()
        var column = 0

        for (char in text) {
            when (char) {
                '\t' -> {
                    // Добавляем пробелы до следующего tab stop
                    val spacesToAdd = TAB_WIDTH - (column % TAB_WIDTH)
                    repeat(spacesToAdd) { result.append(' ') }
                    column += spacesToAdd
                }
                '\n' -> {
                    result.append(char)
                    column = 0 // Сброс позиции в начале новой строки
                }
                else -> {
                    result.append(char)
                    column++
                }
            }
        }

        return result.toString()
    }

    /** Разбирает текст целиком, начиная с чистого состояния. */
    fun parse(text: String): AnnotatedString = parse(text, AnsiState.RESET).annotated

    /**
     * Разбирает текст, начиная с состояния [stateIn] — раскраски, в которой
     * закончился предыдущий кусок, — и отдаёт состояние на своём конце.
     */
    fun parse(text: String, stateIn: AnsiState): ParsedAnsi {
        var stateOut = stateIn
        val annotated = buildAnnotatedString {
            stateOut = parseInto(this, expandTabs(text), stateIn)
        }
        return ParsedAnsi(annotated, stateOut)
    }

    private fun AnnotatedString.Builder.appendStyled(text: String, start: Int, end: Int, state: AnsiState) {
        if (end <= start) return
        // Без раскраски — без отрезка: разметка стоит по отрезкам, не по буквам
        if (state.isPlain) {
            append(text, start, end)
            return
        }
        pushStyle(createSpanStyle(state))
        append(text, start, end)
        pop()
    }

    private fun parseInto(builder: AnnotatedString.Builder, text: String, stateIn: AnsiState): AnsiState {
        var currentPos = 0
        var fg = stateIn.foreground
        var bg = stateIn.background
        var bold = stateIn.bold
        var italic = stateIn.italic
        var underline = stateIn.underline
        var state = stateIn

        while (currentPos < text.length) {
            val escPos = text.indexOf(ESC, currentPos)

            if (escPos == -1) {
                // Нет больше escape последовательностей - применяем текущий стиль к оставшемуся тексту
                builder.appendStyled(text, currentPos, text.length, state)
                break
            }

            // Добавляем текст до escape последовательности
            builder.appendStyled(text, currentPos, escPos, state)

            // Парсим escape последовательность
            if (escPos + 1 < text.length && text[escPos + 1] == '[') {
                val mPos = text.indexOf('m', escPos + 2)
                if (mPos != -1) {
                    val codes = text.substring(escPos + 2, mPos)
                        .split(';')
                        .mapNotNull { it.toIntOrNull() }

                    var i = 0
                    while (i < codes.size) {
                        val code = codes[i]
                        when (code) {
                            0 -> {
                                // Reset
                                fg = null
                                bg = null
                                bold = false
                                italic = false
                                underline = false
                            }
                            1 -> bold = true
                            3 -> italic = true
                            4 -> underline = true
                            22 -> bold = false
                            23 -> italic = false
                            24 -> underline = false
                            in 30..37, in 90..97 -> {
                                // Foreground color
                                fg = ansi16Colors[code]
                            }
                            38 -> {
                                // Extended foreground color
                                if (i + 1 < codes.size) {
                                    when (codes[i + 1]) {
                                        5 -> {
                                            // 256 colors
                                            if (i + 2 < codes.size) {
                                                fg = get256Color(codes[i + 2])
                                                i += 2
                                            }
                                        }
                                        2 -> {
                                            // RGB
                                            if (i + 4 < codes.size) {
                                                fg = Color(codes[i + 2], codes[i + 3], codes[i + 4])
                                                i += 4
                                            }
                                        }
                                    }
                                }
                            }
                            in 40..47, in 100..107 -> {
                                // Background color
                                bg = ansi16Colors[code - 10]
                            }
                            48 -> {
                                // Extended background color
                                if (i + 1 < codes.size) {
                                    when (codes[i + 1]) {
                                        5 -> {
                                            // 256 colors
                                            if (i + 2 < codes.size) {
                                                bg = get256Color(codes[i + 2])
                                                i += 2
                                            }
                                        }
                                        2 -> {
                                            // RGB
                                            if (i + 4 < codes.size) {
                                                bg = Color(codes[i + 2], codes[i + 3], codes[i + 4])
                                                i += 4
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        i++
                    }
                    state = canonical(AnsiState(fg, bg, bold, italic, underline))

                    currentPos = mPos + 1
                } else {
                    // Неполная escape последовательность
                    currentPos = escPos + 1
                }
            } else {
                // Неизвестная escape последовательность
                currentPos = escPos + 1
            }
        }
        return state
    }

    private fun createSpanStyle(state: AnsiState): SpanStyle =
        spanStyleCache.getOrPut(state) {
            SpanStyle(
                color = state.foreground ?: Color.Unspecified,
                background = state.background ?: Color.Unspecified,
                fontWeight = if (state.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (state.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (state.underline) TextDecoration.Underline else null
            )
        }

    /**
     * Удаляет все ANSI escape последовательности из текста
     */
    fun stripAnsi(text: String): String {
        val result = StringBuilder()
        var currentPos = 0

        while (currentPos < text.length) {
            val escPos = text.indexOf(ESC, currentPos)

            if (escPos == -1) {
                // Нет больше escape последовательностей
                result.append(text.substring(currentPos))
                break
            }

            // Добавляем текст до escape последовательности
            if (escPos > currentPos) {
                result.append(text.substring(currentPos, escPos))
            }

            // Пропускаем escape последовательность
            if (escPos + 1 < text.length && text[escPos + 1] == '[') {
                val mPos = text.indexOf('m', escPos + 2)
                if (mPos != -1) {
                    currentPos = mPos + 1
                } else {
                    currentPos = escPos + 1
                }
            } else {
                currentPos = escPos + 1
            }
        }

        return result.toString()
    }
}
