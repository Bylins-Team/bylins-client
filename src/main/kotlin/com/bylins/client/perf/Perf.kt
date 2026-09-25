package com.bylins.client.perf

import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

private val logger = KotlinLogging.logger("Perf")

/**
 * Замеры этапов обработки вывода.
 *
 * Включать нечего: пара nanoTime стоит наносекунды, а телеметрия, которую надо
 * догадаться включить, не ловит ничего — пока спросишь игрока, он уже ушёл.
 * Поэтому этапы меряются всегда, а в лог само попадает только то, что вылезло
 * за [slowThresholdMs] — с обстановкой, по которой видно, отчего стало дорого.
 *
 * Считаем в гистограмме по степеням двойки: она даёт процентиль без хранения
 * отдельных замеров и без единой аллокации на горячем пути.
 */
object Perf {

    /**
     * Дольше этого — строка в лог. Кадр длиннее 50 мс игрок уже замечает.
     *
     * Свойством запуска `-Dbylins.perf.slow=<мс>` порог задаётся без
     * команды в клиенте — для прогонов, где некому её набрать.
     */
    var slowThresholdMs: Long = System.getProperty("bylins.perf.slow")?.toLongOrNull()?.takeIf { it > 0 } ?: 50

    /** Этапы обработки. Порядок — как в конвейере, им же печатается отчёт. */
    enum class Stage(val title: String, val unit: String = "") {
        NET_PARSE("telnet: разбор пакета", "байт"),
        TEXT_PROCESS("текст: события и триггеры", "символов"),
        TABS_ROUTE("вкладки: раскладка", "символов"),
        BUFFER_APPEND("буфер: добавление", "символов"),
        UI_ANSI("вывод: разбор ANSI", "строк"),
        UI_MEASURE("вывод: разметка текста", "строк"),
        END_TO_END("от прихода байтов до кадра")
    }

    private const val BUCKETS = 32

    private class Counters {
        val count = AtomicLong()
        val totalNanos = AtomicLong()
        val maxNanos = AtomicLong()
        /**
         * Объём работы: строк, байт -- что этап считает своей единицей. Нужен, чтобы
         * отличить «этап дорог сам по себе» от «раз в сколько-то пакетов прилетает пачка».
         * Замеры без объёма (size < 0) в счёт не идут -- иначе среднее врало бы.
         */
        val volumeCount = AtomicLong()
        val volumeTotal = AtomicLong()
        val volumeMax = AtomicLong()
        /** Корзина i — замеры от 2^i до 2^(i+1) микросекунд. */
        val histogram = AtomicLongArray(BUCKETS)

        fun add(nanos: Long, size: Long) {
            count.incrementAndGet()
            totalNanos.addAndGet(nanos)
            maxNanos.accumulateAndGet(nanos, ::maxOf)
            if (size >= 0) {
                volumeCount.incrementAndGet()
                volumeTotal.addAndGet(size)
                volumeMax.accumulateAndGet(size, ::maxOf)
            }
            val micros = nanos / 1_000
            val bucket = if (micros <= 0) 0 else (63 - java.lang.Long.numberOfLeadingZeros(micros)).coerceIn(0, BUCKETS - 1)
            histogram.incrementAndGet(bucket)
        }

        fun reset() {
            count.set(0)
            totalNanos.set(0)
            maxNanos.set(0)
            volumeCount.set(0)
            volumeTotal.set(0)
            volumeMax.set(0)
            for (i in 0 until BUCKETS) histogram.set(i, 0)
        }

        /** Верхняя граница корзины, в которую попадает заданная доля замеров. */
        fun percentileMicros(fraction: Double): Long {
            val total = count.get()
            if (total == 0L) return 0
            val target = (total * fraction).toLong().coerceAtLeast(1)
            var seen = 0L
            for (i in 0 until BUCKETS) {
                seen += histogram.get(i)
                if (seen >= target) return 1L shl (i + 1)
            }
            return maxNanos.get() / 1_000
        }
    }

    private val stages = Stage.values().associateWith { Counters() }

    // --- Буфер чтения: его размер и сколько раз чтение упёрлось в него целиком ---
    // Чтение «под завязку» значит, что в сокете было ещё, и ответ сервера порезан на части
    // не сетью, а нашим буфером: экран успевает показать огрызок.
    @Volatile
    private var readBufferSize = 0

    @Volatile
    private var socketBufferSize = 0

    private val fullReads = AtomicLong()

    /** Запоминает размеры: [ours] -- наш буфер чтения, [socket] -- приёмный буфер сокета. */
    fun buffers(ours: Int, socket: Int) {
        readBufferSize = ours
        socketBufferSize = socket
    }

