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
 * Неизменяемый снимок буфера вывода: строки как есть (с ANSI), по порядку.
 *
 * seq не обязан совпадать с «номером строки сервера» — это монотонный счётчик,
 * согласованный внутри буфера: [firstSeq] = сколько строк уже вытеснено
 * сверху. Позволяет заякорить позицию скролла и выделение на конкретной
 * строке и переживать вытеснение.
 *
 * Строки — те же объекты, что лежат в буфере: пока строка не менялась, в
 * следующем снимке под тем же номером будет тот же объект. На этом держится
 * вся цена обновления: разбор, разметка и поиск сверяют строки по ссылке и
 * переделывают только изменившиеся.
 *
 * Последняя строка — незавершённая (промпт), к ней дописывается приходящий
 * текст. Текст с завершающим переводом строки заканчивается пустой строкой.
 */
class LineSnapshot(val firstSeq: Long, val lines: List<String>) {
    val lineCount: Int get() = lines.size
    val isEmpty: Boolean get() = lines.isEmpty()
    val lastSeq: Long get() = firstSeq + lines.size - 1
    val geometry: BufferGeometry get() = BufferGeometry(firstSeq, lines.size)

    /** Весь текст буфера одной строкой — для журнала и тестов, не для показа. */
    fun text(): String = lines.joinToString("\n")

    companion object {
        val EMPTY = LineSnapshot(0L, emptyList())

        /** Снимок из готового текста: как если бы его дописали в пустой буфер. */
        fun of(text: String, firstSeq: Long = 0L): LineSnapshot =
            if (text.isEmpty()) LineSnapshot(firstSeq, emptyList()) else LineSnapshot(firstSeq, text.split('\n'))
    }
}

/**
 * Буфер вывода по строкам.
 *
 * Раньше буфер был одной строкой: каждое добавление копировало её целиком,
 * а каждое обновление панели пересчитывало по ней строки — при десяти
 * мегабайтах это 5–9 мс на каждый приход текста и столько же сверху (#19).
 * Здесь добавление трогает только последнюю строку и хвост списка, снимок
 * — копия ссылок, вытеснение — сдвиг номера первой строки.
 *
 * Не потокобезопасен: владелец держит свой замок.
 *
 * @param maxLines сколько строк держать; лишние вытесняются сверху
 */
class LineBuffer(@Volatile var maxLines: Int) {

    private val lines = ArrayDeque<String>()

    /** Абсолютный номер первой строки: растёт при вытеснении и очистке. */
    var firstSeq: Long = 0L
        private set

    val lineCount: Int get() = lines.size
    val isEmpty: Boolean get() = lines.isEmpty()

    /** Последняя, незавершённая строка; null, если буфер пуст. */
    val lastLine: String? get() = lines.lastOrNull()

    /**
     * Дописывает пришедший текст: кусок до первого перевода строки — к
     * последней строке, остальное — новыми строками. Текст с переводом
     * строки на конце оставляет пустую незавершённую строку.
     */
    fun append(text: String) {
        if (text.isEmpty()) return
        var start = 0
        var first = true
        while (true) {
            val newline = text.indexOf('\n', start)
            val end = if (newline == -1) text.length else newline
            val piece = text.substring(start, end)
            if (first && lines.isNotEmpty()) {
                if (piece.isNotEmpty()) lines[lines.size - 1] = lines.last() + piece
            } else {
                lines.addLast(piece)
            }
            first = false
            if (newline == -1) break
            start = newline + 1
        }
        trim()
    }

    /** Добавляет завершённую строку целиком (вкладки собирают вывод строками). */
    fun addLine(line: String) {
        lines.addLast(line)
        trim()
    }

    /**
     * Вставляет строки [text] перед незавершённой последней строкой —
     * промптом, который иначе оказался бы выше сообщения. Если промпта
     * нет, просто добавляет.
     */
    fun insertBeforeIncomplete(text: String) {
        val incomplete = lines.lastOrNull()
        if (incomplete.isNullOrEmpty()) {
            append(text + "\n")
            return
        }
        lines.removeLast()
        for (piece in text.split('\n')) lines.addLast(piece)
        lines.addLast(incomplete)
        trim()
    }

    /** Очищает буфер; очищенные строки считаются вытесненными — номера монотонны. */
    fun clear() {
        firstSeq += lines.size
        lines.clear()
    }

    fun snapshot(): LineSnapshot = LineSnapshot(firstSeq, ArrayList(lines))

    private fun trim() {
        val limit = maxLines.coerceAtLeast(1)
        while (lines.size > limit) {
            lines.removeFirst()
            firstSeq++
        }
    }
}
