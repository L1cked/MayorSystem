package mayorSystem.service

import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class OfflinePlayerCache(private val plugin: MayorPlugin) {

    data class Entry(
        val uuid: UUID,
        val name: String,
        val hasPlayedBefore: Boolean,
        val isOnline: Boolean
    )

    data class Snapshot(
        val entries: List<Entry>,
        val refreshing: Boolean,
        val ageMs: Long
    )

    private val cache = AtomicReference<List<Entry>>(emptyList())
    private val lastRefreshMs = AtomicLong(0L)
    private val refreshing = AtomicBoolean(false)

    private val ttlMs = 60_000L

    fun snapshot(forceRefresh: Boolean = false): Snapshot {
        val now = System.currentTimeMillis()
        val age = now - lastRefreshMs.get()
        if (forceRefresh || age > ttlMs) {
            refreshAsync()
        }
        return Snapshot(cache.get(), refreshing.get(), age)
    }

    fun refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            try {
                val built = buildSnapshot()
                cache.set(built)
                lastRefreshMs.set(System.currentTimeMillis())
            } finally {
                refreshing.set(false)
            }
        })
    }

    private fun buildSnapshot(): List<Entry> {
        // Must run on the main thread: Bukkit player APIs are not thread-safe.
        val onlinePlayers = Bukkit.getOnlinePlayers()
        val onlineById = onlinePlayers.associateBy { it.uniqueId }
        val merged = LinkedHashMap<UUID, Entry>(onlinePlayers.size * 2)

        for (p in onlinePlayers) {
            merged[p.uniqueId] = Entry(p.uniqueId, p.name, hasPlayedBefore = true, isOnline = true)
        }

        val offline = Bukkit.getOfflinePlayers()
        for (op in offline) {
            val name = op.name ?: continue
            if (merged.containsKey(op.uniqueId)) continue
            val online = onlineById.containsKey(op.uniqueId)
            val hasPlayed = online || op.hasPlayedBefore()
            merged[op.uniqueId] = Entry(op.uniqueId, name, hasPlayedBefore = hasPlayed, isOnline = online)
        }

        return merged.values.toList()
    }
}
