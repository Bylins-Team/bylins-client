package com.bylins.client.ui.components.output

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.bylins.client.ui.scroll.MeasuredWindow
import com.bylins.client.ui.scroll.SearchMatch
import com.bylins.client.ui.scroll.SelPoint

/**
 * Портируемое ядро панели вывода: маппинг между абсолютным seq строки и
 * пиксельной позицией скролла и отрисовка.
 *
 * Разметка — по строкам (см. [MeasuredWindow]): у каждой логической строки
 * свой TextLayoutResult, а её место по вертикали — сумма высот строк выше.
 * Поэтому любой перевод «пиксель ↔ символ» — это два шага: найти строку по
 * вертикали или номеру, затем спросить её собственную разметку. Разметка
 * есть только у строк около вьюпорта; кому нужна другая — размечается на
 * месте, по одной. Использует только multiplatform-API Compose.
 */

internal typealias TextWindow = MeasuredWindow<TextLayoutResult>

/** Максимальный сдвиг скролла для заданной высоты контента и вьюпорта. */
internal fun maxScrollOf(contentHeightPx: Float, viewportPx: Float): Float =
    (contentHeightPx - viewportPx).coerceAtLeast(0f)

/**
 * Сколько визуальных строк займёт текст в [columns] символов шириной —
 * оценка высоты строки, которую не размечали.
 *
 * Перенос по словам, как у разметки: длинное слово рвётся по ширине.
 * Для моноширинного шрифта совпадает с разметкой, для пропорционального —
 * близко; точная высота встаёт, когда строка доезжает до вьюпорта.
 */
internal fun estimateVisualLines(text: CharSequence, columns: Int): Int {
    val width = columns.coerceAtLeast(1)
    if (text.length <= width) return 1
    var lines = 1
    var lineStart = 0
    var lastSpace = -1
    var i = 0
    while (i < text.length) {
        if (text[i] == ' ') lastSpace = i
        if (i - lineStart >= width) {
            // Есть где перенести по слову — переносим там, иначе рвём слово
            lineStart = if (lastSpace > lineStart) lastSpace + 1 else i
            lastSpace = -1
            lines++
        }
        i++
    }
    return lines
}

/** Точный пиксель верха визуальной строки, содержащей символ (seq, col). Без «защёлкивания»
 *  к началу логической строки — поэтому нет дрожи на переносах. */
internal fun TextWindow.anchorToPx(seq: Long, col: Int): Float {
    if (isEmpty) return 0f
    val index = indexOfSeq(seq)
    val layout = layoutOrMeasure(index)
    // Вытесненная строка подтягивается к началу окна, как и в модели выделения
    val offset = if (seq < firstSeq) 0 else col.coerceIn(0, lengthOf(index))
    return topOf(index) + layout.getLineTop(layout.getLineForOffset(offset))
}

/** Якорь (seq, col) символа в левом-верхнем углу вьюпорта при сдвиге [scrollPx]. */
internal fun TextWindow.pxToAnchor(scrollPx: Float): Pair<Long, Int> {
    if (isEmpty) return firstSeq to 0
    val index = indexAt(scrollPx)
    val layout = layoutOrMeasure(index)
    val localY = (scrollPx - topOf(index)).coerceAtLeast(0f)
    val offset = layout.getOffsetForPosition(Offset(0f, localY)).coerceIn(0, lengthOf(index))
    return (firstSeq + index) to offset
}

/** Точка выделения (seq, col) для позиции указателя в координатах контента. */
internal fun TextWindow.pointToSelPoint(contentX: Float, contentY: Float): SelPoint {
    if (isEmpty) return SelPoint(firstSeq, 0)
    val index = indexAt(contentY)
    val layout = layoutOrMeasure(index)
    val localY = (contentY - topOf(index)).coerceAtLeast(0f)
    val offset = layout.getOffsetForPosition(Offset(contentX, localY)).coerceIn(0, lengthOf(index))
    return SelPoint(firstSeq + index, offset)
}

