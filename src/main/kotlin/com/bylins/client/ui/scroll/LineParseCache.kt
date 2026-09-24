package com.bylins.client.ui.scroll

import androidx.compose.ui.text.AnnotatedString
import com.bylins.client.ui.AnsiState
import com.bylins.client.ui.ParsedAnsi

/**
 * Одна разобранная строка: что разбирали, с каким состоянием на входе,
 * что получилось и с каким состоянием кончилось.
 */
class ParsedLine(
    val raw: String,
    val stateIn: AnsiState,
    val annotated: AnnotatedString,
    val stateOut: AnsiState
) {
    val plain: String get() = annotated.text
    val length: Int get() = annotated.length
}

/**
 * Разобранное окно вывода: строки по порядку, с абсолютными номерами.
 *
 * @param parsedCount сколько строк разобрано заново на этом обновлении —
 *   цена обновления; в норме одна, промпт
 */
class ParsedWindow(val firstSeq: Long, val lines: List<ParsedLine>, val parsedCount: Int) {
    val lineCount: Int get() = lines.size
    val isEmpty: Boolean get() = lines.isEmpty()
    val lastSeq: Long get() = firstSeq + lines.size - 1

    /** Индекс строки с номером [seq], зажатый в окно; ниже окна — первая. */
    fun indexOfSeq(seq: Long): Int = (seq - firstSeq).toInt().coerceIn(0, (lines.size - 1).coerceAtLeast(0))

    companion object {
        val EMPTY = ParsedWindow(0L, emptyList(), 0)
    }
}

/**
 * Разбор ANSI по строкам с переносом состояния.
 *
 * Раньше окно разбиралось целиком на каждое обновление — цвет переносится
 * между строками, и казалось, что иначе нельзя. При ста тысячах строк это
 * 157–190 мс на каждый пришедший кусок (#19). Здесь строка помнит, с каким
 * состоянием её разбирали и каким кончилась: если строка та же и состояние
 * на входе то же — результат тот же, и разбирать её заново незачем. Меняется
 * в норме только последняя строка, к ней дописывается текст.
 *
 * Строки сверяются с прошлым окном по номеру: вытеснение сверху сдвигает
 * номер первой строки, но не номера остальных. Сверка сперва по ссылке —
 * неизменённая строка в снимке тот же объект, что и в прошлом, а состояния
 * у парсера канонические, — и лишь потом по содержимому: сто тысяч
 * сравнений `==` с боксингом цвета стоили 5 мс на каждое обновление.
 */
class LineParseCache(private val parse: (String, AnsiState) -> ParsedAnsi) {

    private var window: ParsedWindow = ParsedWindow.EMPTY
    // То же, что window.lines, но массивом: проход по ста тысячам строк идёт
    // на каждое обновление, и обращение к списку через интерфейс на нём заметно
    private var lines: Array<ParsedLine?> = arrayOfNulls(0)

    fun update(snapshot: LineSnapshot): ParsedWindow {
        val previous = lines
        // Индекс в прошлом окне строки с тем же номером, что и первая новая
        val shift = (snapshot.firstSeq - window.firstSeq).toInt()
        val n = snapshot.lineCount
        val source = snapshot.lines
        val result = arrayOfNulls<ParsedLine>(n)
        var state = AnsiState.RESET
        var parsed = 0

        var i = 0
        while (i < n) {
            val raw = source[i]
            val previousIndex = i + shift
            val old = if (previousIndex >= 0 && previousIndex < previous.size) previous[previousIndex] else null
            val line = if (old != null && old.stateIn === state && old.raw === raw) {
                old
            } else if (old != null && old.stateIn == state && old.raw == raw) {
                old
            } else {
                parsed++
                val fresh = parse(raw, state)
                ParsedLine(raw, state, fresh.annotated, fresh.stateOut)
            }
            result[i] = line
            state = line.stateOut
            i++
        }

        lines = result
        @Suppress("UNCHECKED_CAST")
        return ParsedWindow(snapshot.firstSeq, (result as Array<ParsedLine>).asList(), parsed).also { window = it }
    }

    fun clear() {
        window = ParsedWindow.EMPTY
        lines = arrayOfNulls(0)
    }
}
