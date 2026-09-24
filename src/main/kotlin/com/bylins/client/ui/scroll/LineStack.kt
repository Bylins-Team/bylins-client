package com.bylins.client.ui.scroll

/**
 * Строки, сложенные стопкой: по высотам — вертикальные позиции.
 *
 * Разметка теперь у каждой логической строки своя, и «где строка по
 * вертикали» — это сумма высот строк над ней. Здесь только арифметика, без
 * Compose: чтобы её можно было проверить юнитами, а не глазами.
 */
class LineStack(private val heights: FloatArray) {

    /** Верх каждой строки в координатах содержимого. */
    val tops: FloatArray = FloatArray(heights.size).also { tops ->
        var y = 0f
        for (i in heights.indices) {
            tops[i] = y
            y += heights[i]
        }
    }

    val size: Int get() = heights.size

    val totalHeight: Float = if (heights.isEmpty()) 0f else tops.last() + heights.last()

    fun heightOf(index: Int): Float = heights[index]

    /**
     * Индекс строки, на которую приходится вертикальная позиция [y].
     *
     * Выше содержимого — первая строка, ниже — последняя: указатель и скролл
     * за краями должны попадать в крайние строки, а не никуда. Для пустой
     * стопки — -1.
     */
    fun indexAt(y: Float): Int {
        if (heights.isEmpty()) return -1
        if (y <= 0f) return 0
        // Последняя строка с top <= y — двоичный поиск по возрастающим top
        var low = 0
        var high = heights.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (tops[mid] <= y) low = mid else high = mid - 1
        }
        return low
    }
}
