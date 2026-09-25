package com.bylins.client.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Склейка обновлений вывода: показать один кадр вместо нескольких на один ответ сервера.
 *
 * Ответ приходит частями -- в записи пакетов видно два куска через 3-4 мс (сеть режет по
 * размеру сегмента). Клиент рисовал каждый кусок, и между ними экран успевал показать
 * недорисованное: текст стоял не на месте и тут же прыгал. Здесь приход помечается, а
 * снимок для панели вывода отдаётся один раз, спустя [delayMs].
 *
 * Буфер при этом наполняется сразу: триггеры, вкладки, логи и MSDP отрабатывают на приход
 * пакета, задержка касается только того, когда игрок это увидит.
 *
 * @param delayMs окно склейки; 0 -- отдавать сразу, как было раньше
 */
class SnapshotPublisher(
    private val scope: CoroutineScope,
    @Volatile var delayMs: Long,
    private val publish: () -> Unit
) {
    // Заявки склеиваются: пока ждём окно, все пришедшие куски дадут одну публикацию
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (unused in requests) {
                val window = delayMs
                if (window > 0) {
                    delay(window)
                }
                publish()
            }
        }
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
