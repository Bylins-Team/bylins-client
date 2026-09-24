package com.bylins.client.ui.scroll

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Стопка строк: по высотам — позиции. На ней держится перевод пикселя скролла
 * и указателя в строку, поэтому края и пустота проверяются отдельно.
 */
class LineStackTest {

    private val stack = LineStack(floatArrayOf(10f, 20f, 10f))

    @Test
    fun `верх строки — сумма высот строк над ней`() {
        assertEquals(listOf(0f, 10f, 30f), stack.tops.toList())
        assertEquals(40f, stack.totalHeight)
    }

    @Test
    fun `позиция попадает в свою строку, граница — в нижнюю`() {
        assertEquals(0, stack.indexAt(0f))
        assertEquals(0, stack.indexAt(9.9f))
        assertEquals(1, stack.indexAt(10f))
        assertEquals(1, stack.indexAt(29.9f))
        assertEquals(2, stack.indexAt(30f))
    }

    @Test
    fun `за краями — крайние строки, а не никуда`() {
        // Указатель выше содержимого или скролл ниже него должны давать строку
        assertEquals(0, stack.indexAt(-100f))
        assertEquals(2, stack.indexAt(1000f))
    }

    @Test
    fun `пустая стопка`() {
        val empty = LineStack(FloatArray(0))

        assertEquals(0f, empty.totalHeight)
        assertEquals(-1, empty.indexAt(5f))
    }

    @Test
    fun `одна строка`() {
        val one = LineStack(floatArrayOf(15f))

        assertEquals(0, one.indexAt(0f))
        assertEquals(0, one.indexAt(14f))
        assertEquals(0, one.indexAt(99f))
    }
}
