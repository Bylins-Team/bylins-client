package com.bylins.client.perf

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Замеры этапов обработки вывода.
 *
 * Когда игрок говорит «тормозит», у нас нет ничего, кроме рассуждений о коде.
 * Эти счётчики — единственный способ не гадать, поэтому важно, чтобы они сами
 * были дёшевы, считали честно и работали при записи из нескольких потоков:
 * читающий сокет и поток отрисовки пишут в них одновременно.
 */
class PerfTest {

    @BeforeTest
    fun setUp() = Perf.reset()

    @AfterTest
    fun cleanUp() = Perf.reset()

    @Test
    fun `отчёт показывает только то, что мерили`() {
        Perf.record(Perf.Stage.UI_MEASURE, 5_000_000)

        val report = Perf.report()

        assertTrue(report.contains(Perf.Stage.UI_MEASURE.title), report)
        assertTrue(!report.contains(Perf.Stage.NET_PARSE.title), "в отчёт попал неизмеренный этап")
    }

    @Test
    fun `максимум и среднее считаются раздельно`() {
        Perf.record(Perf.Stage.UI_ANSI, 1_000_000)   // 1 мс
        Perf.record(Perf.Stage.UI_ANSI, 9_000_000)   // 9 мс

        val line = Perf.report().lines().first { it.startsWith(Perf.Stage.UI_ANSI.title) }

        // Среднее 5 мс, максимум 9 мс: по одному среднему выброс не виден
        assertTrue(line.contains("5.0"), line)
        assertTrue(line.contains("9.0"), line)
    }

    @Test
    fun `процентиль не съезжает на одиночном выбросе`() {
        repeat(99) { Perf.record(Perf.Stage.TABS_ROUTE, 1_000_000) }
        Perf.record(Perf.Stage.TABS_ROUTE, 2_000_000_000)

        val line = Perf.report().lines().first { it.startsWith(Perf.Stage.TABS_ROUTE.title) }

        // p95 остаётся в районе типичного замера, а секундный выброс виден в максимуме
        assertTrue(line.contains("2000."), "максимум потерялся: $line")
    }

    @Test
    fun `сброс обнуляет`() {
        Perf.record(Perf.Stage.BUFFER_APPEND, 1_000_000)
        Perf.reset()

        assertTrue(!Perf.report().contains(Perf.Stage.BUFFER_APPEND.title), Perf.report())
    }

    @Test
    fun `сквозное время считается от первого непоказанного куска`() {
        // Игрок ждёт с первого байта, а не с последнего: если мерить от
        // последнего, задержка занижается ровно на то, что он и заметил
        Perf.inputArrived()
        Thread.sleep(20)
        Perf.inputArrived()
        Perf.painted()

        val line = Perf.report().lines().first { it.startsWith(Perf.Stage.END_TO_END.title) }
        val max = line.trim().split(Regex("\\s+")).last().replace(',', '.').toDouble()
        assertTrue(max >= 20.0, "сквозное время меньше паузы между кусками: $line")
    }

    @Test
    fun `без новых данных кадр ничего не добавляет`() {
        Perf.painted()

        assertTrue(!Perf.report().contains(Perf.Stage.END_TO_END.title), "замер без данных")
    }

    @Test
    fun `считает при записи из нескольких потоков`() {
        // Пишут читающий сокет и поток отрисовки одновременно
        val threads = (1..8).map {
            Thread { repeat(500) { Perf.record(Perf.Stage.NET_PARSE, 1_000) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val line = Perf.report().lines().first { it.startsWith(Perf.Stage.NET_PARSE.title) }
        val count = line.trim().split(Regex("\\s+")).first { it.toLongOrNull() != null }.toLong()
        assertEquals(4000, count, "потерялись замеры: $line")
    }

    @Test
    fun `в отчёте есть память и сборщик мусора`() {
        // 1.1 ГБ из отчёта игрока надо видеть рядом с временами: иначе
        // непонятно, тормозит код или сборка мусора, которой мы его и создали
        Perf.record(Perf.Stage.UI_MEASURE, 1_000_000)

        val report = Perf.report()

        assertTrue(report.contains("Память:"), report)
        assertTrue(report.contains("Сборщик мусора:"), report)
    }
}
