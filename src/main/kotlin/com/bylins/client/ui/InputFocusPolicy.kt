package com.bylins.client.ui

import androidx.compose.ui.input.key.Key

/**
 * Решает, уводить ли фокус в поле ввода на необработанной клавише.
 *
 * Клиент устроен так, что печатать можно, не целясь мышью в поле ввода: любая
 * клавиша возвращает фокус туда. Но тем же движением фокус отбирался у панели
 * вывода, которая листается PageUp/PageDown: первое нажатие ещё доходило до неё
 * (путь доставки события собирается до перехвата), а дальше панель выпадала из
 * пути, и прокрутка молча переставала работать.
 *
 * Поэтому клавиши, которыми нельзя набрать текст, фокус не перехватывают.
 */
object InputFocusPolicy {

    /** Навигация и правка по выводу: прокрутка, поиск, выход из поиска. */
    private val keepFocusKeys: Set<Key> = buildSet {
        addAll(
            listOf(
                Key.PageUp, Key.PageDown,
                Key.MoveHome, Key.MoveEnd,
                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                Key.Escape, Key.Tab,
            )
        )
        addAll(
            listOf(
                Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6,
                Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
            )
        )
    }

    /**
     * @param key клавиша нажатия, не разобранного как хоткей
     * @param secondaryFieldFocused правится другое поле (диалог, поиск) — туда не лезем
     * @param gameTabFocused открыта вкладка с игрой. На прочих вкладках (настройки,
     *   триггеры, алиасы) текст набирают в тамошние поля, и перехват фокуса означал бы,
     *   что набранное уезжает в строку ввода: правило «любая клавиша возвращает фокус»
     *   придумано для игры, а не для правки настроек
     */
    fun shouldFocusInput(
        key: Key,
        isCtrlPressed: Boolean,
        isAltPressed: Boolean,
        isMetaPressed: Boolean,
        secondaryFieldFocused: Boolean,
        gameTabFocused: Boolean = true
    ): Boolean =
        gameTabFocused && !isCtrlPressed && !isAltPressed && !isMetaPressed
            && !secondaryFieldFocused && key !in keepFocusKeys
}
