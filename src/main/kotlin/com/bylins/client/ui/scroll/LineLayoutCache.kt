package com.bylins.client.ui.scroll

import androidx.compose.ui.text.AnnotatedString

/**
 * Размеченное окно вывода: строки по порядку, каждая на своей высоте.
 *
 * Разметка есть не у всех строк — только у тех, что около вьюпорта (см.
 * [LineLayoutCache]). У остальных известна лишь высота: точная, если строку
 * когда-то размечали, иначе оценка. Кому нужна разметка строки, которой
 * нет, — [layoutOrMeasure] размечает её на месте.
 *
 * @param measuredCount сколько строк размечено на этом обновлении — цена
 *   обновления; в норме одна-две
 */
class MeasuredWindow<L>(
    val parsed: ParsedWindow,
    private val stack: LineStack,
    private val slices: List<LayoutSlice<L>>,
    val measuredCount: Int,
    private val measureNow: (Int) -> L
) {
    /** Разметки подряд идущих строк, начиная с [from]. */
    class LayoutSlice<L>(val from: Int, val layouts: Array<L?>) {
        operator fun contains(index: Int): Boolean = index >= from && index < from + layouts.size
        operator fun get(index: Int): L? = layouts[index - from]
    }

    // Размеченное по ходу отрисовки — вне заранее размеченных областей
    private var extra: HashMap<Int, L>? = null

    val firstSeq: Long get() = parsed.firstSeq
    val lineCount: Int get() = parsed.lineCount
    val isEmpty: Boolean get() = parsed.isEmpty
    val totalHeight: Float get() = stack.totalHeight
    val lastSeq: Long get() = parsed.lastSeq

    /** Индекс строки под вертикальной позицией [y]; за краями — крайняя. */
    fun indexAt(y: Float): Int = stack.indexAt(y)

    /** Индекс строки с номером [seq], зажатый в окно; ниже окна — первая. */
    fun indexOfSeq(seq: Long): Int = parsed.indexOfSeq(seq)

    fun lineAt(index: Int): ParsedLine = parsed.lines[index]
    fun topOf(index: Int): Float = stack.tops[index]
    fun heightOf(index: Int): Float = stack.heightOf(index)
    fun lengthOf(index: Int): Int = parsed.lines[index].length

    /** Разметка строки, если она есть. */
    fun layoutAt(index: Int): L? {
        for (slice in slices) if (index in slice) return slice[index]
        return extra?.get(index)
    }

    /** Разметка строки; нет — размечается сейчас и запоминается. */
    fun layoutOrMeasure(index: Int): L {
        layoutAt(index)?.let { return it }
        val layout = measureNow(index)
        (extra ?: HashMap<Int, L>().also { extra = it })[index] = layout
        return layout
    }
}

/**
 * Кэш разметки по строкам — за цену изменения, а не окна.
 *
 * Первая версия размечала строку один раз и держала разметку, пока строка в
 * окне: при ста тысячах строк это сто тысяч живых TextLayoutResult и 9 ГБ,
 * а обслуживание кэша — таблица на всё окно, пересобираемая на каждое
 * обновление, — стоило 26–32 мс при трёх размеченных строках (#19).
 *
 * Здесь всё лежит в массивах, выровненных по номеру строки: сдвиг окна —
 * сдвиг массивов. Разметка есть только у строк в затребованных областях —
 * около якоря прокрутки и у хвоста; вышла строка из области — разметка
 * отпускается, а её точная высота остаётся. Строки, которых не размечали
 * никогда, стоят в стопке по оценке высоты: она уточняется, когда строка
 * доезжает до вьюпорта, а якорь по (номер, столбец) держит место чтения на
 * месте при любой поправке высот выше.
 *
 * Тип разметки — параметр: в тестах вместо TextLayoutResult подставляется
 * заглушка с высотой, и логика проверяется без экрана.
 */
class LineLayoutCache<L> {

    private var baseSeq = 0L
    private var size = 0
    private var refs: Array<AnnotatedString?> = arrayOfNulls(0)
    private var heights = FloatArray(0)
    private var exact = BooleanArray(0)
    private var layouts: Array<Any?> = arrayOfNulls(0)
    private var key: Any? = null
    private var previousRanges: List<IntRange> = emptyList()

    /** Сколько строк размечено за всё время — для тестов и отчётов. */
    var measuredTotal: Long = 0L
        private set

