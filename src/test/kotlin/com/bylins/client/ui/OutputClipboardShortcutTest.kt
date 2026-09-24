package com.bylins.client.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Копирование с экрана работало только на английской раскладке: сравнивали клавишу,
 * которую вернул Compose, а её код зависит от раскладки. Теперь приходит физическая.
 */
class OutputClipboardShortcutTest {

    @Test
    fun `Ctrl+C копирует`() {
        assertTrue(OutputClipboardShortcut.isCopy(Key.C, isCommandPressed = true, isCtrlPressed = true))
    }

    @Test
    fun `Ctrl+Insert копирует тоже`() {
        assertTrue(OutputClipboardShortcut.isCopy(Key.Insert, isCommandPressed = false, isCtrlPressed = true))
    }

    @Test
    fun `без модификатора не копирует`() {
        assertFalse(OutputClipboardShortcut.isCopy(Key.C, isCommandPressed = false, isCtrlPressed = false))
        assertFalse(OutputClipboardShortcut.isCopy(Key.Insert, isCommandPressed = false, isCtrlPressed = false))
        assertFalse(OutputClipboardShortcut.isCopy(Key.V, isCommandPressed = true, isCtrlPressed = true))
    }

    @Test
    fun `Ctrl+A выделяет всё`() {
        assertTrue(OutputClipboardShortcut.isSelectAll(Key.A, isCommandPressed = true))
        assertFalse(OutputClipboardShortcut.isSelectAll(Key.A, isCommandPressed = false))
        assertFalse(OutputClipboardShortcut.isSelectAll(Key.S, isCommandPressed = true))
    }
}
