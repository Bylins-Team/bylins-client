package com.bylins.client.ui.fonts

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemFontsTest {

    @Test
    fun `логические имена разбираются как раньше`() {
        assertEquals(FontFamily.Monospace, SystemFonts.resolve("MONOSPACE"))
        assertEquals(FontFamily.Serif, SystemFonts.resolve("SERIF"))
        assertEquals(FontFamily.SansSerif, SystemFonts.resolve("SANS_SERIF"))
        assertEquals(FontFamily.Cursive, SystemFonts.resolve("CURSIVE"))
    }

    @Test
    fun `неизвестное имя даёт Monospace, а не что попало`() {
        // Конфиг один на все машины: шрифта из другой системы здесь может не быть
        assertEquals(FontFamily.Monospace, SystemFonts.resolve("Такого Шрифта Нет 12345"))
        assertEquals(FontFamily.Monospace, SystemFonts.resolve(""))
    }

    @Test
    fun `список моноширинных шрифтов считается и не падает`() {
        val families = SystemFonts.monospacedFamilies()
        // На безголовой машине список может быть каким угодно, вплоть до пустого,
        // но обязан быть отсортированным и без повторов
        assertEquals(families.sorted(), families)
        assertEquals(families.distinct().size, families.size)
    }

    @Test
    fun `логические имена отличаются от системных`() {
        assertTrue(SystemFonts.isLogical("MONOSPACE"))
        assertTrue(!SystemFonts.isLogical("Lucida Console"))
        assertEquals("Monospace", SystemFonts.label("MONOSPACE"))
        assertEquals("Lucida Console", SystemFonts.label("Lucida Console"))
    }
}
