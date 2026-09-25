package com.bylins.client.perf

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * В отчёте нужен не только срок, но и объём работы: он отличает «этап дорог сам по
 * себе» от «раз в сколько-то пакетов прилетает пачка в сотню строк».
 */
class PerfVolumeTest {

    @BeforeTest
    fun setUp() = Perf.reset()

    @AfterTest
    fun tearDown() = Perf.reset()

    @Test
    fun `объём попадает в отчёт средним и наибольшим`() {
        Perf.record(Perf.Stage.UI_MEASURE, 1_000_000, size = 2)
        Perf.record(Perf.Stage.UI_MEASURE, 1_000_000, size = 120)

        val report = Perf.report()
        val line = squeeze(report.lines().first { it.startsWith(Perf.Stage.UI_MEASURE.title) })

        assertTrue(line.contains("61.0 / 120 строк"), "в строке нет объёма: $line")
    }

    /** Колонки выравниваются пробелами -- для проверки они не важны. */
    private fun squeeze(line: String) = line.replace(Regex("\\s+"), " ")

    @Test
    fun `замеры без объёма его не выдумывают`() {
        Perf.record(Perf.Stage.END_TO_END, 5_000_000)

        val line = Perf.report().lines().first { it.startsWith(Perf.Stage.END_TO_END.title) }

        assertFalse(line.contains("/"), "у этапа без объёма появился объём: $line")
    }

    @Test
    fun `обнуление стирает и объём`() {
        Perf.record(Perf.Stage.UI_ANSI, 1_000_000, size = 7)
        Perf.reset()
        Perf.record(Perf.Stage.UI_ANSI, 1_000_000, size = 3)

        val line = squeeze(Perf.report().lines().first { it.startsWith(Perf.Stage.UI_ANSI.title) })

        assertTrue(line.contains("3.0 / 3 строк"), "объём не обнулился: $line")
    }
}
