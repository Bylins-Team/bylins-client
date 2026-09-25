package com.bylins.client.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IAC GA (и IAC EOR) сервер ставит после строки приглашения, когда у игрока включено
 * "Автозавершение". Это точный признак, что ответ дорисован: по нему кадр показывается
 * сразу, без ожидания тишины, которое приходится подбирать под скорость сети.
 *
 * Раньше эти команды молча выбрасывались разбором.
 */
class TelnetEndOfAnswerTest {

    private val parser = TelnetParser()

    private fun parse(vararg bytes: Int) = parser.parse(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun `IAC GA распознаётся как конец ответа`() {
        val (text, commands) = parse(0xD0, 0x9F, 255, 249)   // "П" плюс IAC GA

        assertEquals(1, commands.size)
        assertEquals(TelnetCommandType.END_OF_ANSWER, commands[0].type)
        assertEquals("П", text)
    }

    @Test
    fun `IAC EOR тоже конец ответа`() {
        val (_, commands) = parse(255, 239)

        assertEquals(listOf(TelnetCommandType.END_OF_ANSWER), commands.map { it.type })
    }

    @Test
    fun `текст вокруг команды не теряется`() {
        val (text, commands) = parse(0x41, 255, 249, 0x42)   // "A" IAC GA "B"

        assertEquals("AB", text)
        assertEquals(1, commands.size)
    }

    @Test
    fun `экранированный IAC остаётся текстом`() {
        val (text, commands) = parse(255, 255)

        assertTrue(commands.isEmpty(), "IAC IAC -- это байт 255 в тексте, а не команда")
        assertEquals(1, text.toByteArray(Charsets.ISO_8859_1).size)
    }
}
