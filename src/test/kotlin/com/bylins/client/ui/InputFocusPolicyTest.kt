package com.bylins.client.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Прокрутка вывода срабатывала ровно один раз: главное окно на любой
 * необработанной клавише возвращало фокус в поле ввода, и панель вывода
 * выпадала из пути доставки событий.
 */
class InputFocusPolicyTest {

    private fun focus(
        key: Key,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        secondary: Boolean = false
    ) = InputFocusPolicy.shouldFocusInput(key, ctrl, alt, meta, secondary)

    @Test
    fun `клавиши прокрутки вывода фокус не забирают`() {
        listOf(
            Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd,
            Key.DirectionUp, Key.DirectionDown
        ).forEach {
            assertFalse(focus(it), "клавиша ${it} увела фокус из панели вывода")
        }
    }

    @Test
    fun `поиск по выводу остаётся рабочим`() {
        // F3 листает совпадения, Esc закрывает поиск — обе живут в панели вывода
        assertFalse(focus(Key.F3))
        assertFalse(focus(Key.Escape))
    }

    @Test
    fun `печатная клавиша возвращает фокус в поле ввода`() {
        // Ради этого поведение и заведено: начал печатать — команда набирается,
        // куда бы ни был наведён фокус
        listOf(Key.A, Key.One, Key.Spacebar, Key.Backspace, Key.Enter).forEach {
            assertTrue(focus(it), "клавиша ${it} не вернула фокус в ввод")
        }
    }

    @Test
    fun `сочетания с Ctrl и Alt фокус не трогают`() {
        assertFalse(focus(Key.A, ctrl = true))
        assertFalse(focus(Key.One, alt = true))
    }

    @Test
    fun `сочетания с Meta фокус не трогают`() {
        assertFalse(focus(Key.C, meta = true))
    }

    @Test
    fun `на вкладке настроек фокус не забираем`() {
        // Иначе набранное в поле настройки уезжает в строку ввода
        assertFalse(
            InputFocusPolicy.shouldFocusInput(
                key = Key.Four,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                secondaryFieldFocused = false,
                gameTabFocused = false
            ),
            "на вкладке настроек клавиша увела фокус в строку ввода"
        )
    }

    @Test
    fun `на вкладке с игрой фокус по-прежнему возвращается`() {
        assertTrue(
            InputFocusPolicy.shouldFocusInput(
                key = Key.Four,
                isCtrlPressed = false,
                isAltPressed = false,
                isMetaPressed = false,
                secondaryFieldFocused = false,
                gameTabFocused = true
            )
        )
    }

    @Test
    fun `правку другого поля не перебиваем`() {
        assertFalse(focus(Key.A, secondary = true))
    }
}
