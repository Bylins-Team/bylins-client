package com.bylins.client.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Склейка обновлений вывода. Ответ сервера приходит частями (в записи пакетов -- два куска
 * через 3-4 мс), и без склейки экран рисует недорисованное, а следующим кадром дёргается.
 */
class SnapshotPublisherTest {

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("не дождались: $what")
    }

    @Test
    fun `несколько кусков подряд дают одну публикацию`() {
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 50) { published.incrementAndGet() }

        repeat(5) { publisher.request() }

        waitUntil("публикации") { published.get() >= 1 }
        Thread.sleep(150)
        assertEquals(1, published.get(), "склеились не все куски")
        scope.cancel()
    }

    @Test
    fun `нулевое окно публикует сразу`() {
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 0) { published.incrementAndGet() }

        publisher.request()
        publisher.request()

        assertEquals(2, published.get(), "с нулевым окном публикация должна быть сразу")
        scope.cancel()
    }

    @Test
    fun `flush отдаёт накопленное немедленно`() {
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 500) { published.incrementAndGet() }

        publisher.request()
        publisher.flush()

        assertEquals(1, published.get(), "flush должен публиковать не дожидаясь окна")
        scope.cancel()
    }

    @Test
    fun `поток продолжает обновляться, а не замирает`() {
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 20) { published.incrementAndGet() }

        // Непрерывный поток текста: публикации должны идти примерно раз в окно,
        // а не откладываться до конца потока
        repeat(10) {
            publisher.request()
            Thread.sleep(25)
        }

        waitUntil("несколько публикаций") { published.get() >= 5 }
        assertTrue(published.get() >= 5, "поток обновлений замер: ${published.get()}")
        scope.cancel()
    }
}
