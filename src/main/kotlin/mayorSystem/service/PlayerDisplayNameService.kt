package mayorSystem.service

import mayorSystem.MayorPlugin
import mayorSystem.messaging.DisplayTextParser
import net.kyori.adventure.text.Component
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID

class PlayerDisplayNameService(
    private val plugin: MayorPlugin,
    private val tagResolver: DisplayRewardTagPrefixResolver = plugin.displayRewardTags
) {
    fun resolve(player: Player): ResolvedPlayerName =
        resolve(player.uniqueId, player.name)

    fun resolve(offline: OfflinePlayer, fallbackName: String? = null): ResolvedPlayerName =
        resolve(offline.uniqueId, fallbackName ?: offline.name)

    fun resolveMayor(uuid: UUID, fallbackName: String? = null): ResolvedPlayerName {
        val resolved = resolve(uuid, fallbackName)
        if (resolved.usesLuckPermsPrefix) return resolved

        val titlePrefix = plugin.settings.resolvedTitlePlayerPrefix().trim()
        if (titlePrefix.isBlank()) return resolved

        val component = DisplayTextParser.component(titlePrefix)
            .append(Component.space())
            .append(Component.text(resolved.plain))

        return ResolvedPlayerName(
            mini = DisplayTextParser.mini(component),
            plain = DisplayTextParser.plain(component),
            usesLuckPermsPrefix = false
        )
    }

    fun resolve(uuid: UUID, fallbackName: String? = null): ResolvedPlayerName {
        val baseName = baseName(uuid, fallbackName)
        val tagPrefix = displayRewardTagPrefix(uuid)
        luckPermsName(uuid, baseName, tagPrefix)?.let { return it }
        return composedName(baseName, tagPrefix, null, null)
    }

    private fun baseName(uuid: UUID, fallbackName: String?): String {
        val onlineName = Bukkit.getPlayer(uuid)?.name?.trim()?.takeIf { it.isNotBlank() }
        if (onlineName != null) return onlineName

        val normalizedFallback = fallbackName
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isUuidString(it) }
        if (normalizedFallback != null) return normalizedFallback

        val offlineName = Bukkit.getOfflinePlayer(uuid).name?.trim()?.takeIf { it.isNotBlank() }
        return offlineName ?: "Unknown player"
    }

    private fun luckPermsName(uuid: UUID, baseName: String, tagPrefix: Component?): ResolvedPlayerName? {
        val lp = runCatching { LuckPermsProvider.get() }.getOrNull() ?: return null
        val user = lp.userManager.getUser(uuid) ?: return null
        val meta = resolveLuckPermsMeta(lp, user, Bukkit.getPlayer(uuid)) ?: return null
        if (meta.prefix.isBlank()) return null

        val luckPermsPrefix = componentFromLuckPermsText(meta.prefix)

        return composedName(baseName, tagPrefix, luckPermsPrefix, meta.prefixPlain)
    }

    private fun resolveLuckPermsMeta(lp: LuckPerms, user: Any, player: Player?): LuckPermsMetaSnapshot? {
        val cachedData = user.javaClass.methods.firstOrNull { it.name == "getCachedData" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(user) }.getOrNull() }
            ?: return null

        val meta = cachedData.javaClass.methods.firstOrNull { it.name == "getMetaData" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(cachedData) }.getOrNull() }
            ?: run {
                val queryOptions = resolveLuckPermsQueryOptions(lp, player) ?: return null
                cachedData.javaClass.methods.firstOrNull { it.name == "getMetaData" && it.parameterCount == 1 }
                    ?.let { runCatching { it.invoke(cachedData, queryOptions) }.getOrNull() }
            }
            ?: return null

        val prefix = meta.javaClass.methods.firstOrNull { it.name == "getPrefix" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(meta) as? String }.getOrNull() }
            ?.trim()
            .orEmpty()

        return LuckPermsMetaSnapshot(
            prefix = prefix,
            prefixPlain = DisplayTextParser.plain(prefix, parseMiniMessage = false)
        )
    }

    private fun resolveLuckPermsQueryOptions(lp: LuckPerms, player: Player?): Any? {
        val contextManager = lp.javaClass.methods.firstOrNull { it.name == "getContextManager" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(lp) }.getOrNull() }
            ?: return null

        if (player != null) {
            val playerOptions = contextManager.javaClass.methods
                .filter { it.name == "getQueryOptions" && it.parameterCount == 1 }
                .firstOrNull { it.parameterTypes[0].isAssignableFrom(player.javaClass) }
                ?.let { runCatching { it.invoke(contextManager, player) }.getOrNull() }
                ?.let(::unwrapOptional)
            if (playerOptions != null) return playerOptions
        }

        return contextManager.javaClass.methods.firstOrNull { it.name == "getStaticQueryOptions" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(contextManager) }.getOrNull() }
    }

    private fun unwrapOptional(value: Any?): Any? {
        if (value == null || value.javaClass.name != "java.util.Optional") return value
        return runCatching {
            val present = value.javaClass.methods.firstOrNull { it.name == "isPresent" && it.parameterCount == 0 }
                ?.invoke(value) as? Boolean
            if (present != true) return@runCatching null
            value.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }?.invoke(value)
        }.getOrNull()
    }

    private fun componentFromLuckPermsText(raw: String): Component {
        return DisplayTextParser.component(raw, parseMiniMessage = false)
    }

    private fun displayRewardTagPrefix(uuid: UUID): Component? {
        return tagResolver.resolvePrefix(uuid)
    }

    private fun composedName(
        baseName: String,
        tagPrefix: Component?,
        luckPermsPrefix: Component?,
        luckPermsPrefixPlain: String?
    ): ResolvedPlayerName {
        val components = listOfNotNull(tagPrefix, luckPermsPrefix, Component.text(baseName))
        val component = components.reduce { acc, part -> acc.append(Component.space()).append(part) }
        val plainParts = listOf(
            tagPrefix?.let { DisplayTextParser.plain(it) },
            luckPermsPrefixPlain,
            baseName
        )
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
            .trim()
        return ResolvedPlayerName(
            mini = DisplayTextParser.mini(component),
            plain = plainParts,
            usesLuckPermsPrefix = luckPermsPrefix != null || tagPrefix != null
        )
    }

    private fun isUuidString(raw: String): Boolean =
        runCatching { UUID.fromString(raw.trim()) }.isSuccess

    data class ResolvedPlayerName(
        val mini: String,
        val plain: String,
        val usesLuckPermsPrefix: Boolean
    )

    private data class LuckPermsMetaSnapshot(
        val prefix: String,
        val prefixPlain: String
    )
}
