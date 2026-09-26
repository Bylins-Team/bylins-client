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
    fun `после публикации лишнего кадра не будет`() {
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 30) { published.incrementAndGet() }

        repeat(5) { publisher.request() }
        waitUntil("первой публикации") { published.get() >= 1 }
        Thread.sleep(200)

        assertEquals(1, published.get(), "показали уже показанное")
        scope.cancel()
    }

    @Test
    fun `с отметкой конца ответа окно тишины не торопит кадр`() {
        // Хвост ответа приходит позже окна: без этого правила панель рисовала
        // промежуточный кадр, а через три десятка миллисекунд -- настоящий
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 20) { published.incrementAndGet() }
        publisher.endMarkSeen = true

        publisher.request()
        // Ждём вдвое дольше окна тишины (20 мс) -- этого хватает, чтобы показать, что оно
        // кадр не торопит. Больше нельзя: с отметкой ожидание ограничено потолком в
        // 150 мс, и пауза близко к нему делала тест плавающим -- на загруженном раннере
        // потолок успевал сработать раньше проверки
        Thread.sleep(40)

        assertEquals(0, published.get(), "нарисовали, не дождавшись отметки конца ответа")

        publisher.flush()
        assertEquals(1, published.get())
        scope.cancel()
    }

    @Test
    fun `без отметки остаётся ожидание тишины`() {
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 20) { published.incrementAndGet() }

        publisher.request()

        waitUntil("публикации по тишине") { published.get() == 1 }
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
    fun `кусок в пределах окна отодвигает отрисовку`() {
        // Окно нарочно большое: на загруженной машине sleep растягивается, и тест с
        // близкими числами ловил бы не поведение, а планировщик
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 400) { published.incrementAndGet() }

        publisher.request()
        Thread.sleep(50)          // хвост приехал в пределах окна
        publisher.request()
        Thread.sleep(50)          // от первого куска прошло 100 мс -- фиксированное окно
                                  // в 400 мс ещё не истекло бы, но и тишины не было

        assertEquals(0, published.get(), "отрисовали, не дождавшись тишины")
        waitUntil("публикации после тишины") { published.get() == 1 }
        scope.cancel()
    }

    @Test
    fun `сплошной поток не откладывается дольше потолка`() {
        val scope = CoroutineScope(Dispatchers.IO)
        val published = AtomicInteger()
        val publisher = SnapshotPublisher(scope, delayMs = 20) { published.incrementAndGet() }

        // Текст идёт без пауз: тишины не будет вовсе, но потолок обязан сработать
        val stop = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < stop) {
            publisher.request()
            Thread.sleep(5)
        }

        assertTrue(published.get() >= 2, "поток замер в ожидании тишины: ${published.get()}")
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
