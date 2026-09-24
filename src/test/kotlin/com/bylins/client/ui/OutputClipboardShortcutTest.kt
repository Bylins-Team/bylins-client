package com.bylins.client.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputClipboardShortcutTest {

    @Test
    fun `Ctrl+C и Ctrl+Insert копируют`() {
        assertTrue(OutputClipboardShortcut.isCopy(Key.C, isCommandPressed = true, isCtrlPressed = true))
        assertTrue(OutputClipboardShortcut.isCopy(Key.Insert, isCommandPressed = true, isCtrlPressed = true))
    }

    @Test
    fun `Cmd+C копирует, Cmd+Insert — нет`() {
        // На macOS команда — Cmd; Ctrl+Insert — сочетание Windows, и Cmd его не заменяет
        assertTrue(OutputClipboardShortcut.isCopy(Key.C, isCommandPressed = true, isCtrlPressed = false))
        assertFalse(OutputClipboardShortcut.isCopy(Key.Insert, isCommandPressed = true, isCtrlPressed = false))
    }

    @Test
    fun `без модификатора — обычные клавиши`() {
        assertFalse(OutputClipboardShortcut.isCopy(Key.C, isCommandPressed = false, isCtrlPressed = false))
        assertFalse(OutputClipboardShortcut.isCopy(Key.Insert, isCommandPressed = false, isCtrlPressed = false))
        assertFalse(OutputClipboardShortcut.isSelectAll(Key.A, isCommandPressed = false))
    }

    @Test
    fun `Ctrl+A выделяет всё, другие буквы — нет`() {
        assertTrue(OutputClipboardShortcut.isSelectAll(Key.A, isCommandPressed = true))
        assertFalse(OutputClipboardShortcut.isSelectAll(Key.C, isCommandPressed = true))
        assertFalse(OutputClipboardShortcut.isCopy(Key.A, isCommandPressed = true, isCtrlPressed = true))
    }
}
