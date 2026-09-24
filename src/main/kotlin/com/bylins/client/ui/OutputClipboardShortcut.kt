package com.bylins.client.ui

import androidx.compose.ui.input.key.Key

/**
 * Сочетания буфера обмена в панели вывода.
 *
 * Клавиша сюда приходит физическая (`PhysicalKey.of`), а не та, что вернул Compose:
 * штатный код зависит от раскладки, и на русской Ctrl+C не совпадал с `Key.C` -- скопировать
 * выделенное можно было, только переключившись на английскую. Поиск (Ctrl+F) эту грабку
 * обошёл раньше, см. [OutputSearchShortcut].
 */
object OutputClipboardShortcut {

    /**
     * @param isCommandPressed Ctrl, а на macOS ещё и Cmd -- см. [CommandModifier]
     * @param isCtrlPressed именно Ctrl: Ctrl+Insert -- старый способ копирования, Cmd тут не при чём
     */
    fun isCopy(key: Key, isCommandPressed: Boolean, isCtrlPressed: Boolean): Boolean =
        (key == Key.C && isCommandPressed) || (key == Key.Insert && isCtrlPressed)

    fun isSelectAll(key: Key, isCommandPressed: Boolean): Boolean =
        key == Key.A && isCommandPressed
}
