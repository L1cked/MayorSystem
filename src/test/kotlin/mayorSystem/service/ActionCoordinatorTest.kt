package mayorSystem.service

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActionCoordinatorTest {

    @Test
    fun `serialized runs same key operations one at a time`() = runBlocking {
        val coordinator = ActionCoordinator()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        coroutineScope {
            listOf(1, 2, 3).map {
                async {
                    coordinator.serialized("settings") {
                        val nowActive = active.incrementAndGet()
                        peak.updateAndGet { current -> maxOf(current, nowActive) }
                        delay(25)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, peak.get())
    }

    @Test
    fun `trySerialized rejects second in flight operation for same key`() = runBlocking {
        val coordinator = ActionCoordinator()

        val blocker = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()

        val first = async {
            coordinator.trySerialized("reload") {
                blocker.complete(Unit)
                release.await()
            }
        }

        blocker.await()
        val second = coordinator.trySerialized("reload") { 42 }
        assertNull(second)

        release.complete(Unit)
        first.await()
    }
}
