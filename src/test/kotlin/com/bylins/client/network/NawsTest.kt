package com.bylins.client.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * NAWS: размер окна сообщает клиент, и сервер переносит текст ровно по нему.
 * В байтах ловушка -- значение 255 надо удваивать, иначе оно оборвёт подпереговоры.
 */
class NawsTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun `обычный размер`() {
        assertContentEquals(
            bytes(255, 250, 31, 0, 120, 0, 40, 255, 240),
            Naws.subnegotiation(columns = 120, rows = 40)
        )
    }

    @Test
    fun `широкое окно -- значащий старший байт`() {
        // 300 = 0x012C
        assertContentEquals(
            bytes(255, 250, 31, 1, 0x2C, 0, 50, 255, 240),
            Naws.subnegotiation(columns = 300, rows = 50)
        )
    }

    @Test
    fun `значение 255 удваивается`() {
        // Иначе сервер принял бы наш байт за начало команды и оборвал разбор
        assertContentEquals(
            bytes(255, 250, 31, 0, 255, 255, 0, 255, 255, 255, 240),
            Naws.subnegotiation(columns = 255, rows = 255)
        )
    }

    @Test
    fun `255 в старшем байте тоже удваивается`() {
        // 65280 = 0xFF00
        val result = Naws.subnegotiation(columns = 65280, rows = 24)
        assertContentEquals(
            bytes(255, 250, 31, 255, 255, 0, 0, 24, 255, 240),
            result
        )
    }

    @Test
    fun `последовательность всегда начинается и кончается как положено`() {
        val result = Naws.subnegotiation(columns = 80, rows = 25)

        assertEquals(TelnetClient.IAC, result.first())
        assertEquals(TelnetClient.SB, result[1])
        assertEquals(TelnetClient.NAWS, result[2])
        assertEquals(TelnetClient.SE, result.last())
        assertEquals(TelnetClient.IAC, result[result.size - 2])
    }
}
