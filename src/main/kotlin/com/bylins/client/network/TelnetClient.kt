package com.bylins.client.network

import mu.KotlinLogging
import com.bylins.client.ClientState
import com.bylins.client.ui.scroll.ContentSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

private val logger = KotlinLogging.logger("TelnetClient")
class TelnetClient(
    private val clientState: ClientState? = null,
    encoding: String = "UTF-8"
) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _receivedData = MutableStateFlow("")
    val receivedData: StateFlow<String> = _receivedData

    // Снимок с абсолютной нумерацией строк (для логики автоскролла/выделения).
    // firstSeq = seq первой строки текущего буфера; растёт при обрезке сверху.
    private val _snapshot = MutableStateFlow(ContentSnapshot.EMPTY)
    val snapshot: StateFlow<ContentSnapshot> = _snapshot
    private var firstSeq: Long = 0L

    // Буфер правят три разных потока: читающий сокет, UI (эхо команды игрока)
    // и потоки плагинов (команды ИИ через ai-control). Все правки — это
    // read-modify-write целой строки буфера, поэтому без замка одна запись
    // затирает другую: пропадает то эхо команды, то кусок вывода сервера.
    private val bufferLock = Any()

    /** Согласованно публикует текст буфера и его снимок. */
    private fun setReceived(text: String) {
        _receivedData.value = text
        _snapshot.value = ContentSnapshot(text, firstSeq, ContentSnapshot.countLines(text))
    }

    private val telnetParser = TelnetParser(encoding)
    private val msdpParser = MsdpParser()
    private val gmcpParser = GmcpParser()

    /**
     * Устанавливает кодировку для telnet соединения
     */
    fun setEncoding(encoding: String) {
        telnetParser.setEncoding(encoding)
        logger.info { "Encoding changed to: $encoding" }
    }

    // Ограничение на размер буфера вывода (1 МБ)
    // Уменьшено для экономии памяти - вкладки хранят свою историю отдельно
    private val MAX_BUFFER_SIZE = 1024 * 1024 // 1 MB

    // Сколько ждать продолжения строки, прежде чем отдать хвост на экран. Столько же
    // задерживается промпт -- на глаз незаметно, а строки собираются целиком.
    private val PENDING_FLUSH_MS = 60L

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            // Убеждаемся что предыдущее соединение закрыто
            if (_isConnected.value) {
                disconnect()
            }

            socket = Socket(host, port)
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            _isConnected.value = true

            // Уведомляем пользователя об успешном подключении
            appendToBuffer("\u001B[1;32m[Connected to $host:$port]\u001B[0m\n\n")

            // Отправляем поддерживаемые опции Telnet
            sendTelnetNegotiation()

            // Запускаем чтение данных
            startReading()
        } catch (e: IOException) {
            disconnect()
            throw e
        }
    }

    fun disconnect() {
        try {
            pendingFlushJob?.cancel()
            flushPendingLine()
            readJob?.cancel()
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Обнуляем ресурсы
            readJob = null
            inputStream = null
            outputStream = null
            socket = null

            // Меняем статус подключения
            val wasConnected = _isConnected.value
            _isConnected.value = false

            // Уведомляем пользователя о разрыве соединения (только если были подключены)
            if (wasConnected) {
                appendToBuffer("\u001B[1;31m[Disconnected]\u001B[0m\n")
            }
        }
    }

    /**
     * Отправляет команду серверу.
     *
     * Пишет под тем же замком, что и telnet-команды. Раньше запись шла без
     * него, а каждая команда уходила своей корутиной в пул IO: при залпе —
     * например, пачке команд от ИИ — записи наслаивались, и до сервера
     * доходило перемешанное или склеенное.
     */
    fun send(command: String) {
        val bytes = (command + "\r\n").toByteArray(Charsets.UTF_8)
        var shouldDisconnect = false
        synchronized(writeLock) {
            try {
                outputStream?.write(bytes)
                outputStream?.flush()
            } catch (e: IOException) {
                logger.error { "Error sending command: ${e.message}" }
                shouldDisconnect = true
            }
        }
        // disconnect() вне замка: он берёт его сам при отправке telnet-команд
        if (shouldDisconnect) disconnect()
    }

    /**
     * Слушатель собственного вывода клиента: эхо команд, ответы #-команд,
     * сообщения плагинов и ходока.
     *
     * Всё это, в отличие от строк сервера, не проходит через разбор
     * входящих данных, а значит и мимо событий плагинов: плагину, который
     * ведёт журнал вывода, видна только половина происходящего.
     *
     * Зовётся вне замка буфера — обработчик чужой, держать под ним весь
     * вывод нельзя.
     */
    var onLocalOutput: ((String) -> Unit)? = null

    /**
     * Добавляет текст в лог (для эхо команд)
     */
    fun echoCommand(command: String) {
        appendToBuffer("\u001B[1;36m$command\u001B[0m\n")
        onLocalOutput?.invoke(command)
    }

    /**
     * Добавляет произвольный текст в output (для системных сообщений)
     * Обрабатывает триггеры
     */
    fun addToOutput(text: String) {
        val textWithNewline = text + "\n"
        // Обрабатываем текст триггерами и получаем модифицированную версию с colorize
        val modifiedText = clientState?.processIncomingText(textWithNewline) ?: textWithNewline
        appendToBuffer(modifiedText)
    }

    /**
     * Добавляет текст в output БЕЗ обработки триггерами
     * Используется для echo из скриптов чтобы избежать рекурсии
     * Вставляет перед незавершённой строкой (промптом/картой)
     */
    fun addToOutputRaw(text: String) {
        synchronized(bufferLock) {
            val currentValue = _receivedData.value
            val lastNewlineIndex = currentValue.lastIndexOf('\n')
            val incompleteLine = if (lastNewlineIndex == -1) currentValue else currentValue.substring(lastNewlineIndex + 1)

            if (incompleteLine.isNotEmpty()) {
                // Вставляем перед незавершённой строкой
                val bufferComplete = if (lastNewlineIndex == -1) "" else currentValue.substring(0, lastNewlineIndex + 1)
                setReceived(bufferComplete + text + "\n" + incompleteLine)
            } else {
                appendToBuffer(text + "\n")
            }
        }
        onLocalOutput?.invoke(text)
    }

    /**
     * Добавляет локальный вывод (результат #команд) с сохранением промпта
     * Если последняя строка в буфере не заканчивается на \n (это промпт),
     * то выводит сообщение ПЕРЕД промптом
     */
    fun addLocalOutput(text: String) {
        synchronized(bufferLock) {
            val currentValue = _receivedData.value

            // Находим последний перенос строки
            val lastNewlineIndex = currentValue.lastIndexOf('\n')

            // Текст после последнего \n (потенциальный промпт)
            val possiblePrompt = if (lastNewlineIndex == -1) {
                currentValue
            } else {
                currentValue.substring(lastNewlineIndex + 1)
            }

            // Если есть незавершённая строка (промпт), вставляем наш текст перед ней
            if (possiblePrompt.isNotEmpty()) {
                // Убираем промпт из буфера
                val bufferWithoutPrompt = if (lastNewlineIndex == -1) {
                    ""
                } else {
                    currentValue.substring(0, lastNewlineIndex + 1)
                }

                // Добавляем наш текст + промпт
                setReceived(bufferWithoutPrompt + text + "\n" + possiblePrompt)
            } else {
                // Нет промпта, просто добавляем текст
                appendToBuffer(text + "\n")
            }
        }
        onLocalOutput?.invoke(text)
    }

    /**
     * Добавляет текст в буфер с ограничением размера
     */
    private fun appendToBuffer(text: String) = synchronized(bufferLock) {
        val currentValue = _receivedData.value
        val totalLength = currentValue.length + text.length

        // Если буфер превышает лимит, обрезаем старые строки
        if (totalLength > MAX_BUFFER_SIZE) {
            // Находим позицию, с которой начинаем хранить данные (оставляем последние 80% буфера)
            val keepSize = (MAX_BUFFER_SIZE * 0.8).toInt()
            val cutPosition = currentValue.length - keepSize

            // Ищем ближайший перенос строки после позиции обрезки
            val nextNewline = currentValue.indexOf('\n', cutPosition.coerceAtLeast(0))

            val removedEnd = if (nextNewline != -1) nextNewline + 1 else cutPosition.coerceAtLeast(0)
            val truncatedValue = currentValue.substring(removedEnd)

            // Сколько логических строк удалено сверху. Строка-сентинел [Buffer cleared]
            // добавляется обратно как одна строка, поэтому seq сдвигается на (removed - 1),
            // чтобы сохранившиеся строки сохранили свой абсолютный seq.
            val removedLines = currentValue.substring(0, removedEnd).count { it == '\n' }
            firstSeq += (removedLines - 1).coerceAtLeast(0)

            setReceived("\u001B[1;33m[Buffer cleared]\u001B[0m\n" + truncatedValue + text)
        } else {
            setReceived(currentValue + text)
        }
    }

    // Недочитанная строка. TCP режет поток где придётся, и длинная строка приезжает
    // двумя кусками: триггеры на половинках не совпадают, подсветка не появляется, gag не
    // срабатывает. Поэтому триггерам отдаём только строки, дошедшие целиком, а хвост держим
    // до перевода строки. Хвост без продолжения -- это промпт, его отдаём по таймауту, иначе
    // он не появился бы на экране до следующего сообщения.
    private val pendingLine = StringBuilder()
    private val pendingLock = Any()
    private var pendingFlushJob: Job? = null

    internal fun handleIncomingText(text: String) {
        val complete = synchronized(pendingLock) {
            pendingLine.append(text)
            val lastNewline = pendingLine.lastIndexOf("\n")
            if (lastNewline == -1) {
                ""
            } else {
                val done = pendingLine.substring(0, lastNewline + 1)
                pendingLine.delete(0, lastNewline + 1)
                done
            }
        }

        if (complete.isNotEmpty()) {
            val modifiedText = clientState?.processIncomingText(complete) ?: complete
            appendToBuffer(modifiedText)
        }

        schedulePendingFlush()
    }

    private fun schedulePendingFlush() {
        pendingFlushJob?.cancel()

        val hasPending = synchronized(pendingLock) { pendingLine.isNotEmpty() }
        if (!hasPending) {
            return
        }

        pendingFlushJob = CoroutineScope(Dispatchers.IO).launch {
            delay(PENDING_FLUSH_MS)
            flushPendingLine()
        }
    }

    /** Отдать недочитанный хвост как есть: продолжения не будет. */
    internal fun flushPendingLine() {
        val tail = synchronized(pendingLock) {
            val current = pendingLine.toString()
            pendingLine.setLength(0)
            current
        }

        if (tail.isEmpty()) {
            return
        }

        val modifiedText = clientState?.processIncomingText(tail) ?: tail
        appendToBuffer(modifiedText)
    }

    private fun startReading() {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val buffer = ByteArray(4096)
                while (isActive && _isConnected.value) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead == -1) {
                        // Соединение закрыто сервером
                        disconnect()
                        break
                    }

                    val data = buffer.copyOf(bytesRead)
                    val (text, telnetCommands) = telnetParser.parse(data)

                    // Telnet-команды разбираем ПЕРЕД текстом: в одном пакете
                    // сервер шлёт и MSDP с новой комнатой, и её описание.
                    // Разбери мы текст первым — триггеры и контекстные правила
                    // с областью действия проверялись бы против ещё старой
                    // комнаты и молча не срабатывали при входе, оживая только
                    // после «смотреть», когда позиция уже обновилась.
                    telnetCommands.forEach { handleTelnetCommand(it) }

                    if (text.isNotEmpty()) {
                        handleIncomingText(text)
                    }
                }
            } catch (e: IOException) {
                // Ошибка чтения - разрываем соединение
                if (isActive) {
                    disconnect()
                }
            } finally {
                // Убеждаемся что соединение закрыто
                if (_isConnected.value) {
                    disconnect()
                }
            }
        }
    }

    private fun sendTelnetNegotiation() {
        // IAC WILL TERMINAL_TYPE
        sendTelnetCommand(byteArrayOf(IAC, WILL, TERMINAL_TYPE))

        // IAC DO NAWS (Negotiate About Window Size)
        sendTelnetCommand(byteArrayOf(IAC, DO, NAWS))

        // IAC WILL MSDP
        sendTelnetCommand(byteArrayOf(IAC, WILL, MSDP))

        // IAC DO GMCP (для расширенных данных)
        sendTelnetCommand(byteArrayOf(IAC, DO, GMCP))
    }

    // Объект для синхронизации записи в outputStream
    private val writeLock = Any()

    private fun sendTelnetCommand(command: ByteArray) {
        if (!_isConnected.value) return
        var shouldDisconnect = false
        synchronized(writeLock) {
            try {
                outputStream?.write(command)
                outputStream?.flush()
            } catch (e: IOException) {
                logger.error { "Error sending telnet command: ${e.message}" }
                shouldDisconnect = true
            }
        }
        // Вызываем disconnect() вне synchronized блока чтобы избежать deadlock
        if (shouldDisconnect) {
            disconnect()
        }
    }

    private fun handleTelnetCommand(command: TelnetCommand) {
        when (command.type) {
            TelnetCommandType.DO -> {
                // Сервер просит нас включить опцию
                when (command.option) {
                    TERMINAL_TYPE -> sendTelnetCommand(byteArrayOf(IAC, WILL, TERMINAL_TYPE))
                    MSDP -> sendTelnetCommand(byteArrayOf(IAC, WILL, MSDP))
                }
            }
            TelnetCommandType.WILL -> {
                // Сервер сообщает, что будет использовать опцию
                when (command.option) {
                    MSDP -> {
                        sendTelnetCommand(byteArrayOf(IAC, DO, MSDP))
                        clientState?.setMsdpEnabled(true)
                    }
                    GMCP -> sendTelnetCommand(byteArrayOf(IAC, DO, GMCP))
                }
            }
            TelnetCommandType.SUBNEGOTIATION -> {
                handleSubnegotiation(command.option, command.data)
            }
            else -> { /* Ignore other commands for now */ }
        }
    }

    private fun handleSubnegotiation(option: Byte, data: ByteArray) {
        when (option) {
            TERMINAL_TYPE -> {
                // Отправляем тип терминала
                val termType = "xterm-256color".toByteArray()
                val response = byteArrayOf(IAC, SB, TERMINAL_TYPE, 0) + termType + byteArrayOf(IAC, SE)
                sendTelnetCommand(response)
            }
            MSDP -> {
                // Обработка MSDP данных
                parseMSDP(data)
            }
            GMCP -> {
                // Обработка GMCP данных
                parseGMCP(data)
            }
        }
    }

    private fun parseMSDP(data: ByteArray) {
        try {
            logger.debug { "MSDP raw data (${data.size} bytes): ${data.take(100).joinToString(",") { it.toString() }}" }
            val msdpData = msdpParser.parse(data)
            logger.debug { "MSDP parsed: $msdpData" }
            clientState?.updateMsdpData(msdpData)
        } catch (e: Exception) {
            logger.error { "MSDP parse error: ${e.message}" }
            e.printStackTrace()
        }
    }

    private fun parseGMCP(data: ByteArray) {
        try {
            val gmcpMessage = gmcpParser.parse(data)
            if (gmcpMessage != null) {
                clientState?.updateGmcpData(gmcpMessage)
            } else {
                logger.error { "Failed to parse GMCP message" }
            }
        } catch (e: Exception) {
            logger.error { "Error parsing GMCP: ${e.message}" }
            e.printStackTrace()
        }
    }

    /**
     * Отправляет MSDP команду (асинхронно через корутину)
     * command: "LIST", "REPORT", "UNREPORT", "SEND", "RESET"
     * variable: имя переменной или тип списка
     */
    fun sendMsdpCommand(command: String, variable: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // Формат: IAC SB MSDP MSDP_VAR command MSDP_VAL variable IAC SE
            val commandBytes = command.toByteArray(Charsets.UTF_8)
            val variableBytes = variable.toByteArray(Charsets.UTF_8)

            val message = byteArrayOf(IAC, SB, MSDP, MsdpParser.MSDP_VAR) +
                commandBytes +
                byteArrayOf(MsdpParser.MSDP_VAL) +
                variableBytes +
                byteArrayOf(IAC, SE)

            sendTelnetCommand(message)
        }
    }

    companion object {
        // Telnet команды
        const val IAC: Byte = 255.toByte()    // Interpret As Command
        const val DONT: Byte = 254.toByte()
        const val DO: Byte = 253.toByte()
        const val WONT: Byte = 252.toByte()
        const val WILL: Byte = 251.toByte()
        const val SB: Byte = 250.toByte()     // Subnegotiation Begin
        const val SE: Byte = 240.toByte()     // Subnegotiation End

        // Telnet опции
        const val TERMINAL_TYPE: Byte = 24
        const val NAWS: Byte = 31            // Negotiate About Window Size
        const val MSDP: Byte = 69            // MUD Server Data Protocol
        const val GMCP: Byte = 201.toByte()  // Generic MUD Communication Protocol
    }
}
