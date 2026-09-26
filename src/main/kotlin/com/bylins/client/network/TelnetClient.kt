package com.bylins.client.network

import mu.KotlinLogging
import com.bylins.client.ClientState
import com.bylins.client.perf.Perf
import com.bylins.client.ui.scroll.LineBuffer
import com.bylins.client.ui.scroll.LineSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

private val logger = KotlinLogging.logger("TelnetClient")
private const val MIN_READ_BUFFER = 16 * 1024
private const val MAX_READ_BUFFER = 256 * 1024

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

    // Буфер вывода по строкам: добавление трогает только хвост, а не копирует
    // всю историю. Снимок — с абсолютной нумерацией строк для автоскролла и
    // выделения; номер первой строки растёт при вытеснении сверху
    private val buffer = LineBuffer(com.bylins.client.config.DEFAULT_OUTPUT_BUFFER_LINES)
    private val _snapshot = MutableStateFlow(LineSnapshot.EMPTY)
    val snapshot: StateFlow<LineSnapshot> = _snapshot

    // Сервер согласился принимать размер окна (ответил IAC DO NAWS)
    @Volatile
    private var nawsAccepted = false

    // Последний сообщённый размер: пересылаем только изменения
    @Volatile
    private var sentColumns = 0

    @Volatile
    private var sentRows = 0

    /**
     * Склейка обновлений вывода. Окно приходит из конфига (outputCoalesceMs).
     */
    private val publisher = SnapshotPublisher(
        scope = CoroutineScope(Dispatchers.IO),
        delayMs = com.bylins.client.config.DEFAULT_OUTPUT_COALESCE_MS.toLong()
    ) {
        synchronized(bufferLock) { _snapshot.value = buffer.snapshot() }
    }

    /**
     * Размер окна вывода в знаках и строках -- для NAWS.
     *
     * Зовётся панелью вывода при изменении окна или шрифта. Пока сервер не согласился
     * принимать размер, значение просто запоминается и уйдёт после согласия.
     */
    fun setWindowSize(columns: Int, rows: Int) {
        if (columns !in Naws.MIN_COLUMNS..Naws.MAX_COLUMNS || rows !in Naws.MIN_ROWS..Naws.MAX_ROWS) {
            return
        }
        if (columns == sentColumns && rows == sentRows) {
            return
        }
        sentColumns = columns
        sentRows = rows
        sendWindowSize(force = false)
    }

    private fun sendWindowSize(force: Boolean) {
        if (!nawsAccepted || !_isConnected.value) return
        if (sentColumns == 0 || sentRows == 0) return
        sendTelnetCommand(Naws.subnegotiation(sentColumns, sentRows))
        if (force) {
            logger.info { "NAWS: сообщил размер окна $sentColumns x $sentRows" }
        }
    }

    fun setOutputCoalesceMs(ms: Int) {
        publisher.delayMs = ms.toLong()
    }

    // Буфер правят три разных потока: читающий сокет, UI (эхо команды игрока)
    // и потоки плагинов (команды ИИ через ai-control). Без замка одна правка
    // теряет другую: пропадает то эхо команды, то кусок вывода сервера.
    private val bufferLock = Any()

    private val telnetParser = TelnetParser(encoding)
    private val msdpParser = MsdpParser(encoding)
    private val gmcpParser = GmcpParser()

    /**
     * Устанавливает кодировку для telnet соединения
     */
    fun setEncoding(encoding: String) {
        telnetParser.setEncoding(encoding)
        // MSDP приходит в той же кодировке, что и основной поток: названия комнат сервер
        // перекодирует на своей стороне под кодировку сессии.
        msdpParser.setEncoding(encoding)
        logger.info { "Encoding changed to: $encoding" }
    }

    /** Сколько строк вывода держать в памяти. Лишние вытесняются сверху при добавлении. */
    fun setOutputBufferLines(lines: Int) {
        val value = lines.coerceIn(com.bylins.client.config.MIN_OUTPUT_BUFFER_LINES, com.bylins.client.config.MAX_OUTPUT_BUFFER_LINES)
        buffer.maxLines = value
        logger.info { "Буфер вывода: $value строк" }
    }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            // Убеждаемся что предыдущее соединение закрыто
            if (_isConnected.value) {
                disconnect()
            }

            // Видно, куда пошли и чем кончилось: раньше при неудаче в окне не появлялось
            // ничего, ошибка уходила только во всплывающее сообщение.
            appendToBuffer("\u001B[1;33m[Подключение к $host:$port...]\u001B[0m\n")

            // Переговоры начинаются заново: согласие прошлого соединения не в счёт,
            // а размер посылаем сразу после согласия
            nawsAccepted = false
            socket = Socket(host, port)
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            _isConnected.value = true

            appendToBuffer("\u001B[1;32m[Соединение с $host:$port установлено]\u001B[0m\n\n")

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
                appendToBuffer("\u001B[1;31m[Соединение разорвано]\u001B[0m\n")
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
        // Своё эхо и системные сообщения показываем сразу: склейка нужна для текста
        // сервера, который приходит кусками, а тут ждать продолжения нечего
        publisher.flush()
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
        publisher.flush()
    }

    /**
     * Добавляет текст в output БЕЗ обработки триггерами
     * Используется для echo из скриптов чтобы избежать рекурсии
     * Вставляет перед незавершённой строкой (промптом/картой)
     */
    fun addToOutputRaw(text: String) {
        insertBeforePrompt(text)
        onLocalOutput?.invoke(text)
    }

    /**
     * Добавляет локальный вывод (результат #команд) с сохранением промпта
     * Если последняя строка в буфере не завершена (это промпт),
     * то выводит сообщение ПЕРЕД промптом
     */
    fun addLocalOutput(text: String) {
        insertBeforePrompt(text)
        onLocalOutput?.invoke(text)
    }

    private fun insertBeforePrompt(text: String) {
        Perf.measure(Perf.Stage.BUFFER_APPEND, text.length.toLong()) {
            synchronized(bufferLock) {
                buffer.insertBeforeIncomplete(text)
            }
        }
        // Ответ на свою же команду ждать незачем -- показываем сразу
        publisher.flush()
    }

    /**
     * Добавляет текст в буфер с ограничением размера
     */
    private fun appendToBuffer(text: String) {
        // Замер снаружи замка: ожидание чужой записи — такая же задержка
        // для игрока, как и сама работа
        Perf.measure(Perf.Stage.BUFFER_APPEND, text.length.toLong()) {
            synchronized(bufferLock) {
                buffer.append(text)
            }
        }
        // Снимок отдаётся со склейкой: сеть режет ответ на куски по размеру сегмента,
        // и рисовать каждый кусок значит показывать недорисованное
        publisher.request()
    }

    private fun startReading() {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Размер буфера берём у самого сокета: столько ядро и отдаёт за одно
                // чтение. Прежние 4096 были меньше любого приёмного буфера, поэтому ответ
                // сервера резался на части не сетью, а нами -- и экран показывал огрызок,
                // а следующим кадром дёргался. Границы на случай странных значений.
                val socketBuffer = runCatching { socket?.receiveBufferSize ?: 0 }.getOrDefault(0)
                val size = socketBuffer.coerceIn(MIN_READ_BUFFER, MAX_READ_BUFFER)
                Perf.buffers(ours = size, socket = socketBuffer)
                val buffer = ByteArray(size)
                while (isActive && _isConnected.value) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) Perf.readDone(bytesRead)
                    if (bytesRead == -1) {
                        // Соединение закрыто сервером
                        disconnect()
                        break
                    }

                    val data = buffer.copyOf(bytesRead)
                    Perf.inputArrived()
                    val (text, telnetCommands) = Perf.measure(Perf.Stage.NET_PARSE, bytesRead.toLong()) {
                        telnetParser.parse(data)
                    }

                    // Telnet-команды разбираем ПЕРЕД текстом: в одном пакете
                    // сервер шлёт и MSDP с новой комнатой, и её описание.
                    // Разбери мы текст первым — триггеры и контекстные правила
                    // с областью действия проверялись бы против ещё старой
                    // комнаты и молча не срабатывали при входе, оживая только
                    // после «смотреть», когда позиция уже обновилась.
                    telnetCommands.forEach { handleTelnetCommand(it) }
                    // Сервер сказал "ответ закончен" -- ждать больше нечего. Флаг
                    // выставляем до разбора текста: сам кадр отдаём уже после того, как
                    // текст этого пакета ляжет в буфер
                    val answerEnded = telnetCommands.any { it.type == TelnetCommandType.END_OF_ANSWER }
                    // Раз отметка приходит -- дальше ждём только её, а не время:
                    // окно тишины опережало хвост ответа и рисовало промежуточный кадр
                    if (answerEnded) publisher.endMarkSeen = true

                    if (text.isNotEmpty()) {
                        com.bylins.client.perf.PacketTrace.record(bytesRead, text)
                        // Обрабатываем текст триггерами и получаем модифицированную версию с colorize
                        val modifiedText = Perf.measure(Perf.Stage.TEXT_PROCESS, text.length.toLong()) {
                            clientState?.processIncomingText(text) ?: text
                        }
                        appendToBuffer(modifiedText)
                    }
                    if (answerEnded) {
                        publisher.flush()
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

        // IAC WILL NAWS: размер окна сообщает клиент, а не сервер -- раньше мы просили
        // сервер сообщать нам (IAC DO NAWS), и сторона, которая должна говорить, молчала
        sendTelnetCommand(byteArrayOf(IAC, WILL, NAWS))

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
                    NAWS -> {
                        // Согласился принимать размер окна -- сообщаем текущий
                        nawsAccepted = true
                        sendWindowSize(force = true)
                    }
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

        // Конец ответа: сервер ставит их после строки приглашения. Для нас это точный
        // признак "больше ничего не будет" -- лучше любого ожидания по времени
        const val GA: Byte = 249.toByte()     // Go Ahead
        const val EOR: Byte = 239.toByte()    // End Of Record

        // Telnet опции
        const val TERMINAL_TYPE: Byte = 24
        const val NAWS: Byte = 31            // Negotiate About Window Size
        const val MSDP: Byte = 69            // MUD Server Data Protocol
        const val GMCP: Byte = 201.toByte()  // Generic MUD Communication Protocol
    }
}