    /**
     * @param key всё, от чего зависит разметка помимо текста — ширина, стиль.
     *   Сменился ключ — старая разметка и высоты негодны все
     * @param wanted области (индексы в окне), где разметка должна быть;
     *   вне их разметка отпускается
     * @param estimate оценка высоты строки без разметки
     * @param measure размечает одну строку
     * @param heightOf высота размеченной строки
     */
    fun update(
        parsed: ParsedWindow,
        key: Any,
        wanted: List<IntRange>,
        estimate: (ParsedLine) -> Float,
        measure: (AnnotatedString) -> L,
        heightOf: (L) -> Float
    ): MeasuredWindow<L> {
        val n = parsed.lineCount
        if (key != this.key) {
            this.key = key
            reset(parsed.firstSeq, n)
        } else {
            realign(parsed.firstSeq, n)
        }

        // Строка сменилась под своим номером — её высота и разметка негодны
        for (i in 0 until n) {
            val annotated = parsed.lines[i].annotated
            if (refs[i] !== annotated) {
                refs[i] = annotated
                heights[i] = estimate(parsed.lines[i])
                exact[i] = false
                layouts[i] = null
            }
        }

        val ranges = wanted.mapNotNull { r ->
            val from = r.first.coerceAtLeast(0)
            val to = r.last.coerceAtMost(n - 1)
            if (from <= to) from..to else null
        }

        // Разметка отпускается там, где была нужна раньше и не нужна теперь
        for (old in previousRanges) {
            for (i in old) {
                if (i in 0 until n && ranges.none { i in it }) layouts[i] = null
            }
        }
        previousRanges = ranges

        var measured = 0
        for (range in ranges) {
            for (i in range) {
                if (layouts[i] == null) {
                    val layout = measure(refs[i]!!)
                    layouts[i] = layout
                    heights[i] = heightOf(layout)
                    exact[i] = true
                    measured++
                }
            }
        }
        measuredTotal += measured

        val slices = ranges.map { range ->
            @Suppress("UNCHECKED_CAST")
            MeasuredWindow.LayoutSlice(range.first, Array<Any?>(range.last - range.first + 1) { layouts[range.first + it] } as Array<L?>)
        }
        val stack = LineStack(heights.copyOf(n))
        val firstSeq = parsed.firstSeq
        return MeasuredWindow(parsed, stack, slices, measured) { index ->
            val layout = measure(parsed.lines[index].annotated)
            // Кэш мог уже уехать к следующему окну — тогда запоминать некуда
            if (baseSeq == firstSeq && index < size && refs[index] === parsed.lines[index].annotated) {
                layouts[index] = layout
                heights[index] = heightOf(layout)
                exact[index] = true
            }
            layout
        }
    }

    /** Точна ли высота строки с номером [seq] (размечена, а не оценена). */
    fun isExact(seq: Long): Boolean {
        val i = (seq - baseSeq).toInt()
        return i in 0 until size && exact[i]
    }

    /** Есть ли сейчас разметка у строки с номером [seq]. */
    fun hasLayout(seq: Long): Boolean {
        val i = (seq - baseSeq).toInt()
        return i in 0 until size && layouts[i] != null
    }

    fun clear() {
        key = null
        reset(0L, 0)
    }

    private fun reset(firstSeq: Long, n: Int) {
        baseSeq = firstSeq
        size = n
        refs = arrayOfNulls(n)
        heights = FloatArray(n)
        exact = BooleanArray(n)
        layouts = arrayOfNulls(n)
        previousRanges = emptyList()
    }

    /** Сдвигает массивы под новое окно; что было под тем же номером — остаётся. */
    private fun realign(firstSeq: Long, n: Int) {
        val shift = (firstSeq - baseSeq).toInt()
        if (shift == 0 && n == size) return
        val kept = if (shift in 0 until size) minOf(size - shift, n) else 0
        if (n > refs.size) {
            val capacity = maxOf(n, refs.size + refs.size / 2)
            refs = Array(capacity) { if (it < kept) refs[it + shift] else null }
            heights = FloatArray(capacity).also { if (kept > 0) System.arraycopy(heights, shift, it, 0, kept) }
            exact = BooleanArray(capacity).also { if (kept > 0) System.arraycopy(exact, shift, it, 0, kept) }
            layouts = Array(capacity) { if (it < kept) layouts[it + shift] else null }
        } else {
            if (kept > 0 && shift != 0) {
                System.arraycopy(refs, shift, refs, 0, kept)
                System.arraycopy(heights, shift, heights, 0, kept)
                System.arraycopy(exact, shift, exact, 0, kept)
                System.arraycopy(layouts, shift, layouts, 0, kept)
            }
            for (i in kept until n) {
                refs[i] = null
                exact[i] = false
                layouts[i] = null
            }
            // Хвост за окном — чтобы отпущенная разметка не жила в массиве
            for (i in n until size) layouts[i] = null
        }
        previousRanges = previousRanges.map { (it.first - shift)..(it.last - shift) }
        baseSeq = firstSeq
        size = n
    }
}
