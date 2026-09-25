package com.bylins.client.ui

import androidx.compose.ui.input.key.Key

/**
 * Сочетания буфера обмена в панели вывода.
 *
 * Вынесены из composable по той же причине, что и [OutputSearchShortcut]:
 * внутри него их нечем проверить. Клавиша сравнивается физическая
 * (`PhysicalKey.of`): код от Compose зависит от раскладки, и на русской
 * Ctrl+C приходил не как C — копирование молча не срабатывало. Форма —
 * по закрытому PR #27 Андрея, который нашёл то же самое.
 */
object OutputClipboardShortcut {

    /**
     * Копировать выделение: Ctrl+C (на macOS и Cmd+C) или Ctrl+Insert.
     *
     * @param isCommandPressed Ctrl, а на macOS ещё и Cmd — см. [CommandModifier]
     * @param isCtrlPressed именно Ctrl: Ctrl+Insert — сочетание Windows, Cmd там ни при чём
     */
    fun isCopy(key: Key, isCommandPressed: Boolean, isCtrlPressed: Boolean): Boolean =
        (key == Key.C && isCommandPressed) || (key == Key.Insert && isCtrlPressed)

    /** Выделить всё: Ctrl+A (на macOS и Cmd+A). */
    fun isSelectAll(key: Key, isCommandPressed: Boolean): Boolean =
        key == Key.A && isCommandPressed
}
