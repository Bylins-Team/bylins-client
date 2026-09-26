package com.bylins.client.network

/**
 * Телнет-подпереговоры о размере окна (NAWS, RFC 1073).
 *
 * Сервер переносит текст по ширине, которую иначе приходится задавать руками; с NAWS он
 * узнаёт настоящий размер окна и переносит ровно по нему. Размер сообщает клиент -- это он
 * знает своё окно, -- поэтому мы посылаем `IAC WILL NAWS` и, когда сервер согласится
 * (`IAC DO NAWS`), отправляем числа.
 *
 * Байты собираются отдельно от сетевого кода: в них есть ловушка -- значение 255 внутри
 * подпереговоров надо удваивать, иначе оно оборвёт последовательность.
 */
object Naws {

    /** Сколько знаков и строк имеет смысл сообщать: за пределами сервер всё равно не примет. */
    const val MIN_COLUMNS = 20
    const val MAX_COLUMNS = 500
    const val MIN_ROWS = 5
    const val MAX_ROWS = 200

    /**
     * IAC SB NAWS <ширина: 2 байта> <высота: 2 байта> IAC SE.
     *
     * @param columns ширина окна в знаках
     * @param rows высота окна в строках
     */
    fun subnegotiation(columns: Int, rows: Int): ByteArray {
        val out = ArrayList<Byte>(12)
        out += TelnetClient.IAC
        out += TelnetClient.SB
        out += TelnetClient.NAWS
        appendValue(out, columns)
        appendValue(out, rows)
        out += TelnetClient.IAC
        out += TelnetClient.SE
        return out.toByteArray()
    }

    private fun appendValue(out: MutableList<Byte>, value: Int) {
        val high = ((value shr 8) and 0xFF).toByte()
        val low = (value and 0xFF).toByte()
        appendByte(out, high)
        appendByte(out, low)
    }

    private fun appendByte(out: MutableList<Byte>, byte: Byte) {
        out += byte
        // 255 внутри подпереговоров удваивается, иначе разбор оборвётся на нём
        if (byte == TelnetClient.IAC) {
            out += TelnetClient.IAC
        }
    }
}
