package com.bylins.client.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class CommandHistoryTest {

    private fun tempFile(): Path {
        val dir = Files.createTempDirectory("bylins-history")
        dir.toFile().deleteOnExit()
        return dir.resolve("history.txt")
    }

    @Test
    fun `история переживает перезапуск`() {
        val file = tempFile()
        CommandHistory(file = file).apply {
            add("смотреть")
            add("север")
        }
        // Запись идёт в своём потоке -- ждём, пока файл появится
        waitForLines(file, 2)

        val restored = CommandHistory(file = file)
        assertEquals(listOf("смотреть", "север"), restored.all())
    }

    @Test
    fun `подстановка отдаёт свежие первыми и без повторов`() {
        val history = CommandHistory(file = tempFile())
        history.add("север")
        history.add("смотреть стол")
        history.add("север")
        history.add("смотреть в сундук")

        assertEquals(listOf("смотреть в сундук", "смотреть стол"), history.matches("смотреть"))
        // "север" встречался дважды, в подстановке должен быть один раз
        assertEquals(listOf("север"), history.matches("сев"))
        // Регистр не важен
        assertEquals(listOf("север"), history.matches("СЕВ"))
    }

    @Test
    fun `пустой префикс даёт всю историю, свежее первым`() {
        val history = CommandHistory(file = tempFile())
        history.add("один")
        history.add("два")
        assertEquals(listOf("два", "один"), history.matches(""))
    }

    @Test
    fun `история не растёт дальше предела`() {
        val file = tempFile()
        val history = CommandHistory(maxSize = 3, file = file)
        for (i in 1..5) history.add("команда $i")

        assertEquals(listOf("команда 3", "команда 4", "команда 5"), history.all())
        waitForLines(file, 3)
        assertEquals(listOf("команда 3", "команда 4", "команда 5"), Files.readAllLines(file))
    }

    @Test
    fun `уменьшение предела обрезает историю и файл`() {
        val file = tempFile()
        val history = CommandHistory(maxSize = 5, file = file)
        for (i in 1..5) history.add("команда $i")
        waitForLines(file, 5)

        history.maxSize = 2

        assertEquals(listOf("команда 4", "команда 5"), history.all())
        waitForLines(file, 2)
        assertEquals(listOf("команда 4", "команда 5"), Files.readAllLines(file))
    }

    @Test
    fun `пустые строки в историю не идут`() {
        val history = CommandHistory(file = tempFile())
        history.add("   ")
        history.add("")
        assertEquals(0, history.size)
    }

    @Test
    fun `приглашение ввести пароль распознаётся`() {
        assertTrue(CommandHistory.looksLikePasswordPrompt("Введите пароль для Дедал : "))
        assertTrue(CommandHistory.looksLikePasswordPrompt("Имя и пароль через пробел : "))
        assertTrue(CommandHistory.looksLikePasswordPrompt("\r\nВведите СТАРЫЙ пароль : "))
        assertFalse(CommandHistory.looksLikePasswordPrompt("Вы стоите посреди дороги.\r\n"))
        // Смотрим только хвост: слово в середине давнего вывода не считается
        assertFalse(
            CommandHistory.looksLikePasswordPrompt(
                "Введите пароль : " + "ровная дорога уходит на север. ".repeat(20)
            )
        )
    }

    private fun waitForLines(file: Path, expected: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file) && Files.readAllLines(file).size == expected) return
            Thread.sleep(20)
        }
        throw AssertionError("файл истории так и не получил $expected строк")
    }
}
