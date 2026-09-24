package com.bylins.client.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Собственный вывод клиента должен доходить до плагинов.
 *
 * Строки сервера идут через разбор входящих данных и порождают событие для
 * плагинов, а эхо команд и ответы #-команд печатаются напрямую в буфер — мимо
 * событий. Из-за этого журнал вывода для ИИ видел только половину: агент
 * отправлял «#var light шар», не получал ответа и считал, что не сработало.
 */
class LocalOutputHookTest {

    private fun clientWithListener(): Pair<TelnetClient, MutableList<String>> {
        val seen = mutableListOf<String>()
        val client = TelnetClient()
        client.onLocalOutput = { seen.add(it) }
        return client to seen
    }

    @Test
    fun `ответ локальной команды виден слушателю`() {
        val (client, seen) = clientWithListener()

        client.addLocalOutput("Переменная light = шар")

        assertEquals(listOf("Переменная light = шар"), seen)
    }

    @Test
    fun `эхо команды видно слушателю без оформления`() {
        val (client, seen) = clientWithListener()

        client.echoCommand("смотреть")

        // В буфер эхо уходит в цвете, слушателю — сама команда
        assertEquals(listOf("смотреть"), seen)
    }

    @Test
    fun `вывод скриптов и плагинов виден слушателю`() {
        val (client, seen) = clientWithListener()

        client.addToOutputRaw("[Маршрут] Иду к «Площадь», шагов: 4")

        assertEquals(listOf("[Маршрут] Иду к «Площадь», шагов: 4"), seen)
    }

    @Test
    fun `текст всё равно попадает в буфер`() {
        val (client, seen) = clientWithListener()

        client.addLocalOutput("привет")

        assertTrue(client.snapshot.value.text().contains("привет"), "текст потерян из буфера")
        assertEquals(1, seen.size)
    }

    @Test
    fun `без слушателя ничего не ломается`() {
        val client = TelnetClient()

        client.addLocalOutput("привет")
        client.echoCommand("смотреть")
        client.addToOutputRaw("текст")

        assertTrue(client.snapshot.value.text().contains("привет"))
    }
}
