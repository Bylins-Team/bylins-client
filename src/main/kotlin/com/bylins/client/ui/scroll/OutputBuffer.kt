package com.bylins.client.ui.scroll

/**
 * Геометрия буфера вывода в терминах абсолютной нумерации строк (seq).
 * Чистый тип без Compose — используется логикой автоскролла.
 *
 * @param firstSeq абсолютный порядковый номер первой (верхней) строки в буфере
 * @param lineCount число логических строк в текущем буфере
 */
data class BufferGeometry(val firstSeq: Long, val lineCount: Int) {
    val lastSeq: Long get() = firstSeq + lineCount - 1
    val isEmpty: Boolean get() = lineCount == 0
}

/**
 * Неизменяемый кусок буфера: подряд идущие строки с номера [firstSeq].
 *
 * Все куски снимка, кроме последнего, ровно по [SIZE] строк — так строка по
 * индексу находится делением. Кусок, однажды закрытый, больше не меняется
 * и во всех следующих снимках остаётся тем же объектом: кто сверяет снимки
 * (разбор, разметка), пропускает его целиком, а не построчно.
 */
class LineChunk(val firstSeq: Long, val lines: Array<String>) {
    val size: Int get() = lines.size

    companion object {
        const val SIZE = 256
    }
}

/**
 * Неизменяемый снимок буфера вывода: строки как есть (с ANSI), по порядку.
 *
 * seq не обязан совпадать с «номером строки сервера» — это монотонный счётчик,
 * согласованный внутри буфера: [firstSeq] = сколько строк уже вытеснено
 * сверху. Позволяет заякорить позицию скролла и выделение на конкретной
 * строке и переживать вытеснение.
 *
 * Снимок — список кусков ([chunks]) и сколько строк первого куска уже
 * вытеснено ([offset]): вытеснение не переписывает кусок, а сдвигает
 * начало. Неизменённая строка в следующем снимке — тот же объект, а
 * закрытый кусок — тот же кусок; на этом держится вся цена обновления.
 *
 * Последняя строка — незавершённая (промпт), к ней дописывается приходящий
 * текст. Завершающий перевод строки новой строки не создаёт: она появится
 * вместе с текстом, который в неё придёт.
 */
class LineSnapshot(val firstSeq: Long, val chunks: List<LineChunk>, val offset: Int) {

    constructor(firstSeq: Long, lines: List<String>) : this(firstSeq, chunk(firstSeq, lines), 0)

    val lineCount: Int = chunks.sumOf { it.size } - offset
    val isEmpty: Boolean get() = lineCount == 0
    val lastSeq: Long get() = firstSeq + lineCount - 1
    val geometry: BufferGeometry get() = BufferGeometry(firstSeq, lineCount)

    /** Строки по порядку — вид поверх кусков, без копии. */
    val lines: List<String> = object : AbstractList<String>() {
        override val size: Int get() = lineCount
        override fun get(index: Int): String {
            val position = offset + index
            return chunks[position / LineChunk.SIZE].lines[position % LineChunk.SIZE]
        }
    }

    /** Весь текст буфера одной строкой — для журнала и тестов, не для показа. */
    fun text(): String = lines.joinToString("\n")

    companion object {
        val EMPTY = LineSnapshot(0L, emptyList(), 0)

        /** Снимок из готового текста: как если бы его дописали в пустой буфер. */
        fun of(text: String, firstSeq: Long = 0L): LineSnapshot =
            if (text.isEmpty()) LineSnapshot(firstSeq, emptyList()) else LineSnapshot(firstSeq, text.split('\n'))

        private fun chunk(firstSeq: Long, lines: List<String>): List<LineChunk> {
            if (lines.isEmpty()) return emptyList()
            val chunks = ArrayList<LineChunk>((lines.size + LineChunk.SIZE - 1) / LineChunk.SIZE)
            var from = 0
            while (from < lines.size) {
                val to = minOf(from + LineChunk.SIZE, lines.size)
                chunks.add(LineChunk(firstSeq + from, Array(to - from) { lines[from + it] }))
                from = to
            }
            return chunks
        }
    }
}

