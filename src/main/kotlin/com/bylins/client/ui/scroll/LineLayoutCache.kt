package com.bylins.client.ui.scroll

import androidx.compose.ui.text.AnnotatedString

/** Одна размеченная строка окна: её место в стопке и результат разметки. */
class MeasuredLine<L>(
    val seq: Long,
    val annotated: AnnotatedString,
    val layout: L,
    val top: Float,
    val height: Float
) {
    val length: Int get() = annotated.length
}

/**
 * Размеченное окно вывода: строки по порядку, с абсолютными номерами.
 *
 * @param measuredCount сколько строк пришлось размечать заново на этом
 *   обновлении — это и есть цена обновления; в норме одна-две
 */
class MeasuredWindow<L>(
    val firstSeq: Long,
    val lines: List<MeasuredLine<L>>,
    val measuredCount: Int
) {
    private val stack = LineStack(FloatArray(lines.size) { lines[it].height })

    val lineCount: Int get() = lines.size
    val isEmpty: Boolean get() = lines.isEmpty()
    val totalHeight: Float get() = stack.totalHeight
    val lastSeq: Long get() = firstSeq + lines.size - 1

    /** Индекс строки под вертикальной позицией [y]; за краями — крайняя. */
    fun indexAt(y: Float): Int = stack.indexAt(y)

    /** Индекс строки с номером [seq], зажатый в окно; ниже окна — первая. */
    fun indexOfSeq(seq: Long): Int = (seq - firstSeq).toInt().coerceIn(0, (lines.size - 1).coerceAtLeast(0))

    fun lineAt(y: Float): MeasuredLine<L>? = if (lines.isEmpty()) null else lines[indexAt(y)]
}

/**
 * Кэш разметки по строкам.
 *
 * Раньше окно вывода размечалось одним куском на каждое обновление: тысяча
 * строк, тысячи цветных отрезков, 84 мс — при том, что 999 строк из тысячи
 * байт в байт те же, что секунду назад (#19). Здесь строка размечается один
 * раз и живёт, пока не изменится или не уйдёт из окна. Меняется в норме
 * только последняя — промпт, к которому дописывается текст.
 *
 * Строка адресуется абсолютным номером [MeasuredLine.seq]: он не съезжает
 * при вытеснении сверху. Содержимое сверяется целиком — и текст, и отрезки:
 * `addLocalOutput` вставляет строки ПЕРЕД промптом, и под прежним номером
 * оказывается другая строка.
 *
 * Тип разметки — параметр: в тестах вместо TextLayoutResult подставляется
 * заглушка с высотой, и логика переиспользования проверяется без экрана.
 */
class LineLayoutCache<L> {

    private var cached: Map<Long, MeasuredLine<L>> = emptyMap()
    private var cacheKey: Any? = null

    /**
     * @param key всё, от чего зависит разметка помимо текста — ширина, стиль.
     *   Сменился ключ — старая разметка негодна вся
     * @param measure размечает одну строку
     * @param heightOf высота размеченной строки
     */
    fun update(
        firstSeq: Long,
        lines: List<AnnotatedString>,
        key: Any,
        measure: (AnnotatedString) -> L,
        heightOf: (L) -> Float
    ): MeasuredWindow<L> {
        val reusable = if (key == cacheKey) cached else emptyMap()
        cacheKey = key

        var measured = 0
        var y = 0f
        val result = ArrayList<MeasuredLine<L>>(lines.size)
        val fresh = HashMap<Long, MeasuredLine<L>>(lines.size * 2)

        for ((index, annotated) in lines.withIndex()) {
            val seq = firstSeq + index
            val previous = reusable[seq]
            val layout: L
            val height: Float
            if (previous != null && previous.annotated == annotated) {
                layout = previous.layout
                height = previous.height
            } else {
                layout = measure(annotated)
                height = heightOf(layout)
                measured++
            }
            val line = MeasuredLine(seq, annotated, layout, y, height)
            result.add(line)
            fresh[seq] = line
            y += height
        }

        cached = fresh
        return MeasuredWindow(firstSeq, result, measured)
    }

    fun clear() {
        cached = emptyMap()
        cacheKey = null
    }
}
