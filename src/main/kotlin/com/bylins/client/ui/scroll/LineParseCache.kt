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
 * Разобранный кусок буфера — зеркало [LineChunk]: те же строки, разобранные
 * с состоянием [stateIn] на входе. Кусок, разобранный однажды, живёт, пока
 * в снимке тот же кусок и то же состояние на входе.
 */
class ParsedChunk(
    val firstSeq: Long,
    val source: Array<String>,
    val stateIn: AnsiState,
    val lines: Array<ParsedLine>,
    val stateOut: AnsiState
)

/**
 * Разобранное окно вывода: строки по порядку, с абсолютными номерами.
 *
 * @param parsedCount сколько строк разобрано заново на этом обновлении —
 *   цена обновления; в норме одна, промпт
 */
class ParsedWindow(val firstSeq: Long, val chunks: List<ParsedChunk>, val offset: Int, val parsedCount: Int) {
    val lineCount: Int = chunks.sumOf { it.lines.size } - offset
    val isEmpty: Boolean get() = lineCount == 0
    val lastSeq: Long get() = firstSeq + lineCount - 1

    /** Строки по порядку — вид поверх кусков, без копии. */
    val lines: List<ParsedLine> = object : AbstractList<ParsedLine>() {
        override val size: Int get() = lineCount
        override fun get(index: Int): ParsedLine {
            val position = offset + index
            return chunks[position / LineChunk.SIZE].lines[position % LineChunk.SIZE]
        }
    }

    /** Индекс строки с номером [seq], зажатый в окно; ниже окна — первая. */
    fun indexOfSeq(seq: Long): Int = (seq - firstSeq).toInt().coerceIn(0, (lineCount - 1).coerceAtLeast(0))

    companion object {
        val EMPTY = ParsedWindow(0L, emptyList(), 0, 0)
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
 * Сверка идёт по кускам снимка: закрытый кусок в следующем снимке — тот же
 * объект, и при том же состоянии на входе он берётся целиком, не глядя на
 * строки. Построчная сверка по ссылкам по всему буферу стоила 2–4 мс на ста
 * тысячах строк — не вычислениями, а промахами по памяти. Построчно
 * сверяется только открытый хвост, он в снимке всегда новый.
 *
 * Первый кусок — особый: текст перед ним вытеснен, и состояние на его входе
 * взять неоткуда. Он остаётся с тем, с которым его разобрали; разобранный
 * впервые — с чистого. Состояния у парсера канонические, сверка — по ссылке.
 */
class LineParseCache(private val parse: (String, AnsiState) -> ParsedAnsi) {

    private var byFirstSeq: HashMap<Long, ParsedChunk> = HashMap()
    // Последний кусок прошлого окна — открытый хвост: у крошечных буферов
    // (меньше куска) вытеснение сдвигает его номер, и по номеру он не найдётся
    private var lastChunk: ParsedChunk? = null

    fun update(snapshot: LineSnapshot): ParsedWindow {
        val previous = byFirstSeq
        val fresh = HashMap<Long, ParsedChunk>(snapshot.chunks.size * 2)
        val result = ArrayList<ParsedChunk>(snapshot.chunks.size)
        val chunkCount = snapshot.chunks.size
        var state = AnsiState.RESET
        var parsed = 0

        for ((position, chunk) in snapshot.chunks.withIndex()) {
            val old = previous[chunk.firstSeq]
                ?: lastChunk?.takeIf { position == chunkCount - 1 && it.firstSeq <= chunk.firstSeq }
            // Строки прежнего разбора того же куска — по номеру: сдвиг ненулевой
            // только у хвоста крошечного буфера
            val shift = if (old == null) 0 else (chunk.firstSeq - old.firstSeq).toInt()
            val stateIn = if (position == 0) {
                old?.lines?.getOrNull(shift)?.stateIn ?: AnsiState.RESET
            } else {
                state
            }
            val parsedChunk = if (old != null && old.source === chunk.lines && old.stateIn === stateIn) {
                old
            } else {
                val (freshChunk, count) = parseChunk(chunk, stateIn, old, shift)
                parsed += count
                freshChunk
            }
            result.add(parsedChunk)
            fresh[chunk.firstSeq] = parsedChunk
            state = parsedChunk.stateOut
        }

        byFirstSeq = fresh
        lastChunk = result.lastOrNull()
        return ParsedWindow(snapshot.firstSeq, result, snapshot.offset, parsed)
    }

    /**
     * Разбирает кусок построчно, беря неизменившиеся строки из прежнего
     * разбора [old]; строка с номером seq там лежит под индексом j + [shift].
     */
    private fun parseChunk(chunk: LineChunk, stateIn: AnsiState, old: ParsedChunk?, shift: Int): Pair<ParsedChunk, Int> {
        val source = chunk.lines
        val lines = arrayOfNulls<ParsedLine>(source.size)
        var state = stateIn
        var parsed = 0
        for (j in source.indices) {
            val raw = source[j]
            val oldIndex = j + shift
            val previous = if (old != null && oldIndex >= 0 && oldIndex < old.lines.size) old.lines[oldIndex] else null
            val line = if (previous != null && previous.stateIn === state && previous.raw === raw) {
                previous
            } else if (previous != null && previous.stateIn == state && previous.raw == raw) {
                previous
            } else {
                parsed++
                val fresh = parse(raw, state)
                ParsedLine(raw, state, fresh.annotated, fresh.stateOut)
            }
            lines[j] = line
            state = line.stateOut
        }
        @Suppress("UNCHECKED_CAST")
        return ParsedChunk(chunk.firstSeq, source, stateIn, lines as Array<ParsedLine>, state) to parsed
    }

    fun clear() {
        byFirstSeq = HashMap()
        lastChunk = null
    }
}
