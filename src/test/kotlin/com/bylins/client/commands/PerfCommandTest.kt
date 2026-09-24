package com.bylins.client.commands

import com.bylins.client.audio.SoundManager
import com.bylins.client.contextcommands.ContextCommandManager
import com.bylins.client.mapper.Direction
import com.bylins.client.mapper.MapManager
import com.bylins.client.perf.Perf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `#perf` — то, что игрок присылает, когда «тормозит».
 *
 * Команда обязана гаситься локально: уйди она на сервер, в ответ придёт
 * «Чаво?», а отчёта игрок не увидит.
 */
class PerfCommandTest {

    private val sentToServer = mutableListOf<String>()
    private val localOutput = mutableListOf<String>()
    private val tempFiles = mutableListOf<File>()

    private val context = object : CommandContext {
        override fun addLocalOutput(text: String) { localOutput.add(text) }
        override fun sendRaw(command: String) { sentToServer.add(command) }
        override fun startWalk(targetRoomId: String) = false
        override fun walkDirections(directions: List<Direction>, label: String) = false
        override fun stopWalk() {}
        override fun getAllZones(): List<String> = emptyList()
        override fun getZoneStatistics(): Map<String, Int> = emptyMap()
        override fun detectAndAssignZones() {}
        override fun clearAllZones() {}
    }

    private fun processor(): CommandProcessor {
        val mapDb = File.createTempFile("bylins-perf-test", ".db").also { tempFiles.add(it) }
        return CommandProcessor(
            scope = CoroutineScope(Dispatchers.Unconfined),
            context = context,
            mapManager = MapManager(dbFileName = mapDb.absolutePath),
            soundManager = SoundManager(),
            contextCommandManager = ContextCommandManager(onCommand = {}, getCurrentRoom = { null }),
            getScriptManager = { null },
            getPluginManager = { null }
        )
    }

    @AfterTest
    fun cleanUp() {
        tempFiles.forEach { it.deleteRecursively() }
        tempFiles.clear()
        Perf.reset()
        Perf.slowThresholdMs = 50
    }

    @Test
    fun `печатает отчёт и не уходит на сервер`() {
        Perf.record(Perf.Stage.UI_MEASURE, 5_000_000)

        val handled = processor().processNavigationCommand("#perf")

        assertTrue(handled)
        assertTrue(localOutput.any { it.contains(Perf.Stage.UI_MEASURE.title) }, localOutput.toString())
        assertTrue(sentToServer.isEmpty(), "команда ушла на сервер: $sentToServer")
    }

    @Test
    fun `сбрасывает замеры`() {
        Perf.record(Perf.Stage.UI_ANSI, 1_000_000)

        processor().processNavigationCommand("#perf reset")

        assertFalse(Perf.report().contains(Perf.Stage.UI_ANSI.title), Perf.report())
    }

    @Test
    fun `меняет порог жалобы в лог`() {
        processor().processNavigationCommand("#perf slow 200")

        assertEquals(200, Perf.slowThresholdMs)
    }

    @Test
    fun `на мусор в аргументе отвечает подсказкой, а не молчанием`() {
        processor().processNavigationCommand("#perf slow ноль")

        assertEquals(50, Perf.slowThresholdMs, "порог изменён мусором")
        assertTrue(localOutput.any { it.contains("Использование") }, localOutput.toString())
    }

    @Test
    fun `команды с похожим началом не перехватываются`() {
        val handled = processor().processNavigationCommand("#perfect")

        assertFalse(handled, "чужая команда обработана как #perf")
    }
}
