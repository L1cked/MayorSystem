package mayorSystem.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ActionCoordinator {
    private data class LockEntry(
        val mutex: Mutex = Mutex(),
        val users: AtomicInteger = AtomicInteger(0)
    )

    private val locks = ConcurrentHashMap<String, LockEntry>()

    suspend fun <T> serialized(key: String, block: suspend () -> T): T {
        val entry = acquire(key)
        return try {
            entry.mutex.withLock {
                block()
            }
        } finally {
            release(key, entry)
        }
    }

    suspend fun <T> trySerialized(key: String, block: suspend () -> T): T? {
        val entry = acquire(key)
        if (!entry.mutex.tryLock()) {
            release(key, entry)
            return null
        }
        return try {
            block()
        } finally {
            entry.mutex.unlock()
            release(key, entry)
        }
    }

    private fun acquire(key: String): LockEntry =
        locks.compute(key) { _, existing ->
            val entry = existing ?: LockEntry()
            entry.users.incrementAndGet()
            entry
        }!!

    private fun release(key: String, entry: LockEntry) {
        val remaining = entry.users.decrementAndGet()
        if (remaining > 0) return

        locks.computeIfPresent(key) { _, current ->
            if (current === entry && current.users.get() == 0) null else current
        }
    }
}