    /** Отмечает чтение; [bytes] == размер буфера значит «в сокете было ещё». */
    fun readDone(bytes: Int) {
        if (readBufferSize > 0 && bytes >= readBufferSize) {
            fullReads.incrementAndGet()
        }
    }

    /**
     * Меряет блок.
     *
     * @param size размер обрабатываемого куска — попадает в строку о медленном
     *   этапе. Считать его дорого не надо: он нужен, только когда стало плохо
     */
    inline fun <T> measure(stage: Stage, size: Long = -1, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            record(stage, System.nanoTime() - started, size)
        }
    }

    /** Вынесено из measure: inline-функция не может трогать приватные поля. */
    fun record(stage: Stage, nanos: Long, size: Long = -1) {
        stages.getValue(stage).add(nanos, size)
        val ms = nanos / 1_000_000
        if (ms >= slowThresholdMs) {
            val where = if (size >= 0) ", объём $size" else ""
            logger.warn { "Медленно: ${stage.title} — $ms мс$where" }
        }
    }

    // --- Сквозное время ---
    //
    // Держим момент прихода ПЕРВОГО ещё не нарисованного куска: игрок ждёт
    // именно с него, а не с последнего. Ноль означает «всё нарисовано».
    private val pendingInputNanos = AtomicLong(0)

    /** Пришли данные, которые ещё не показаны. */
    fun inputArrived() {
        pendingInputNanos.compareAndSet(0, System.nanoTime())
    }

    /** Кадр с этими данными отрисован. */
    fun painted() {
        val started = pendingInputNanos.getAndSet(0)
        if (started != 0L) record(Stage.END_TO_END, System.nanoTime() - started)
    }

    fun reset() {
        stages.values.forEach { it.reset() }
        fullReads.set(0)
        pendingInputNanos.set(0)
    }

    /** Человекочитаемый отчёт: этапы плюс память и сборщик мусора. */
    fun report(): String = buildString {
        // Сборка в отчёте: его и присылают при разборе тормозов, а по цифрам не понять,
        // из какой сборки они взяты
        appendLine("Сборка: ${com.bylins.client.BuildInfo.full}")
        appendLine("Этап                              вызовов   сумма    среднее   p95    макс     объём: среднее / наибольшее")
        for (stage in Stage.values()) {
            val c = stages.getValue(stage)
            val count = c.count.get()
            if (count == 0L) continue
            val totalMs = c.totalNanos.get() / 1_000_000.0
            val avgMs = c.totalNanos.get() / 1_000_000.0 / count
            val p95Ms = c.percentileMicros(0.95) / 1_000.0
            val maxMs = c.maxNanos.get() / 1_000_000.0
            val volumeCount = c.volumeCount.get()
            val volume = if (volumeCount == 0L) "" else
                "  %7.1f / %-6d %s".format(
                    c.volumeTotal.get().toDouble() / volumeCount,
                    c.volumeMax.get(),
                    stage.unit
                )
            appendLine(
                "%-32s %7d %7.0f мс %6.1f %6.1f %7.1f%s".format(
                    stage.title, count, totalMs, avgMs, p95Ms, maxMs, volume
                )
            )
        }
        appendLine()
        appendLine(buffersLine())
        appendLine(memoryLine())
        appendLine(gcLine())
        append("Порог жалобы в лог: ${slowThresholdMs} мс")
    }

    private fun buffersLine(): String {
        if (readBufferSize == 0) return "Буфер чтения: пока не читали"
        val full = fullReads.get()
        val hint = if (full == 0L) "" else " -- ответ сервера режется на части"
        return "Буфер чтения: %d байт (приёмный буфер сокета %d), чтений под завязку: %d%s".format(
            readBufferSize, socketBufferSize, full, hint
        )
    }

    private fun memoryLine(): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val totalMb = runtime.totalMemory() / 1024 / 1024
        val maxMb = runtime.maxMemory() / 1024 / 1024
        return "Память: занято $usedMb МБ, выделено $totalMb МБ, потолок $maxMb МБ"
    }

    /**
     * Паузы сборщика надо видеть рядом с временами этапов: иначе непонятно,
     * тормозит наш код или сборка мусора, которой мы этот мусор и создали.
     */
    private fun gcLine(): String {
        val beans = runCatching { java.lang.management.ManagementFactory.getGarbageCollectorMXBeans() }
            .getOrNull() ?: return "Сборщик мусора: нет данных"
        return beans.joinToString("; ", prefix = "Сборщик мусора: ") { bean ->
            "${bean.name}: ${bean.collectionCount} раз, ${bean.collectionTime} мс"
        }
    }
}
