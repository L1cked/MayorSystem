package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.messaging.DisplayTextParser
import mayorSystem.rewards.DeluxeTagsIntegration
import mayorSystem.rewards.DisplayRewardTagId
import mayorSystem.rewards.TagRewardSettings
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerCommandEvent
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface DisplayRewardTagPrefixResolver {
    fun resolvePrefix(uuid: UUID): Component?
    fun clear()
    fun clear(uuid: UUID)
}

class DisplayRewardTagResolver(
    private val plugin: MayorPlugin,
    private val liveSource: LiveTagSource = BukkitLiveTagSource(plugin),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
) : Listener, DisplayRewardTagPrefixResolver {
    interface LiveTagSource {
        fun isPrimaryThread(): Boolean
        fun onlinePlayer(uuid: UUID): Player?
        fun activeTagId(uuid: UUID, player: Player?): String?
        fun tagDisplay(uuid: UUID, player: Player?, tagId: String): String?
    }

    private data class CacheKey(
        val uuid: UUID,
        val tagId: String
    )

    private data class CacheEntry(
        val expiresAtMs: Long,
        val component: Component?
    )

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    override fun resolvePrefix(uuid: UUID): Component? {
        val reward = plugin.settings.displayReward
        if (!reward.enabled || !reward.tag.enabled || !reward.tag.renderBeforeLuckPerms) return null

        val tagId = reward.tag.deluxeTagId.trim()
        if (!DisplayRewardTagId.isValid(tagId)) return null

        val key = CacheKey(uuid, tagId.lowercase())
        val now = nowMs()
        val cached = cache[key]
        if (cached != null && now < cached.expiresAtMs) {
            return cached.component
        }

        if (!liveSource.isPrimaryThread()) {
            return cached?.component ?: configuredTrackedPrefix(uuid, tagId, reward.tag)
        }

        val resolved = resolveFresh(uuid, tagId, reward.tag)
        cache[key] = CacheEntry(now + cacheTtlMs, resolved)
        return resolved
    }

    override fun clear() {
        cache.clear()
    }

    override fun clear(uuid: UUID) {
        cache.keys.removeIf { it.uuid == uuid }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        clear(event.player.uniqueId)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        clear(event.player.uniqueId)
    }

    @EventHandler
    fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name in SOURCE_PLUGIN_NAMES) clear()
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin.name in SOURCE_PLUGIN_NAMES) clear()
    }

    @EventHandler
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        if (isTagsCommand(event.message.removePrefix("/"))) clear()
    }

    @EventHandler
    fun onServerCommand(event: ServerCommandEvent) {
        if (isTagsCommand(event.command)) clear()
    }

    private fun resolveFresh(uuid: UUID, tagId: String, tag: TagRewardSettings): Component? {
        val player = liveSource.onlinePlayer(uuid)
        val active = liveSource.activeTagId(uuid, player)?.trim()?.takeIf { it.isNotBlank() }
            ?: trackedTagId(uuid)

        if (!active.equals(tagId, ignoreCase = true)) return null

        val raw = liveSource.tagDisplay(uuid, player, tagId)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: configuredTagDisplay(tag)

        return raw?.let(::parseDisplay)
    }

    private fun configuredTrackedPrefix(uuid: UUID, tagId: String, tag: TagRewardSettings): Component? {
        if (!trackedTagId(uuid).equals(tagId, ignoreCase = true)) return null
        return configuredTagDisplay(tag)?.let(::parseDisplay)
    }

    private fun configuredTagDisplay(tag: TagRewardSettings): String? =
        plugin.settings.applyTitleTokens(tag.display)
            .trim()
            .takeIf { it.isNotBlank() }

    private fun parseDisplay(raw: String): Component =
        DisplayTextParser.component(plugin.settings.applyTitleTokens(raw))

    private fun trackedTagId(uuid: UUID): String? {
        val trackedUuid = plugin.config.getString(TRACKED_UUID_PATH)
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        if (trackedUuid != uuid) return null
        return plugin.config.getString(TRACKED_TAG_ID_PATH)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun isTagsCommand(raw: String): Boolean {
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val root = parts.firstOrNull()?.lowercase().orEmpty()
        return root == "tags" || root == "deluxetags:tags"
    }

    private class BukkitLiveTagSource(private val plugin: MayorPlugin) : LiveTagSource {
        private val deluxeTags = DeluxeTagsIntegration(plugin)
        @Volatile
        private var placeholderApiMethod: Method? = null
        @Volatile
        private var placeholderApiUnavailable: Boolean = false

        override fun isPrimaryThread(): Boolean =
            runCatching { Bukkit.isPrimaryThread() }.getOrDefault(false)

        override fun onlinePlayer(uuid: UUID): Player? =
            Bukkit.getPlayer(uuid)

        override fun activeTagId(uuid: UUID, player: Player?): String? =
            deluxeTags.activeTagId(uuid, warnFailures = false)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: placeholder(player, "%deluxetags_identifier%")

        override fun tagDisplay(uuid: UUID, player: Player?, tagId: String): String? =
            placeholder(player, "%deluxetags_tag%")
                ?: deluxeTags.tagSnapshot(tagId, warnFailures = false)
                    ?.display
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

        private fun placeholder(player: Player?, placeholder: String): String? {
            if (player == null) return null
            val papi = plugin.server.pluginManager.getPlugin("PlaceholderAPI")
            if (papi == null || !papi.isEnabled) return null

            val method = placeholderApiMethod ?: loadPlaceholderApiMethod() ?: return null
            val rendered = runCatching { method.invoke(null, player, placeholder) as? String }.getOrNull()
            return rendered
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != placeholder && !it.contains("%deluxetags_", ignoreCase = true) }
        }

        private fun loadPlaceholderApiMethod(): Method? {
            if (placeholderApiUnavailable) return null
            return runCatching {
                Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                    .getMethod("setPlaceholders", OfflinePlayer::class.java, String::class.java)
            }.getOrElse {
                placeholderApiUnavailable = true
                null
            }?.also { placeholderApiMethod = it }
        }
    }

    companion object {
        private const val DEFAULT_CACHE_TTL_MS = 750L
        private const val TRACKED_UUID_PATH = "admin.display_reward.tracked_uuid"
        private const val TRACKED_TAG_ID_PATH = "admin.display_reward.tracked_tag_id"
        private val SOURCE_PLUGIN_NAMES = setOf("DeluxeTags", "PlaceholderAPI", "LuckPerms")
    }
}
