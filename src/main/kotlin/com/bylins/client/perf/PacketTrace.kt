package com.bylins.client.perf

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Запись прихода пакетов: когда пришёл, сколько в нём было, чем начинался.
 *
 * Нужна, чтобы отличить «клиент рисует медленно» от «сервер присылает ответ по частям».
 * Обычный лог вывода на этот вопрос не отвечает: он пишет текст без отметок времени, и по
 * нему не видно, разделены куски двумя миллисекундами или сотней. По умолчанию выключено:
 * включается `#perf trace on`, пишет рядом с логами.
 */
object PacketTrace {

    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val ansi = "\u001B\\[[;\\d]*m".toRegex()

    private var file: File? = null
    private var previousNanos = 0L

    val isOn: Boolean get() = file != null

    /** Включает запись; возвращает путь к файлу или null, если не удалось. */
    @Synchronized
    fun start(): String? {
        stop()
        return try {
            val dir = Paths.get(System.getProperty("user.home"), ".bylins-client", "logs")
            Files.createDirectories(dir)
            val name = "packets_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.log"
            val target = dir.resolve(name).toFile()
            target.writeText(
                "Приход пакетов. Колонки: время, прошло с прошлого пакета, размер, начало текста\n"
            )
            previousNanos = 0L
            file = target
            target.absolutePath
        } catch (e: Exception) {
            println("PacketTrace: не начать запись (${e.message})")
            null
        }
    }

    @Synchronized
    fun stop() {
        file = null
        previousNanos = 0L
    }

    /**
     * Запись кадра панели вывода: что она насчитала и куда поставила вид.
     *
     * Пакетов мало, а кадров может быть несколько на пакет -- по записи видно, рисуется ли
     * промежуточное состояние (например, текст встал не на место и следующим кадром прыгнул).
     *
     * @param lines сколько строк в буфере
     * @param contentHeight полная высота содержимого
     * @param maxScroll предел прокрутки
     * @param scroll где стоит вид
     * @param split раздвоен ли вывод
     */
    @Synchronized
    fun frame(lines: Int, contentHeight: Float, maxScroll: Float, scroll: Float, split: Boolean) {
        val target = file ?: return
        try {
            target.appendText(
                "%s  кадр: строк %d, высота %.0f, предел %.0f, вид %.0f%s\n".format(
                    LocalDateTime.now().format(stamp), lines, contentHeight, maxScroll, scroll,
                    if (split) ", раздвоение" else ""
                )
            )
        } catch (e: Exception) {
            println("PacketTrace: не записать кадр (${e.message})")
            stop()
        }
    }

    /**
     * @param bytes сколько байт прочитано из сети
     * @param text расшифрованный текст пакета (для начала строки)
     */
    @Synchronized
    fun record(bytes: Int, text: String) {
        val target = file ?: return
        val now = System.nanoTime()
        val sincePrevious = if (previousNanos == 0L) 0.0 else (now - previousNanos) / 1_000_000.0
        previousNanos = now
        // Переводы строки и цвета в одну строку записи: иначе запись не читается глазами
        val preview = ansi.replace(text, "")
            .replace("\r", "")
            .replace("\n", "⏎")
            .take(70)
        try {
            target.appendText(
                "%s  +%7.1f мс  %5d байт  %s\n".format(
                    LocalDateTime.now().format(stamp), sincePrevious, bytes, preview
                )
            )
        } catch (e: Exception) {
            println("PacketTrace: не записать (${e.message})")
            stop()
        }
    }
}
