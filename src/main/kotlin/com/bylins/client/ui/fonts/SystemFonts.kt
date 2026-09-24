package com.bylins.client.ui.fonts

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface as SkiaTypeface

/**
 * Шрифты для окна вывода.
 *
 * Клиент всегда умел четыре логических семейства (их имена и лежат в конфиге), а какой это
 * шрифт на самом деле, решала система: на винде Monospace -- обычно Courier New, на линуксе
 * DejaVu Sans Mono. Если в настройках разрешены системные шрифты, в конфиг вместо слова
 * попадает имя шрифта ("Lucida Console"), и оно разбирается здесь.
 *
 * Имя, которого на машине нет, молча превращается в Monospace: конфиг один, а набор шрифтов
 * на винде и на линуксе разный, и клиент не должен из-за этого рисовать чем попало.
 */
object SystemFonts {

    /** Ключ в конфиге -> семейство. Порядок задаёт порядок в выпадающем списке. */
    val LOGICAL: Map<String, FontFamily> = linkedMapOf(
        "MONOSPACE" to FontFamily.Monospace,
        "SERIF" to FontFamily.Serif,
        "SANS_SERIF" to FontFamily.SansSerif,
        "CURSIVE" to FontFamily.Cursive
    )

    /** Ключ в конфиге -> подпись в настройках. */
    val LOGICAL_LABELS: Map<String, String> = linkedMapOf(
        "MONOSPACE" to "Monospace",
        "SERIF" to "Serif",
        "SANS_SERIF" to "Sans Serif",
        "CURSIVE" to "Cursive"
    )

    private val resolved = ConcurrentHashMap<String, FontFamily>()

    @Volatile
    private var monospacedCache: List<String>? = null

    @Volatile
    private var installedCache: Set<String>? = null

    fun isLogical(name: String): Boolean = LOGICAL.containsKey(name)

    fun label(name: String): String = LOGICAL_LABELS[name] ?: name

    /**
     * Моноширинные шрифты, установленные в системе.
     *
     * Немоноширинные не предлагаем: в выводе мада колонки, рамки и карта поедут. Проверка
     * простая -- у моноширинного ширина 'i' и 'W' совпадает. Список считается один раз:
     * обход всех шрифтов системы занимает заметные доли секунды.
     */
    fun monospacedFamilies(): List<String> {
        monospacedCache?.let { return it }
        val families = try {
            val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
            val names = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
            val monospaced = names.filter { name ->
                val metrics = graphics.getFontMetrics(Font(name, Font.PLAIN, 12))
                metrics.charWidth('i') == metrics.charWidth('W') && metrics.charWidth('i') > 0
            }.sorted()
            graphics.dispose()
            monospaced
        } catch (e: Throwable) {
            println("SystemFonts: не получить список шрифтов (${e.message})")
            emptyList()
        }
        monospacedCache = families
        return families
    }

    fun resolve(name: String): FontFamily {
        LOGICAL[name]?.let { return it }
        return resolved.getOrPut(name) { systemFamily(name) ?: FontFamily.Monospace }
    }

    private fun installed(): Set<String> {
        installedCache?.let { return it }
        val names = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        } catch (e: Throwable) {
            emptySet()
        }
        installedCache = names
        return names
    }

    private fun systemFamily(name: String): FontFamily? {
        // Skia на незнакомое имя отдаёт шрифт по умолчанию, а не null, поэтому сначала
        // спрашиваем систему, есть ли такой вообще.
        if (name.isBlank() || !installed().contains(name)) return null
        return try {
            val typeface = SkiaTypeface.makeFromName(name, FontStyle.NORMAL) ?: return null
            FontFamily(Typeface(typeface))
        } catch (e: Throwable) {
            println("SystemFonts: шрифт '$name' не открылся (${e.message})")
            null
        }
    }
}