/**
 * Буфер вывода по строкам.
 *
 * Раньше буфер был одной строкой: каждое добавление копировало её целиком,
 * а каждое обновление панели пересчитывало по ней строки — при десяти
 * мегабайтах это 5–9 мс на каждый приход текста и столько же сверху (#19).
 * Здесь добавление трогает только открытый хвост, снимок — копия ссылок на
 * куски и на хвост (сотни, не сотни тысяч), вытеснение — сдвиг начала.
 *
 * Куски по [LineChunk.SIZE] строк: заполнился хвост — закрывается куском и
 * больше не меняется. Вставка перед промптом и дописывание к нему трогают
 * только открытый хвост.
 *
 * Не потокобезопасен: владелец держит свой замок.
 *
 * @param maxLines сколько строк держать; лишние вытесняются сверху
 */
class LineBuffer(@Volatile var maxLines: Int) {

    private val closed = ArrayDeque<LineChunk>()
    // Сколько строк первого закрытого куска уже вытеснено
    private var offset = 0
    // Открытый хвост; его последняя строка — незавершённая
    private val open = ArrayList<String>()
    // Последняя строка закрыта переводом строки: следующая появится вместе с текстом.
    // Заводить её сразу нельзя -- пустая строка на кадр удлиняет буфер, вид прижат к низу,
    // и весь текст дёргается вверх-вниз на строку при каждом ответе сервера.
    private var pendingNewLine = false

    /** Абсолютный номер первой строки: растёт при вытеснении и очистке. */
    var firstSeq: Long = 0L
        private set

    val lineCount: Int get() = closed.size * LineChunk.SIZE - offset + open.size
    val isEmpty: Boolean get() = lineCount == 0

    /** Последняя, незавершённая строка; null, если буфер пуст или строка закрыта. */
    val lastLine: String? get() = if (pendingNewLine) null else open.lastOrNull()

    /**
     * Дописывает пришедший текст: кусок до первого перевода строки — к
     * последней строке, остальное — новыми строками.
     *
     * Перевод строки в самом конце строку не заводит, а только закрывает текущую:
     * следующая появится, когда в неё придёт текст. Иначе после каждого ответа сервера
     * (он начинается с перевода строки, закрывающего промпт) буфер на кадр становился
     * на строку длиннее, а вид прижат к низу — весь текст дёргался вверх и обратно.
     */
    fun append(text: String) {
        if (text.isEmpty()) return
        var start = 0
        var first = true
        while (true) {
            val newline = text.indexOf('\n', start)
            val end = if (newline == -1) text.length else newline
            val piece = text.substring(start, end)
            if (first && !pendingNewLine && open.isNotEmpty()) {
                if (piece.isNotEmpty()) open[open.size - 1] = open.last() + piece
            } else {
                push(piece)
            }
            first = false
            if (newline == -1) break
            start = newline + 1
            if (start == text.length) {
                // Перевод строки последним символом: строку не заводим, только закрываем
                pendingNewLine = true
                break
            }
        }
        trim()
    }

    /** Добавляет завершённую строку целиком (вкладки собирают вывод строками). */
    fun addLine(line: String) {
        push(line)
        trim()
    }

    /**
     * Вставляет строки [text] перед незавершённой последней строкой —
     * промптом, который иначе оказался бы выше сообщения. Если промпта
     * нет, просто добавляет.
     */
    fun insertBeforeIncomplete(text: String) {
        val incomplete = lastLine
        if (incomplete.isNullOrEmpty()) {
            append(text + "\n")
            return
        }
        open.removeAt(open.size - 1)
        for (piece in text.split('\n')) push(piece)
        push(incomplete)
        trim()
    }

    /** Очищает буфер; очищенные строки считаются вытесненными — номера монотонны. */
    fun clear() {
        firstSeq += lineCount
        closed.clear()
        offset = 0
        open.clear()
        pendingNewLine = false
    }

    fun snapshot(): LineSnapshot {
        val chunks = ArrayList<LineChunk>(closed.size + 1)
        chunks.addAll(closed)
        if (open.isNotEmpty()) chunks.add(LineChunk(openFirstSeq(), open.toTypedArray()))
        return LineSnapshot(firstSeq, chunks, offset)
    }

    private fun openFirstSeq(): Long = firstSeq + closed.size * LineChunk.SIZE - offset

    private fun push(line: String) {
        pendingNewLine = false
        if (open.size == LineChunk.SIZE) {
            closed.addLast(LineChunk(openFirstSeq(), open.toTypedArray()))
            open.clear()
        }
        open.add(line)
    }

    private fun trim() {
        val limit = maxLines.coerceAtLeast(1)
        while (lineCount > limit) {
            if (closed.isEmpty()) {
                open.removeAt(0)
            } else {
                offset++
                if (offset == LineChunk.SIZE) {
                    closed.removeFirst()
                    offset = 0
                }
            }
            firstSeq++
        }
    }
}