/**
 * Путь подсветки диапазона (seq, col) → (seq, col) — по строкам, каждая
 * своей разметкой на своей высоте, но только для строк с индексами от
 * [fromIndex] до [toIndex]: подсвечивать невидимое незачем, а выделение
 * «всего» на ста тысячах строк размечало бы их все. Столбцы зажимаются
 * по длине строки, номера — по окну: выделение может начинаться в
 * вытесненной строке.
 *
 * @return null, если подсвечивать нечего
 */
internal fun TextWindow.pathForRange(
    startSeq: Long, startCol: Int, endSeq: Long, endCol: Int,
    fromIndex: Int = 0, toIndex: Int = lineCount - 1
): Path? {
    if (isEmpty) return null
    val from = maxOf(startSeq, firstSeq, firstSeq + fromIndex.coerceAtLeast(0))
    val to = minOf(endSeq, lastSeq, firstSeq + toIndex.coerceAtMost(lineCount - 1))
    if (from > to) return null
    var path: Path? = null
    for (seq in from..to) {
        val index = (seq - firstSeq).toInt()
        val length = lengthOf(index)
        val s = if (seq == startSeq) startCol.coerceIn(0, length) else 0
        val e = if (seq == endSeq) endCol.coerceIn(0, length) else length
        if (e <= s) continue
        val segment = layoutOrMeasure(index).getPathForRange(s, e)
        segment.translate(Offset(0f, topOf(index)))
        (path ?: Path().also { path = it }).addPath(segment)
    }
    return path
}

/** Путь подсветки совпадения поиска; null — вне окна или пустое. */
internal fun TextWindow.pathForMatch(match: SearchMatch): Path? =
    pathForRange(match.seq, match.start, match.seq, match.end)

internal val SEARCH_ALL_COLOR = Color(0x66E6B800)     // все совпадения — приглушённый жёлтый
internal val SEARCH_CURRENT_COLOR = Color(0xCCFF8C00)  // текущее — яркий оранжевый

/**
 * Рисует одну панель-вьюпорт над окном строк: подсветки и текст, сдвинутые
 * на [scrollPx] и обрезанные границами панели. Рисуются — и подсвечиваются
 * — только строки, попавшие во вьюпорт: остальные не стоят ничего.
 *
 * Выделение и совпадения читаются в фазе draw через провайдеры: панель
 * перерисовывается по ревизиям без рекомпозиции.
 */
@Composable
internal fun OutputCanvas(
    window: TextWindow,
    scrollProvider: () -> Float,
    selectionColor: Color,
    revisionState: State<Int>,
    searchRevisionState: State<Int>,
    selectionProvider: () -> Pair<SelPoint, SelPoint>?,
    matchesProvider: (fromSeq: Long, toSeq: Long) -> List<SearchMatch>,
    currentMatchProvider: () -> SearchMatch?,
    modifier: Modifier
) {
    Canvas(modifier) {
        // Скролл, ревизии выделения и поиска читаем в фазе draw — Canvas перерисуется
        // при их изменении даже без рекомпозиции (надёжно на любой вкладке).
        revisionState.value
        searchRevisionState.value
        val scrollPx = scrollProvider()
        if (window.isEmpty) return@Canvas
        val from = window.indexAt(scrollPx)
        val to = window.indexAt(scrollPx + size.height)
        val fromSeq = window.firstSeq + from
        val toSeq = window.firstSeq + to
        clipRect {
            translate(top = -scrollPx) {
                val matches = matchesProvider(fromSeq, toSeq)
                if (matches.isNotEmpty()) {
                    val all = Path()
                    for (match in matches) window.pathForMatch(match)?.let { all.addPath(it) }
                    drawPath(all, color = SEARCH_ALL_COLOR)
                }
                currentMatchProvider()
                    ?.takeIf { it.seq in fromSeq..toSeq }
                    ?.let { window.pathForMatch(it) }
                    ?.let { drawPath(it, color = SEARCH_CURRENT_COLOR) }
                selectionProvider()
                    ?.let { (a, b) -> window.pathForRange(a.seq, a.col, b.seq, b.col, from, to) }
                    ?.let { drawPath(it, color = selectionColor) }
                for (index in from..to) {
                    drawText(window.layoutOrMeasure(index), topLeft = Offset(0f, window.topOf(index)))
                }
            }
        }
    }
}

