package com.bylins.client.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * История введённых команд: стрелки вверх-вниз, подстановка по Tab и сохранение на диск.
 *
 * Файл `~/.bylins-client/history.txt`, одна команда в строке, новые внизу. Дописываем
 * сразу при вводе, а не на выходе: клиент может закрыться крестиком или упасть, и история
 * за сеанс не должна от этого зависеть.
 */
class CommandHistory(
    maxSize: Int = DEFAULT_MAX_SIZE,
    file: Path? = null
) {
    companion object {
        const val DEFAULT_MAX_SIZE = 500

        /**
         * Похоже ли, что сервер прямо сейчас спрашивает пароль.
         *
         * Пароль на былинах вводится в ту же строку ввода, а телнетного "не показывай ввод"
         * сервер не присылает — значит, отличить нечем, кроме самого приглашения. Команду,
         * набранную после такого приглашения, в историю не кладём: иначе пароль осел бы
         * открытым текстом в файле, который переживает сеанс.
         */
        fun looksLikePasswordPrompt(text: String): Boolean {
            val tail = text.takeLast(200).lowercase()
            return tail.contains("пароль")
        }

        private fun defaultFile(): Path =
            Paths.get(System.getProperty("user.home"), ".bylins-client", "history.txt")
    }

    private val file: Path = file ?: defaultFile()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

    private val items = mutableListOf<String>()

    /** Предел приходит из конфига (commandHistorySize) уже после создания объекта. */
    var maxSize: Int = maxSize
        set(value) {
            val limit = value.coerceAtLeast(1)
            if (field == limit) return
            field = limit
            if (trimToMaxSize()) rewrite()
        }

    init {
        load()
    }

    val size: Int get() = items.size

    /** Команды от старых к новым. */
    fun all(): List<String> = items.toList()

    fun add(command: String) {
        if (command.isBlank()) return
        items.add(command)
        val trimmed = trimToMaxSize()
        append(command, rewrite = trimmed)
    }

    /**
     * Подходящие под начало строки команды, свежие первыми и без повторов.
     *
     * Пустой префикс даёт всю историю: так Tab с пустой строкой работает как стрелка вверх.
     */
    fun matches(prefix: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item.startsWith(prefix, ignoreCase = true)) {
                seen.add(item)
            }
        }
        return seen.toList()
    }

    private fun trimToMaxSize(): Boolean {
        var trimmed = false
        while (items.size > maxSize) {
            items.removeAt(0)
            trimmed = true
        }
        return trimmed
    }

    private fun rewrite() {
        val snapshot = items.toList()
        ioScope.launch {
            try {
                Files.createDirectories(file.parent)
                Files.write(file, snapshot)
            } catch (e: Exception) {
                println("CommandHistory: не сохранить историю (${e.message})")
            }
        }
    }

    private fun load() {
        try {
            if (!Files.exists(file)) return
            val lines = Files.readAllLines(file).filter { it.isNotBlank() }
            items.addAll(if (lines.size > maxSize) lines.takeLast(maxSize) else lines)
        } catch (e: Exception) {
            println("CommandHistory: не прочитать историю (${e.message})")
        }
    }

    private fun append(command: String, rewrite: Boolean) {
        val snapshot = if (rewrite) items.toList() else null
        ioScope.launch {
            try {
                Files.createDirectories(file.parent)
                if (snapshot != null) {
                    // История переполнилась -- переписываем файл целиком, чтобы он не рос вечно.
                    Files.write(file, snapshot)
                } else {
                    Files.write(
                        file,
                        listOf(command),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                    )
                }
            } catch (e: Exception) {
                println("CommandHistory: не сохранить историю (${e.message})")
            }
        }
    }
}
