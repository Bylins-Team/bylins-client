package com.bylins.client.hotkeys

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import com.bylins.client.OperatingSystem
import mu.KotlinLogging
import java.lang.reflect.Field

private val logger = KotlinLogging.logger("PhysicalKey")

/**
 * Приводит нажатие к физической клавише, не зависящей от раскладки.
 *
 * Compose отдаёт код AWT, а тот вычисляется по символу текущей раскладки: одна
 * и та же клавиша приходит как Slash(47) в английской и как Period(46) в
 * русской. Хоткей, назначенный в одной раскладке, в другой просто не срабатывал.
 *
 * В самом событии AWT есть `rawCode` — код клавиши от Windows (VK_OEM_2 = 191
 * для «/?»), одинаковый в любой раскладке. Его и берём. Поле приватное,
 * поэтому нужен `--add-opens java.desktop/java.awt.event=ALL-UNNAMED`; если
 * доступа нет (запуск без флага), откатываемся на прежнее поведение — хоткеи
 * продолжат работать в раскладке, где их назначили.
 *
 * Только на Windows: `rawCode` хранит код той системы, в которой событие
 * пришло, а на macOS и X11 нумерация своя. Имена клавиш и сравнение с
 * константами Compose построены на кодах Windows — «1» там 49, и на этом
 * держатся контекстные команды Alt+1..0. На маке та же клавиша приходит как
 * 18, и совпадать перестало бы всё сразу: и имена в конфиге, и хоткеи.
 *
 * Без `rawCode` буквы приводятся по раскладке ЙЦУКЕН: на macOS и X11 AWT
 * вычисляет код по символу, и Ctrl+C в русской раскладке приходит не как C,
 * а как «с» или вовсе без кода — копирование не срабатывало (#19). Русская
 * буква переводится в латинскую с той же клавиши.
 */
object PhysicalKey {

    private val rawCodeField: Field? =
        if (OperatingSystem.current != OperatingSystem.Windows) {
            logger.info { "Не Windows: клавиша определяется штатным кодом Compose и раскладкой ЙЦУКЕН" }
            null
        } else runCatching {
            java.awt.event.KeyEvent::class.java.getDeclaredField("rawCode").apply { isAccessible = true }
        }.onFailure {
            logger.warn { "Физический код клавиши недоступен (${it.javaClass.simpleName}): хоткеи будут зависеть от раскладки" }
        }.getOrNull()

    /** Доступен ли раскладко-независимый код. Для диагностики в UI. */
    val available: Boolean get() = rawCodeField != null

    // Клавиши ЙЦУКЕН → те же клавиши в QWERTY. Только буквы: знаки препинания
    // в русской раскладке лежат на других клавишах, и общего ответа для них нет
    private val cyrillicToLatin: Map<Char, Key> = mapOf(
        'й' to Key.Q, 'ц' to Key.W, 'у' to Key.E, 'к' to Key.R, 'е' to Key.T, 'н' to Key.Y,
        'г' to Key.U, 'ш' to Key.I, 'щ' to Key.O, 'з' to Key.P,
        'ф' to Key.A, 'ы' to Key.S, 'в' to Key.D, 'а' to Key.F, 'п' to Key.G, 'р' to Key.H,
        'о' to Key.J, 'л' to Key.K, 'д' to Key.L,
        'я' to Key.Z, 'ч' to Key.X, 'с' to Key.C, 'м' to Key.V, 'и' to Key.B, 'т' to Key.N, 'ь' to Key.M
    )

    /** Латинская клавиша, на которой в раскладке ЙЦУКЕН лежит буква [codePoint]; null для прочих символов. */
    fun fromCyrillic(codePoint: Int): Key? {
        if (codePoint < 0 || codePoint > 0xFFFF) return null
        return cyrillicToLatin[codePoint.toChar().lowercaseChar()]
    }

    /**
     * Клавиша, пригодная и для сохранения, и для сравнения.
     *
     * Возвращает Key с кодом Windows и тем же расположением (NumPad остаётся
     * отличим от основной клавиатуры).
     */
    fun of(event: KeyEvent): Key {
        val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return byLayout(event)
        val field = rawCodeField ?: return byLayout(event)
        val raw = runCatching { field.getLong(awt) }.getOrNull() ?: return byLayout(event)
        if (raw <= 0) return byLayout(event)

        // Расположение берём из самого события, а не пересобираем: Compose
        // кодирует его по-своему, и своя нумерация ломала сравнение с его же
        // константами — Key.One переставала совпадать, а на ней держатся
        // контекстные команды Alt+1..0
        val location = event.key.keyCode and 0xFFFFFFFFL
        return Key((raw shl 32) or location)
    }

    /** Без физического кода: русская буква — по своей клавише в ЙЦУКЕН, остальное — как есть. */
    private fun byLayout(event: KeyEvent): Key = fromCyrillic(event.utf16CodePoint) ?: event.key
}
