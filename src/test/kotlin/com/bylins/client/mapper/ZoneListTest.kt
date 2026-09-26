package com.bylins.client.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZoneListTest {

    private fun room(id: String, zone: String?, visited: Boolean = true) =
        Room(id = id, name = "Комната $id", zone = zone, visited = visited)

    private val names = mapOf("10" to "Деревня", "20" to "Лес", "30" to "Болото")

    @Test
    fun `видимые — зоны отрисованных комнат, крупные сверху`() {
        val displayed = listOf(room("1", "10"), room("2", "20"), room("3", "20"), room("4", null), room("5", ""))

        val zones = ZoneList.visible(displayed, names)

        assertEquals(listOf("20", "10"), zones.map { it.id })
        assertEquals(listOf(2, 1), zones.map { it.roomCount })
        assertEquals("Лес", zones[0].title)
    }

    @Test
    fun `безымянная зона называется по ID`() {
        val zones = ZoneList.visible(listOf(room("1", "99")), names)

        assertEquals("Зона 99", zones.single().title)
    }

    @Test
    fun `все — по имени, безымянные в конце`() {
        val rooms = listOf(room("1", "30"), room("2", "10"), room("3", "99"), room("4", "20"))

        val zones = ZoneList.all(rooms, names)

        assertEquals(listOf("Болото", "Деревня", "Лес", "Зона 99"), zones.map { it.title })
    }

    @Test
    fun `поиск — по подстроке имени или ID, без учёта регистра`() {
        val rooms = listOf(room("1", "30"), room("2", "10"), room("3", "99"), room("4", "20"))

        assertEquals(listOf("Лес"), ZoneList.all(rooms, names, "ЛЕС").map { it.title })
        assertEquals(listOf("Зона 99"), ZoneList.all(rooms, names, "99").map { it.title })
        assertEquals(listOf("Болото", "Зона 99"), ZoneList.all(rooms, names, "о").map { it.title })
        assertEquals(4, ZoneList.all(rooms, names, "   ").size)
    }

    @Test
    fun `комната входа — посещённая с наименьшим ID, иначе любая`() {
        val rooms = listOf(room("9", "10", visited = false), room("5", "10"), room("7", "10"), room("1", "20"))

        assertEquals("5", ZoneList.entryRoom("10", rooms))
        assertEquals("9", ZoneList.entryRoom("10", listOf(room("9", "10", visited = false))))
        assertNull(ZoneList.entryRoom("77", rooms))
    }

    @Test
    fun `масштаб по размеру зоны с запасом в клетку и в пределах допустимого`() {
        // 8 клеток + 2 запаса = 10 × 64 = 640 px на 640 px канвы → 1.0
        assertEquals(1f, ZoneList.zoomToFit(8, 4, 640f, 640f, 64f, 0.3f, 3f))
        // Узкая канва ограничивает по ширине
        assertEquals(0.5f, ZoneList.zoomToFit(8, 4, 320f, 640f, 64f, 0.3f, 3f))
        // Крошечная зона не раздувается выше потолка
        assertEquals(3f, ZoneList.zoomToFit(1, 1, 2000f, 2000f, 64f, 0.3f, 3f))
        // Огромная — не мельче пола
        assertEquals(0.3f, ZoneList.zoomToFit(100, 100, 640f, 640f, 64f, 0.3f, 3f))
        // Без канвы — как есть
        assertEquals(1f, ZoneList.zoomToFit(8, 4, 0f, 0f, 64f, 0.3f, 3f))
    }
}
