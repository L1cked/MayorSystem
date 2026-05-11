package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.util.loggedTask
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

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
    private val minRefreshIntervalMs = 15_000L
    private val batchSize = plugin.config.getInt("admin.offline_cache.batch_size", 500).coerceIn(100, 5000)

    private var refreshTaskId: Int = -1
    private var scanList: Array<OfflinePlayer>? = null
    private var scanIndex: Int = 0
    private var scanMap: LinkedHashMap<UUID, Entry>? = null

    fun snapshot(forceRefresh: Boolean = false): Snapshot {
        val now = System.currentTimeMillis()
        val age = now - lastRefreshMs.get()
        if (forceRefresh || age > ttlMs) {
            refreshAsync(force = forceRefresh)
        }
        return Snapshot(cache.get(), refreshing.get(), age)
    }

    fun refreshAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val age = now - lastRefreshMs.get()
        if (refreshing.get()) return
        if (!force && age <= ttlMs) return
        if (force && age < minRefreshIntervalMs) return
        if (!refreshing.compareAndSet(false, true)) return
        try {
            plugin.server.scheduler.runTask(plugin, plugin.loggedTask("offline player cache start scan") { safelyStartScan() })
        } catch (t: Throwable) {
            refreshing.set(false)
            plugin.logger.log(Level.WARNING, "Failed to schedule offline player cache refresh.", t)
        }
    }

    private fun safelyStartScan() {
        try {
            startScan()
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Offline player cache scan failed to start.", t)
            finish()
        }
    }

    private fun startScan() {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        val map = LinkedHashMap<UUID, Entry>(onlinePlayers.size * 2)
        for (p in onlinePlayers) {
            map[p.uniqueId] = Entry(p.uniqueId, p.name, hasPlayedBefore = true, isOnline = true)
        }

        scanMap = map
        scanList = Bukkit.getOfflinePlayers()
        scanIndex = 0

        scheduleScanTask()
    }

    private fun scheduleScanTask() {
        if (refreshTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(refreshTaskId) }
            refreshTaskId = -1
        }
        refreshTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(
            plugin,
            plugin.loggedTask("offline player cache batch scan") { safelyProcessBatch() },
            1L,
            1L
        )
        if (refreshTaskId == -1) {
            plugin.logger.warning("Failed to schedule offline player cache batch scan.")
            finish()
        }
    }

    private fun safelyProcessBatch() {
        try {
            processBatch()
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Offline player cache batch failed.", t)
            finish()
        }
    }

    private fun processBatch() {
        if (!plugin.isEnabled) {
            finish()
            return
        }

        val list = scanList ?: run {
            finish()
            return
        }
        val map = scanMap ?: run {
            finish()
            return
        }

        var processed = 0
        while (processed < batchSize && scanIndex < list.size) {
            val op = list[scanIndex++]
            val name = op.name
            if (name.isNullOrBlank()) {
                processed++
                continue
            }
            if (map.containsKey(op.uniqueId)) {
                processed++
                continue
            }
            val hasPlayed = op.hasPlayedBefore()
            map[op.uniqueId] = Entry(op.uniqueId, name, hasPlayedBefore = hasPlayed, isOnline = false)
            processed++
        }

        if (scanIndex >= list.size) {
            cache.set(map.values.toList())
            lastRefreshMs.set(System.currentTimeMillis())
            finish()
        }
    }

    private fun finish() {
        if (refreshTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(refreshTaskId) }
            refreshTaskId = -1
        }
        scanList = null
        scanMap = null
        scanIndex = 0
        refreshing.set(false)
    }
}
