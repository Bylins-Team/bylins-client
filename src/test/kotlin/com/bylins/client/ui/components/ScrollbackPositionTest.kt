package com.bylins.client.ui.components

import com.bylins.client.ui.components.output.maxScrollOf
import com.bylins.client.ui.components.output.scrollbackPositionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Мигание разделителя при ходьбе по клеткам.
 *
 * Пришедшие строки увеличивают contentHeight, а с ним и maxScroll, прямо в текущей
 * композиции; сохранённая позиция скроллбэка обновлялась только следующим кадром. Один
 * кадр выходило "скроллбэк отстал от хвоста" -- панель показывала раздвоение и тут же
 * его убирала.
 */
class ScrollbackPositionTest {

    private val lineHeight = 16f

    @Test
    fun `в режиме следования скроллбэк всегда в конце, даже если сохранённое отстало`() {
        val viewport = 400f
        val before = maxScrollOf(contentHeightPx = 1000f, viewportPx = viewport)   // 600
        val after = maxScrollOf(contentHeightPx = 1080f, viewportPx = viewport)    // 680 -- пришли строки

        val position = scrollbackPositionOf(followMode = true, savedPx = before, maxScroll = after)

        assertEquals(after, position)
        // Раздвоение выводится из этого же значения -- разрыва с хвостом нет
        assertTrue(position >= after - lineHeight)
    }

    @Test
    fun `без следования держим сохранённую позицию`() {
        val maxScroll = 680f
        assertEquals(200f, scrollbackPositionOf(followMode = false, savedPx = 200f, maxScroll = maxScroll))
    }

    @Test
    fun `сохранённое за пределами обрезается`() {
        assertEquals(680f, scrollbackPositionOf(followMode = false, savedPx = 9000f, maxScroll = 680f))
        assertEquals(0f, scrollbackPositionOf(followMode = false, savedPx = -5f, maxScroll = 680f))
    }

    @Test
    fun `короткий лог -- скролла нет вовсе`() {
        val maxScroll = maxScrollOf(contentHeightPx = 100f, viewportPx = 400f)
        assertEquals(0f, maxScroll)
        assertEquals(0f, scrollbackPositionOf(followMode = true, savedPx = 0f, maxScroll = maxScroll))
    }
}
