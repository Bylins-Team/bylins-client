package com.bylins.client.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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
    /**
     * Сервер отмечает конец ответа (IAC GA) -- значит ждать надо его, а не время.
     *
     * Иначе окно тишины опережает отметку: хвост ответа приходит через три десятка
     * миллисекунд, окно в двадцать срабатывает раньше, и панель рисует промежуточный кадр.
     * Подбирать окно под скорость сети -- гиблое дело, поэтому как только отметка
     * встретилась хоть раз, ждём только её (с потолком на случай, если она пропадёт).
     */
    @Volatile
    var endMarkSeen: Boolean = false

    /** Дольше этого отрисовка не откладывается, сколько бы текста ни шло. */
    val maxDelayMs: Long get() = if (endMarkSeen) MAX_HOLD_MS else (delayMs * 4).coerceAtMost(MAX_HOLD_MS)

    /** Сколько ждать продолжения: с отметкой конца ответа -- до потолка, иначе окно тишины. */
    private val waitMs: Long get() = if (endMarkSeen && delayMs > 0) MAX_HOLD_MS else delayMs

    // Заявки склеиваются: пока ждём тишины, все пришедшие куски дадут одну публикацию
    private val requests = Channel<Unit>(Channel.CONFLATED)

    // Есть ли что показывать. Без него оставшаяся в канале заявка давала лишнюю
    // публикацию уже показанного -- лишний кадр на ровном месте
    private val pending = AtomicBoolean(false)

    init {
        scope.launch {
            for (unused in requests) {
                val started = System.nanoTime()
                while (waitMs > 0) {
                    // Новый кусок в пределах окна -- ждём дальше, но не дольше потолка
                    withTimeoutOrNull(waitMs) { requests.receive() } ?: break
                    if ((System.nanoTime() - started) / 1_000_000 >= maxDelayMs) break
                }
                if (pending.getAndSet(false)) {
                    publish()
                }
            }
        }
    }

    companion object {
        /** Потолок ожидания: больше игрок уже замечает задержку. */
        const val MAX_HOLD_MS = 150L
    }

    /** Пришёл текст. Публикация -- сразу или по истечении окна тишины. */
    fun request() {
        // Ноль -- это "рисовать сразу", прямое указание игрока; его не переигрываем
        if (delayMs <= 0) {
            pending.set(false)
            publish()
            return
        }
        pending.set(true)
        requests.trySend(Unit)
    }

    /**
     * Отдать накопленное немедленно: сервер сказал "ответ закончен" (IAC GA), пришёл
     * локальный вывод или своё эхо.
     */
    fun flush() {
        pending.set(false)
        publish()
    }
}
