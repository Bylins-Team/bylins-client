package com.bylins.client.perf

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class PacketTraceTest {

    private var previousHome: String? = null
    private lateinit var home: java.nio.file.Path

    private fun withTempHome(block: () -> Unit) {
        previousHome = System.getProperty("user.home")
        home = Files.createTempDirectory("bylins-trace")
        System.setProperty("user.home", home.toString())
        try {
            block()
        } finally {
            previousHome?.let { System.setProperty("user.home", it) }
            PacketTrace.stop()
            home.toFile().deleteRecursively()
        }
    }

    private fun traceFile(): File =
        home.resolve(".bylins-client").resolve("logs").toFile()
            .listFiles { f -> f.name.startsWith("packets_") }!!
            .first()

    @AfterTest
    fun tearDown() = PacketTrace.stop()

    @Test
    fun `пока не включили -- ничего не пишется`() = withTempHome {
        assertFalse(PacketTrace.isOn)
        PacketTrace.record(100, "текст")
        val logs = home.resolve(".bylins-client").resolve("logs").toFile()
        assertFalse(logs.exists() && logs.listFiles()?.isNotEmpty() == true, "файл появился без включения")
    }

    @Test
    fun `в записи есть размер, промежуток и начало текста`() = withTempHome {
        assertTrue(PacketTrace.start() != null)
        PacketTrace.record(512, "\u001B[32mБазарная площадь\r\n[ Exits: n e ]\r\n")
        PacketTrace.record(64, "630H 179M Вых:СВ> ")

        val lines = traceFile().readLines().drop(1)   // первая строка -- заголовок

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("512 байт"), lines[0])
        // цвета убраны, переводы строк не рвут запись
        assertTrue(lines[0].contains("Базарная площадь⏎[ Exits: n e ]⏎"), lines[0])
        assertFalse(lines[0].contains("\u001B"), "в записи остались цветовые коды")
        assertTrue(lines[1].contains("64 байт"), lines[1])
        // у второго пакета промежуток уже не нулевой по смыслу -- колонка на месте
        assertTrue(lines[1].contains("мс"), lines[1])
    }

    @Test
    fun `выключение прекращает запись`() = withTempHome {
        PacketTrace.start()
        PacketTrace.record(10, "раз")
        PacketTrace.stop()
        PacketTrace.record(10, "два")

        val lines = traceFile().readLines().drop(1)

        assertEquals(1, lines.size, "после выключения запись продолжилась")
    }
}
