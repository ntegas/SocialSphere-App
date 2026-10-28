package com.aistudio.socialsphere.crmlxb.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * BATCH 2B — единственный global write pipeline для AppStateStore, заменяет
 * Batch 2's `Dispatchers.IO.limitedParallelism(1)` per-aggregate-type
 * диспетчеры.
 *
 * ПОЧЕМУ limitedParallelism(1) НЕ было настоящей FIFO-гарантией: он
 * ограничивает, сколько runnable этого диспетчера выполняются ОДНОВРЕМЕННО,
 * но освобождает свой "слот" в момент, когда корутина ПРИОСТАНАВЛИВАЕТСЯ —
 * в том числе когда она сама переключается на ДРУГОЙ диспетчер (именно это
 * делает `db.withTransaction {}` внутри Room — он уходит на собственный
 * transaction executor через withContext). В момент этого переключения
 * "воркер" limitedParallelism(1) считает текущую задачу завершённой и сразу
 * забирает следующую команду из очереди — то есть вторая запись может начать
 * СВОЮ транзакцию до того, как первая коммитнулась, несмотря на parallelism=1.
 * Formально это была scheduler-зависимая эвристика, а не structural guarantee.
 *
 * Здесь вместо этого — ОДНА долгоживущая корутина-потребитель, которая
 * обрабатывает команды обычным последовательным `for`-циклом: следующая
 * итерация физически не может начаться, пока текущая не ВЕРНУЛА управление —
 * не важно, на какой диспетчер команда переключалась внутри себя. Это
 * гарантия языка (порядок выполнения suspend-функций в одной корутине), а не
 * поведение конкретного диспетчера.
 *
 * Generic (не завязан на Room/SocialsphereDatabase) специально — чтобы этот
 * класс можно было протестировать напрямую (см. DbWriteQueueTest), без
 * Robolectric/реальной БД, тем же способом, каким его использует production.
 */
internal class DbWriteQueue(scope: CoroutineScope) {

    private class Command(val block: suspend () -> Unit, val result: CompletableDeferred<Unit>)

    // UNLIMITED, не bounded: личный CRM не создаёт тысяч записей одновременно
    // (реальный traffic — это UI-действия одного человека), а bounded-канал
    // потребовал бы решения про backpressure — запрещено (§33) молча терять
    // команды через trySend-fails-ignore. send() на bounded канале тоже не
    // теряет данные (suspend вместо drop), но добавляет вопрос выбора ёмкости
    // без реальной выгоды на этом масштабе — UNLIMITED проще и не хуже.
    private val channel = Channel<Command>(Channel.UNLIMITED)

    init {
        // Единственный consumer. НЕ launch{} внутри цикла — команда выполняется
        // прямо в теле цикла потребителя, поэтому следующая итерация ждёт
        // полного возврата текущей (см. класс-комментарий выше).
        scope.launch(Dispatchers.IO) {
            for (command in channel) {
                try {
                    command.block()
                    command.result.complete(Unit)
                } catch (t: Throwable) {
                    // Одна упавшая команда не должна убить потребителя навсегда —
                    // catch здесь, а не вокруг всего for, иначе следующая команда
                    // (и все последующие) никогда бы не выполнилась. Диагностика
                    // (лог) — забота caller'а через completeExceptionally ниже,
                    // не этого класса: DbWriteQueue намеренно не зависит от
                    // android.util.Log, чтобы оставаться тестируемым как чистый
                    // JVM-тест (см. DbWriteQueueTest) — Android-специфичное
                    // логирование делает AppStateStore.enqueueWrite поверх.
                    command.result.completeExceptionally(t)
                }
            }
        }
    }

    /**
     * Ставит команду в очередь. Возвращает [Deferred], который завершается
     * (или падает с исключением команды) ПОСЛЕ реального выполнения — caller
     * может проигнорировать результат (текущая fire-and-forget семантика UI)
     * либо `await()` его, когда нужен подтверждённый commit (см. Batch 3).
     * `trySend` на UNLIMITED-канале не приостанавливает вызывающего и
     * практически никогда не проваливается (только если канал закрыт) — но
     * даже в этом случае ошибка не проглатывается молча, а передаётся через
     * тот же Deferred.
     */
    fun enqueue(block: suspend () -> Unit): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        val sendResult = channel.trySend(Command(block, result))
        sendResult.exceptionOrNull()?.let { result.completeExceptionally(it) }
        if (sendResult.isFailure && sendResult.exceptionOrNull() == null) {
            result.completeExceptionally(IllegalStateException("DbWriteQueue: enqueue failed (channel closed)"))
        }
        return result
    }
}