/**
 * Единый интерактивный скроллбар на всю высоту компонента, управляющий
 * позицией скроллбэка (общий и в одно-, и в двухпанельном режиме).
 *
 * @param scrollPx текущий сдвиг скроллбэка
 * @param maxScroll максимальный сдвиг скроллбэка (для активного вьюпорта)
 * @param viewportPx высота окна скроллбэка (для размера ползунка)
 * @param contentHeightPx полная высота контента
 * @param onScrollTo установить сдвиг (как пользовательский скролл)
 */
@Composable
internal fun OutputScrollbar(
    scrollProvider: () -> Float,
    maxScroll: Float,
    viewportPx: Float,
    contentHeightPx: Float,
    onScrollTo: (Float) -> Unit,
    onActive: (Boolean) -> Unit,
    modifier: Modifier
) {
    if (contentHeightPx <= viewportPx + 1f) return

    val maxRef = rememberUpdatedState(maxScroll)
    val viewportRef = rememberUpdatedState(viewportPx)
    val contentRef = rememberUpdatedState(contentHeightPx)
    // ВАЖНО: колбэк тоже через rememberUpdatedState — иначе долгоживущий жест
    // pointerInput(Unit) захватит устаревший onScrollTo (со старым maxScroll) → скачки.
    val onScrollToRef = rememberUpdatedState(onScrollTo)
    val scrollProviderRef = rememberUpdatedState(scrollProvider)
    val onActiveRef = rememberUpdatedState(onActive)

    Canvas(
        modifier.pointerInput(Unit) {
            // Тащим ползунок по АБСОЛЮТНОЙ позиции указателя (с учётом точки захвата) —
            // ползунок точно следует за мышью, без накопления дельт и скачков.
            awaitEachGesture {
                val down = awaitFirstDown()
                // Геометрию ползунка фиксируем на время жеста СОЗНАТЕЛЬНО: если
                // пересчитывать её при росте лога, ползунок будет уползать
                // из-под курсора. Позиция и предел при этом берутся свежие.
                val track = size.height.toFloat()
                val thumb = (track * viewportRef.value / contentRef.value).coerceIn(30f, track)
                val denom = (track - thumb).coerceAtLeast(1f)
                val curThumbY = if (maxRef.value > 0f) (scrollProviderRef.value() / maxRef.value) * denom else 0f
                // Смещение точки захвата внутри ползунка (если кликнули мимо — по центру)
                val grab = (down.position.y - curThumbY).let { if (it in 0f..thumb) it else thumb / 2f }
                down.consume()
                onActiveRef.value(true)
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val targetThumbY = (ch.position.y - grab).coerceIn(0f, denom)
                        onScrollToRef.value(targetThumbY / denom * maxRef.value)
                        ch.consume()
                    }
                } finally {
                    onActiveRef.value(false)
                }
            }
        }
    ) {
        val track = size.height
        val thumb = (track * viewportPx / contentHeightPx).coerceIn(30f, track)
        val scrollPx = scrollProvider() // читаем в фазе draw — ползунок двигается без рекомпозиции
        val thumbY = if (maxScroll > 0f) (scrollPx / maxScroll) * (track - thumb) else 0f
        val width = 8f
        drawRoundRect(
            color = Color(0x55FFFFFF),
            topLeft = Offset(size.width - width - 2f, thumbY.coerceIn(0f, track - thumb)),
            size = Size(width, thumb),
            cornerRadius = CornerRadius(width / 2f, width / 2f)
        )
    }
}
