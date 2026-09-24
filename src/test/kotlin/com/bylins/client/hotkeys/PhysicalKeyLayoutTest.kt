package com.bylins.client.hotkeys

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Без физического кода клавиши (macOS, X11) буквы приводятся по раскладке
 * ЙЦУКЕН: Ctrl+C в русской раскладке — это Ctrl+«с», и копирование должно
 * сработать, как будто раскладка английская.
 */
class PhysicalKeyLayoutTest {

    @Test
    fun `русская буква переводится в латинскую клавишу на том же месте`() {
        assertEquals(Key.C, PhysicalKey.fromCyrillic('с'.code))
        assertEquals(Key.V, PhysicalKey.fromCyrillic('м'.code))
        assertEquals(Key.A, PhysicalKey.fromCyrillic('ф'.code))
        assertEquals(Key.F, PhysicalKey.fromCyrillic('а'.code))
        assertEquals(Key.X, PhysicalKey.fromCyrillic('ч'.code))
    }

    @Test
    fun `регистр не важен`() {
        assertEquals(Key.C, PhysicalKey.fromCyrillic('С'.code))
    }

    @Test
    fun `латиница, знаки и управляющие символы — не переводятся`() {
        assertNull(PhysicalKey.fromCyrillic('c'.code))
        assertNull(PhysicalKey.fromCyrillic('.'.code))
        assertNull(PhysicalKey.fromCyrillic(3))     // Ctrl+C как управляющий символ
        assertNull(PhysicalKey.fromCyrillic(0xFFFF)) // CHAR_UNDEFINED
        assertNull(PhysicalKey.fromCyrillic(-1))
    }

    @Test
    fun `вся буквенная часть ЙЦУКЕН покрыта`() {
        val letters = "йцукенгшщзфывапролдячсмить"
        val keys = letters.map { PhysicalKey.fromCyrillic(it.code) }
        assertEquals(letters.length, keys.filterNotNull().size, "непокрытые: ${letters.filterIndexed { i, _ -> keys[i] == null }}")
        assertEquals(letters.length, keys.toSet().size, "две буквы на одной клавише")
    }
}
