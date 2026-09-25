package com.bylins.client.network

import mu.KotlinLogging
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

enum class TelnetCommandType {
    DO, DONT, WILL, WONT, SUBNEGOTIATION,

    /**
     * Конец ответа: IAC GA или IAC EOR после строки приглашения.
     *
     * Сервер ставит их, когда у игрока включено "Автозавершение". Для клиента это точный
     * признак, что ответ дорисован и можно показывать кадр, -- в отличие от ожидания по
     * времени, которое приходится подбирать под скорость сети.
     */
    END_OF_ANSWER
}

data class TelnetCommand(
    val type: TelnetCommandType,
    val option: Byte,
    val data: ByteArray = byteArrayOf()
)

private val logger = KotlinLogging.logger("TelnetParser")
class TelnetParser(
    private var encoding: String = "UTF-8"  // Конфигурируемая кодировка
) {
    // Stateful decoder для правильной обработки многобайтовых символов между буферами
    private var decoder: CharsetDecoder = createDecoder(encoding)
    private enum class State {
        NORMAL, IAC, COMMAND, SUBNEGOTIATION, SUBNEG_IAC
    }

    private var state = State.NORMAL
    private var currentCommand: Byte = 0
    private var currentOption: Byte = 0
    private val subnegBuffer = ByteArrayOutputStream()

    // Буфер для накопления текстовых байтов (НЕ сбрасывается между вызовами parse)
    private val textBuffer = ByteArrayOutputStream()
    private val commands = mutableListOf<TelnetCommand>()

    // Буфер для неполных многобайтовых последовательностей
    private val incompleteBytes = ByteArrayOutputStream()

    /**
     * Устанавливает кодировку для парсинга текста
     */
    fun setEncoding(newEncoding: String) {
        encoding = newEncoding
        decoder = createDecoder(newEncoding)
        incompleteBytes.reset()
    }

    private fun createDecoder(charsetName: String): CharsetDecoder {
        val charset = try {
            Charset.forName(charsetName)
        } catch (e: Exception) {
            logger.info { "Unsupported encoding: $charsetName, falling back to UTF-8" }
            Charsets.UTF_8
        }

        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
    }

    fun parse(data: ByteArray): Pair<String, List<TelnetCommand>> {
        textBuffer.reset()
        commands.clear()

        // Сначала добавляем неполные байты из предыдущего вызова
        if (incompleteBytes.size() > 0) {
            textBuffer.write(incompleteBytes.toByteArray())
            incompleteBytes.reset()
        }

        for (byte in data) {
            when (state) {
                State.NORMAL -> {
                    if (byte == TelnetClient.IAC) {
                        state = State.IAC
                    } else {
                        textBuffer.write(byte.toInt())
                    }
                }

                State.IAC -> {
                    when (byte) {
                        TelnetClient.DO, TelnetClient.DONT,
                        TelnetClient.WILL, TelnetClient.WONT -> {
                            currentCommand = byte
                            state = State.COMMAND
                        }
                        TelnetClient.SB -> {
                            subnegBuffer.reset()
                            state = State.SUBNEGOTIATION
                        }
                        TelnetClient.IAC -> {
                            // Escaped IAC (255)
                            textBuffer.write(TelnetClient.IAC.toInt())
                            state = State.NORMAL
                        }
                        TelnetClient.GA, TelnetClient.EOR -> {
                            commands.add(TelnetCommand(TelnetCommandType.END_OF_ANSWER, byte))
                            state = State.NORMAL
                        }
                        else -> {
                            // Unknown command, ignore
                            state = State.NORMAL
                        }
                    }
                }

                State.COMMAND -> {
                    currentOption = byte
                    val commandType = when (currentCommand) {
                        TelnetClient.DO -> TelnetCommandType.DO
                        TelnetClient.DONT -> TelnetCommandType.DONT
                        TelnetClient.WILL -> TelnetCommandType.WILL
                        TelnetClient.WONT -> TelnetCommandType.WONT
                        else -> null
                    }

                    if (commandType != null) {
                        commands.add(TelnetCommand(commandType, currentOption))
                    }
                    state = State.NORMAL
                }

                State.SUBNEGOTIATION -> {
                    if (byte == TelnetClient.IAC) {
                        state = State.SUBNEG_IAC
                    } else {
                        if (currentOption == 0.toByte() && subnegBuffer.size() == 0) {
                            // Первый байт - это опция
                            currentOption = byte
                        } else {
                            subnegBuffer.write(byte.toInt())
                        }
                    }
                }

                State.SUBNEG_IAC -> {
                    when (byte) {
                        TelnetClient.SE -> {
                            // Конец subnegotiation
                            commands.add(
                                TelnetCommand(
                                    TelnetCommandType.SUBNEGOTIATION,
                                    currentOption,
                                    subnegBuffer.toByteArray()
                                )
                            )
                            currentOption = 0
                            state = State.NORMAL
                        }
                        TelnetClient.IAC -> {
                            // Escaped IAC внутри subnegotiation
                            subnegBuffer.write(TelnetClient.IAC.toInt())
                            state = State.SUBNEGOTIATION
                        }
                        else -> {
                            // Некорректная последовательность
                            state = State.NORMAL
                        }
                    }
                }
            }
        }

        // Декодируем накопленные байты
        val bytes = textBuffer.toByteArray()
        if (bytes.isEmpty()) {
            return Pair("", commands.toList())
        }

        val inputBuffer = ByteBuffer.wrap(bytes)
        val outputBuffer = CharBuffer.allocate((bytes.size * decoder.maxCharsPerByte()).toInt() + 10)

        // decode() сохраняет состояние для неполных последовательностей
        // false означает "не конец потока" - decoder сохранит неполные последовательности во внутреннем состоянии
        val result = decoder.decode(inputBuffer, outputBuffer, false)

        // Проверяем результат декодирования
        when {
            result.isUnderflow -> {
                // Decoder прочитал всё что смог
                // Если остались байты, значит это неполная UTF-8 последовательность
                if (inputBuffer.hasRemaining()) {
                    val remaining = ByteArray(inputBuffer.remaining())
                    inputBuffer.get(remaining)
                    incompleteBytes.write(remaining)
                }
            }
            result.isOverflow -> {
                // Output buffer переполнен (не должно происходить с нашим размером)
                logger.info { "Output buffer overflow - this should not happen" }
            }
            result.isMalformed -> {
                logger.info { "Malformed input at position ${inputBuffer.position()}" }
            }
            result.isUnmappable -> {
                logger.info { "Unmappable character at position ${inputBuffer.position()}" }
            }
        }

        outputBuffer.flip()
        val text = outputBuffer.toString()

        return Pair(text, commands.toList())
    }
}
