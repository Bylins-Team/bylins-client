package com.bylins.client.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Склейка обновлений вывода: показать один кадр вместо нескольких на один ответ сервера.
 *
 * Ответ приходит частями -- в записи пакетов видно два-три куска с промежутками от 3 до 30
 * мс (сеть режет по размеру сегмента, хвост доезжает следующим пульсом). Клиент рисовал
 * каждый кусок, и между ними экран успевал показать недорисованное: текст стоял не на месте
 * и тут же прыгал.
 *
 * Точный признак конца ответа -- IAC GA от сервера (режим "Автозавершение"): по нему кадр
 * отдаётся сразу, [flush]. Ожидание тишины остаётся запасным путём -- для игроков и
 * серверов, у которых автозавершение выключено.
 *
 * Ждём тишины, а не фиксированное окно от первого куска: каждый новый кусок отодвигает
 * отрисовку на [delayMs]. Иначе третий кусок приходил уже за окном, и кадра снова два.
 * Чтобы сплошной поток текста не застрял в ожидании, есть потолок [maxDelayMs]: дольше него
 * отрисовка не откладывается ни при каком потоке.
 *
 * Буфер при этом наполняется сразу: триггеры, вкладки, логи и MSDP отрабатывают на приход
 * пакета, задержка касается только того, когда игрок это увидит.
 *
 * @param delayMs сколько тишины ждать; 0 -- отдавать сразу, как было раньше
 */
class SnapshotPublisher(
    private val scope: CoroutineScope,
    @Volatile var delayMs: Long,
    private val publish: () -> Unit
) {
    /** Дольше этого отрисовка не откладывается, сколько бы текста ни шло. */
    val maxDelayMs: Long get() = (delayMs * 4).coerceAtMost(MAX_HOLD_MS)

    // Заявки склеиваются: пока ждём тишины, все пришедшие куски дадут одну публикацию
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (unused in requests) {
                val started = System.nanoTime()
                while (delayMs > 0) {
                    // Новый кусок в пределах окна -- ждём дальше, но не дольше потолка
                    withTimeoutOrNull(delayMs) { requests.receive() } ?: break
                    if ((System.nanoTime() - started) / 1_000_000 >= maxDelayMs) break
                }
                publish()
            }
        }
    }

    companion object {
        /** Потолок ожидания: больше игрок уже замечает задержку. */
        const val MAX_HOLD_MS = 150L
    }

    /** Пришёл текст. Публикация -- сразу или по истечении окна. */
    fun request() {
        if (delayMs <= 0) {
            publish()
            return
        }
        requests.trySend(Unit)
    }

    /** Отдать накопленное немедленно (разрыв связи, локальный вывод команды). */
    fun flush() {
        publish()
    }
}
