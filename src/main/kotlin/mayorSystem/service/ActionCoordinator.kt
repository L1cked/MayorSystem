package mayorSystem.service

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ActionCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> serialized(key: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            block()
        }
    }

    suspend fun <T> trySerialized(key: String, block: suspend () -> T): T? {
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        if (!mutex.tryLock()) {
            return null
        }
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
