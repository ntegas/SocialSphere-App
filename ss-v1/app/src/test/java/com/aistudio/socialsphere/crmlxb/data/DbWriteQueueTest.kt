package com.aistudio.socialsphere.crmlxb.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BATCH 2B — regression tests for the actual production [DbWriteQueue] class,
 * not a copy of its logic (Batch 2B §21/§22). Plain JVM tests: DbWriteQueue is
 * generic and has no Room/Robolectric dependency.
 *
 * Each test builds its OWN independent [CoroutineScope] (not `runBlocking`'s
 * own scope) for the queue's consumer coroutine, and cancels it at the end.
 * The consumer runs an intentionally infinite `for (command in channel)` loop
 * (correct in production — AppStateStore's app-lifetime scope never "joins");
 * launching it as a child of `runBlocking` itself would make `runBlocking`
 * hang forever waiting for that loop to finish.
 */
class DbWriteQueueTest {

    /**
     * TEST GROUP: writer ordering (§21-22). Proves the property Batch 2's
     * `Dispatchers.IO.limitedParallelism(1)` did NOT structurally guarantee:
     * command A suspending INSIDE its own execution (simulating what
     * `db.withTransaction`'s internal dispatcher switch does) must not let
     * command B start before A fully finishes. A `for (command in channel)`
     * loop processing commands in the SAME coroutine, one at a time, gives
     * this by construction — this test proves it holds for the real class.
     */
    @Test
    fun commandDoesNotStartUntilPreviousFullyCompletes() = runBlocking {
        val queueScope = CoroutineScope(Dispatchers.IO)
        val events = mutableListOf<String>()
        val queue = DbWriteQueue(queueScope)

        val a = queue.enqueue {
            events.add("A-start")
            delay(50) // симулирует suspension ВНУТРИ команды (как собственный
                      // withContext(transactionDispatcher) у db.withTransaction) —
                      // ключевая проверка: не должен ли B "проскочить" сюда?
            events.add("A-finish")
        }
        val b = queue.enqueue {
            events.add("B-start")
            events.add("B-finish")
        }

        a.await(); b.await()
        queueScope.cancel()

        assertEquals(listOf("A-start", "A-finish", "B-start", "B-finish"), events)
    }

    /** Три команды подряд (реальный сценарий V1→V2→V3) — тот же порядок. */
    @Test
    fun threeCommandsExecuteStrictlyInEnqueueOrder() = runBlocking {
        val queueScope = CoroutineScope(Dispatchers.IO)
        val events = mutableListOf<String>()
        val queue = DbWriteQueue(queueScope)

        val results = listOf("A", "B", "C").map { name ->
            queue.enqueue {
                events.add("$name-start")
                delay(10)
                events.add("$name-finish")
            }
        }
        results.forEach { it.await() }
        queueScope.cancel()

        assertEquals(
            listOf("A-start", "A-finish", "B-start", "B-finish", "C-start", "C-finish"),
            events
        )
    }

    /**
     * TEST GROUP: writer failure isolation (§27). A failed command must not
     * kill the consumer — subsequent commands must still execute.
     */
    @Test
    fun writerSurvivesFailedCommand() = runBlocking {
        val queueScope = CoroutineScope(Dispatchers.IO)
        val queue = DbWriteQueue(queueScope)

        val a = queue.enqueue { throw RuntimeException("boom") }
        var bRan = false
        val b = queue.enqueue { bRan = true }

        val aFailed = try { a.await(); false } catch (e: Exception) { true }
        b.await()
        queueScope.cancel()

        assertTrue("a failed command must not silently succeed", aFailed)
        assertTrue("writer must remain alive and process the next command", bRan)
    }

    /**
     * TEST GROUP: awaitable failure propagation (§28, prep for Batch 3). The
     * caller of a failing command must receive the actual failure, not a
     * false success — critical for restore/import flows that will need to
     * know whether a commit really happened.
     */
    @Test
    fun awaitOnFailedCommandThrowsTheOriginalException() = runBlocking {
        val queueScope = CoroutineScope(Dispatchers.IO)
        val queue = DbWriteQueue(queueScope)
        val marker = IllegalStateException("simulated DB failure")

        val a = queue.enqueue { throw marker }

        try {
            a.await()
            fail("caller must not see success for a failed command")
        } catch (e: IllegalStateException) {
            assertEquals("simulated DB failure", e.message)
        } finally {
            queueScope.cancel()
        }
    }

    /** Успешная команда действительно выполняется и её результат наблюдаем
     *  (не просто "не упала"). */
    @Test
    fun successfulCommandActuallyRuns() = runBlocking {
        val queueScope = CoroutineScope(Dispatchers.IO)
        val queue = DbWriteQueue(queueScope)
        var ran = false

        queue.enqueue { ran = true }.await()
        queueScope.cancel()

        assertTrue(ran)
    }
}
