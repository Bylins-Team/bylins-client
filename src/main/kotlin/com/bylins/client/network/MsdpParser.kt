package com.bylins.client.network

import mu.KotlinLogging
import java.nio.charset.Charset

private val logger = KotlinLogging.logger("MsdpParser")

/**
 * Разбор MSDP.
 *
 * Данные приходят байтами, поэтому и позиция в буфере считается в байтах: под UTF-8 русская
 * буква занимает два байта, и счёт по длине декодированной строки уводил позицию в середину
 * символа. Кодировка берётся та же, что у основного потока -- сервер отдаёт текст MSDP в
 * кодировке сессии, а не всегда в UTF-8.
 */
class MsdpParser(
    encoding: String = "UTF-8"
) {
    companion object {
        const val MSDP_VAR: Byte = 1
        const val MSDP_VAL: Byte = 2
        const val MSDP_TABLE_OPEN: Byte = 3
        const val MSDP_TABLE_CLOSE: Byte = 4
        const val MSDP_ARRAY_OPEN: Byte = 5
        const val MSDP_ARRAY_CLOSE: Byte = 6
    }

    private var charset: Charset = resolveCharset(encoding)

    fun setEncoding(newEncoding: String) {
        charset = resolveCharset(newEncoding)
    }

    private fun resolveCharset(charsetName: String): Charset = try {
        Charset.forName(charsetName)
    } catch (e: Exception) {
        logger.info { "Unsupported encoding: $charsetName, falling back to UTF-8" }
        Charsets.UTF_8
    }

    fun parse(data: ByteArray): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var pos = 0

        while (pos < data.size) {
            if (data[pos] == MSDP_VAR) {
                pos++
                val (varName, afterName) = readString(data, pos)
                pos = afterName

                if (pos < data.size && data[pos] == MSDP_VAL) {
                    pos++
                    val (value, afterValue) = readValue(data, pos)
                    result[varName] = value
                    pos = afterValue
                }
            } else {
                pos++
            }
        }

        return result
    }

    /** Строка до ближайшего управляющего байта. Второй элемент -- позиция этого байта. */
    private fun readString(data: ByteArray, start: Int): Pair<String, Int> {
        var end = start
        while (end < data.size && !isControl(data[end])) {
            end++
        }

        return Pair(String(data, start, end - start, charset), end)
    }

    private fun isControl(byte: Byte): Boolean =
        byte == MSDP_VAR || byte == MSDP_VAL ||
            byte == MSDP_TABLE_OPEN || byte == MSDP_TABLE_CLOSE ||
            byte == MSDP_ARRAY_OPEN || byte == MSDP_ARRAY_CLOSE

    private fun readValue(data: ByteArray, start: Int): Pair<Any, Int> {
        var pos = start

        return when {
            pos < data.size && data[pos] == MSDP_ARRAY_OPEN -> {
                pos++
                val array = mutableListOf<Any>()

                // Элементом массива бывает не только строка: наш сервер шлёт GROUP массивом
                // таблиц. Поэтому элементы разбираются тем же readValue, что и всё остальное.
                while (pos < data.size && data[pos] != MSDP_ARRAY_CLOSE) {
                    if (data[pos] == MSDP_VAL) {
                        pos++
                        continue
                    }

                    val (value, afterValue) = readValue(data, pos)
                    if (value !is String || value.isNotEmpty()) {
                        array.add(value)
                    }

                    // Страховка от вечного цикла: если разбор элемента не сдвинул позицию,
                    // дальше двигаемся сами, иначе цикл не кончится никогда.
                    pos = if (afterValue > pos) afterValue else pos + 1
                }

                if (pos < data.size && data[pos] == MSDP_ARRAY_CLOSE) {
                    pos++
                }

                Pair(array, pos)
            }

            pos < data.size && data[pos] == MSDP_TABLE_OPEN -> {
                pos++
                val table = mutableMapOf<String, Any>()

                while (pos < data.size && data[pos] != MSDP_TABLE_CLOSE) {
                    if (data[pos] == MSDP_VAR) {
                        pos++
                        val (key, afterKey) = readString(data, pos)
                        pos = afterKey

                        if (pos < data.size && data[pos] == MSDP_VAL) {
                            pos++
                            val (value, afterValue) = readValue(data, pos)
                            table[key] = value
                            pos = afterValue
                        }
                    } else {
                        pos++
                    }
                }

                if (pos < data.size && data[pos] == MSDP_TABLE_CLOSE) {
                    pos++
                }

                Pair(table, pos)
            }

            else -> {
                val (str, afterString) = readString(data, pos)
                Pair(str, afterString)
            }
        }
    }
}
