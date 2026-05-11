package mayorSystem.service

import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.UUID

class PlayerIdentityService(private val plugin: MayorPlugin) {

    fun knownUuidByName(raw: String): UUID? {
        val value = raw.trim()
        if (value.isBlank()) return null

        runCatching { UUID.fromString(value) }.getOrNull()?.let { return it }

        Bukkit.getOnlinePlayers()
            .firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?.uniqueId
            ?.let { return it }

        cachedEntryByName(value)?.uuid?.let { return it }

        return runCatching {
            Bukkit.getOfflinePlayer(value)
                .takeIf { it.hasPlayedBefore() }
                ?.uniqueId
        }.getOrNull()
    }

    fun displayName(uuid: UUID, fallback: String? = null): String {
        Bukkit.getPlayer(uuid)
            ?.name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        fallback
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isUuidString(it) }
            ?.let { return it }

        cachedEntryByUuid(uuid)?.name?.let { return it }

        return runCatching {
            Bukkit.getOfflinePlayer(uuid).name
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "Unknown player"
    }

    fun cachedDisplayName(uuid: UUID, fallback: String? = null): String {
        Bukkit.getPlayer(uuid)
            ?.name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        fallback
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isUuidString(it) }
            ?.let { return it }

        cachedEntryByUuid(uuid)?.name?.let { return it }

        return "Unknown player"
    }

    fun offlinePlayerForProfile(uuid: UUID): OfflinePlayer =
        Bukkit.getOfflinePlayer(uuid)

    private fun cachedEntryByName(name: String): OfflinePlayerCache.Entry? =
        runCatching {
            plugin.offlinePlayers.snapshot(forceRefresh = false).entries
                .firstOrNull { it.name.equals(name, ignoreCase = true) && (it.hasPlayedBefore || it.isOnline) }
        }.getOrNull()

    private fun cachedEntryByUuid(uuid: UUID): OfflinePlayerCache.Entry? =
        runCatching {
            plugin.offlinePlayers.snapshot(forceRefresh = false).entries
                .firstOrNull { it.uuid == uuid && it.name.isNotBlank() && (it.hasPlayedBefore || it.isOnline) }
        }.getOrNull()

    private fun isUuidString(raw: String): Boolean =
        runCatching { UUID.fromString(raw.trim()) }.isSuccess
}
