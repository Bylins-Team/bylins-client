package com.bylins.client.mapper

/** Зона в списке панели карты. */
data class ZoneEntry(val id: String, val name: String?, val roomCount: Int) {
    /** Имя зоны, если известно, иначе «Зона <id>». */
    val title: String get() = name ?: "Зона $id"
}

/**
 * Список зон для панели карты — чистая логика, без Compose.
 *
 * Два режима: «видимые» — зоны отрисованных сейчас комнат (легенда того, что
 * на экране), «все» — каждая зона карты; их полтысячи, поэтому с поиском.
 */
object ZoneList {

    /** Зоны отрисованных комнат: сперва те, которых на экране больше. */
    fun visible(displayed: Collection<Room>, names: Map<String, String>): List<ZoneEntry> =
        entries(displayed, names).sortedWith(compareByDescending<ZoneEntry> { it.roomCount }.thenBy { it.title })

    /**
     * Все зоны карты по имени.
     *
     * @param query подстрока имени или ID без учёта регистра; пустая — все
     */
    fun all(rooms: Collection<Room>, names: Map<String, String>, query: String = ""): List<ZoneEntry> {
        val needle = query.trim().lowercase()
        return entries(rooms, names)
            .filter { needle.isEmpty() || it.title.lowercase().contains(needle) || it.id.lowercase().contains(needle) }
            .sortedWith(compareBy<ZoneEntry> { it.name == null }.thenBy { it.title.lowercase() })
    }

    /**
     * Комната, с которой смотреть на зону, которой нет на экране: посещённая
     * с наименьшим ID — так выбор устойчив между запусками, — иначе любая.
     */
    fun entryRoom(zoneId: String, rooms: Collection<Room>): String? {
        val inZone = rooms.filter { it.zone == zoneId }
        return (inZone.filter { it.visited }.ifEmpty { inZone }).minByOrNull { it.id }?.id
    }

    /**
     * Масштаб, при котором сетка в [gridWidth] × [gridHeight] клеток умещается
     * в канву с клеткой запаса по краям; в пределах [min]..[max].
     */
    fun zoomToFit(
        gridWidth: Int,
        gridHeight: Int,
        canvasWidth: Float,
        canvasHeight: Float,
        baseSpacing: Float,
        min: Float,
        max: Float
    ): Float {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return 1f
        val byWidth = canvasWidth / ((gridWidth + 2) * baseSpacing)
        val byHeight = canvasHeight / ((gridHeight + 2) * baseSpacing)
        return minOf(byWidth, byHeight).coerceIn(min, max)
    }

    private fun entries(rooms: Collection<Room>, names: Map<String, String>): List<ZoneEntry> =
        rooms.asSequence()
            .mapNotNull { it.zone?.takeIf { z -> z.isNotEmpty() } }
            .groupingBy { it }
            .eachCount()
            .map { (id, count) -> ZoneEntry(id, names[id], count) }
}
