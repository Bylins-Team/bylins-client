package com.bylins.client.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TCP не обязан отдавать строку целиком: длинная строка приезжает двумя кусками.
 * Раньше каждый кусок сразу уходил в триггеры как строка, поэтому на половинках
 * ничего не совпадало -- подсветка не появлялась, gag не срабатывал.
 */
class TelnetClientLineAssemblyTest {

    @Test
    fun `строка из двух кусков собирается целиком`() {
        val client = TelnetClient()

        client.handleIncomingText("Лютый волк ")
        assertFalse(
            client.receivedData.value.contains("Лютый волк"),
            "незавершённая строка не должна уходить в вывод сразу"
        )

        client.handleIncomingText("сильно ударил вас.\n")
        assertEquals("Лютый волк сильно ударил вас.", client.receivedData.value.trimEnd('\n'))
    }

    @Test
    fun `завершённые строки отдаются сразу, хвост остаётся`() {
        val client = TelnetClient()

        client.handleIncomingText("первая\nвторая\nтретья без конца")

        val shown = client.receivedData.value
        assertTrue(shown.contains("первая"))
        assertTrue(shown.contains("вторая"))
        assertFalse(shown.contains("третья"), "хвост ждёт продолжения")

        client.flushPendingLine()
        assertTrue(client.receivedData.value.contains("третья без конца"))
    }

    @Test
    fun `промпт без перевода строки доезжает по флашу`() {
        val client = TelnetClient()

        client.handleIncomingText("630H 179M Вых:СВЮ> ")
        assertFalse(client.receivedData.value.contains("Вых:СВЮ"))

        client.flushPendingLine()
        assertTrue(client.receivedData.value.contains("630H 179M Вых:СВЮ> "))
    }
}
