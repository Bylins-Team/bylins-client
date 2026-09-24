package com.bylins.client.ui.scroll

/**
 * Точка выделения в координатах (абсолютный seq строки, столбец).
 * Хранение в seq (а не в char-offset) позволяет выделению переживать
 * вытеснение строк из буфера так же, как якорю скролла.
 */
data class SelPoint(val seq: Long, val col: Int)

/**
 * Чистая модель выделения текста по всему буферу (без Compose).
 *
 * Выделение задаётся парой anchor/focus в координатах (seq, col) и не зависит
 * от того, раздвоено окно или нет, и от конкретных панелей — поэтому переживает
 * раздвоение/схлопывание во время drag. Текст собирается по строкам буфера,
 * какими их отдаёт панель.
 */
class OutputSelection {
    var anchor: SelPoint? = null
        private set
    var focus: SelPoint? = null
        private set

    val isEmpty: Boolean get() = anchor == null || anchor == focus

    fun start(p: SelPoint) {
        anchor = p
        focus = p
    }

    fun extendTo(p: SelPoint) {
        if (anchor == null) anchor = p
        focus = p
    }

    fun clear() {
        anchor = null
        focus = null
    }

    /** Выделить весь буфер от первой до последней строки. */
    fun selectAll(firstSeq: Long, lineCount: Int) {
        if (lineCount <= 0) {
            clear()
            return
        }
        anchor = SelPoint(firstSeq, 0)
        focus = SelPoint(firstSeq + lineCount - 1, Int.MAX_VALUE)
    }

    /** Нормализованная пара (начало, конец) по порядку в буфере, или null если пусто. */
    fun normalized(): Pair<SelPoint, SelPoint>? {
        val a = anchor ?: return null
        val f = focus ?: return null
        if (a == f) return null
        return if (compare(a, f) <= 0) a to f else f to a
    }

    /**
     * Текст выделения (включая скрытую «середину» при разрыве панелей).
     *
     * Строки — видимый текст без ANSI, по индексу от [firstSeq]. Выделение,
     * начавшееся в вытесненной строке, подтягивается к началу буфера.
     */
    fun copyText(firstSeq: Long, lineCount: Int, lineAt: (Int) -> CharSequence): String {
        val (min, max) = normalized() ?: return ""
        if (lineCount <= 0) return ""
        val lastSeq = firstSeq + lineCount - 1
        val from = maxOf(min.seq, firstSeq)
        val to = minOf(max.seq, lastSeq)
        if (from > to) return ""
        val out = StringBuilder()
        for (seq in from..to) {
            val line = lineAt((seq - firstSeq).toInt())
            val s = if (seq == min.seq) min.col.coerceIn(0, line.length) else 0
            val e = if (seq == max.seq) max.col.coerceIn(0, line.length) else line.length
            if (seq != from) out.append('\n')
            out.append(line, s, e)
        }
        return out.toString()
    }

    private fun compare(a: SelPoint, b: SelPoint): Int =
        if (a.seq != b.seq) a.seq.compareTo(b.seq) else a.col.compareTo(b.col)
}
