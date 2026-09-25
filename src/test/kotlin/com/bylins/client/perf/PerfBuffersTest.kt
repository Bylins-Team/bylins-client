package com.bylins.client.perf

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Размер буфера чтения и чтения «под завязку» в отчёте.
 *
 * Прежние 4096 байт были меньше любого приёмного буфера сокета, и ответ сервера резался
 * на части не сетью, а нами: экран показывал огрызок и дёргался следующим кадром. Чтобы
 * это было видно, а не выводилось из рассуждений, обе цифры печатаются в #perf.
 */
class PerfBuffersTest {

    @BeforeTest
    fun setUp() = Perf.reset()

    @AfterTest
    fun tearDown() = Perf.reset()

    private fun buffersLine(): String = Perf.report().lines().first { it.startsWith("Буфер чтения") }

    @Test
    fun `пока размер неизвестен -- сказать нечего`() {
        // Ноль значит "ещё не читали": счётчики живут в объекте, и до первого чтения
        // (или на неудачном сокете) размера нет
        Perf.buffers(ours = 0, socket = 0)

        assertTrue(buffersLine().contains("пока не читали"), buffersLine())
    }

    @Test
    fun `размеры и число чтений под завязку попадают в отчёт`() {
        Perf.buffers(ours = 65536, socket = 87380)
        Perf.readDone(1024)
        Perf.readDone(65536)
        Perf.readDone(65536)

        val line = buffersLine()

        assertTrue(line.contains("65536 байт"), line)
        assertTrue(line.contains("87380"), line)
        assertTrue(line.contains("чтений под завязку: 2"), line)
        assertTrue(line.contains("режется на части"), line)
    }

    @Test
    fun `когда в буфер всё влезает, про резку не пишем`() {
        Perf.buffers(ours = 65536, socket = 65536)
        Perf.readDone(4096)
        Perf.readDone(12000)

        val line = buffersLine()

        assertTrue(line.contains("чтений под завязку: 0"), line)
        assertFalse(line.contains("режется на части"), line)
    }

    @Test
    fun `обнуление стирает счёт чтений`() {
        Perf.buffers(ours = 16384, socket = 16384)
        Perf.readDone(16384)
        Perf.reset()

        assertTrue(buffersLine().contains("чтений под завязку: 0"), buffersLine())
    }
}
