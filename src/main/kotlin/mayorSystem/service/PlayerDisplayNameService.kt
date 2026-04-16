package mayorSystem.service

import mayorSystem.MayorPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID

class PlayerDisplayNameService(private val plugin: MayorPlugin) {
    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()
    private val legacyAmp = LegacyComponentSerializer.legacyAmpersand()
    private val plain = PlainTextComponentSerializer.plainText()

    fun resolve(player: Player): ResolvedPlayerName =
        resolve(player.uniqueId, player.name)

    fun resolve(offline: OfflinePlayer, fallbackName: String? = null): ResolvedPlayerName =
        resolve(offline.uniqueId, fallbackName ?: offline.name)

    fun resolveMayor(uuid: UUID, fallbackName: String? = null): ResolvedPlayerName {
        val resolved = resolve(uuid, fallbackName)
        if (resolved.usesLuckPermsPrefix) return resolved

        val titlePrefix = plugin.settings.resolvedTitlePlayerPrefix().trim()
        if (titlePrefix.isBlank()) return resolved

        val component = mini.deserialize(titlePrefix)
            .append(Component.space())
            .append(Component.text(resolved.plain))

        return ResolvedPlayerName(
            mini = mini.serialize(component),
            plain = plain.serialize(component).trim(),
            usesLuckPermsPrefix = false
        )
    }

    fun resolve(uuid: UUID, fallbackName: String? = null): ResolvedPlayerName {
        val baseName = baseName(uuid, fallbackName)
        luckPermsName(uuid, baseName)?.let { return it }
        return ResolvedPlayerName(
            mini = escapeMini(baseName),
            plain = baseName,
            usesLuckPermsPrefix = false
        )
    }

    private fun baseName(uuid: UUID, fallbackName: String?): String {
        val onlineName = Bukkit.getPlayer(uuid)?.name?.trim()?.takeIf { it.isNotBlank() }
        if (onlineName != null) return onlineName

        val normalizedFallback = fallbackName?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedFallback != null) return normalizedFallback

        val offlineName = Bukkit.getOfflinePlayer(uuid).name?.trim()?.takeIf { it.isNotBlank() }
        return offlineName ?: "Unknown"
    }

    private fun luckPermsName(uuid: UUID, baseName: String): ResolvedPlayerName? {
        val lp = runCatching { LuckPermsProvider.get() }.getOrNull() ?: return null
        val user = lp.userManager.getUser(uuid) ?: return null
        val meta = resolveLuckPermsMeta(lp, user, Bukkit.getPlayer(uuid)) ?: return null
        if (meta.prefix.isBlank()) return null

        val component = componentFromLuckPermsText(meta.prefix)
            .append(Component.space())
            .append(Component.text(baseName))

        return ResolvedPlayerName(
            mini = mini.serialize(component),
            plain = listOf(meta.prefixPlain, baseName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim(),
            usesLuckPermsPrefix = true
        )
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
            prefixPlain = plain.serialize(componentFromLuckPermsText(prefix)).trim()
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
        val text = raw.trim()
        if (text.isBlank()) return Component.empty()

        val component = when {
            text.contains('\u00A7') -> runCatching { legacy.deserialize(text) }.getOrNull()
            Regex("(?i)&([0-9A-FK-ORX])").containsMatchIn(text) -> runCatching { legacyAmp.deserialize(text) }.getOrNull()
            else -> null
        }
        return component ?: Component.text(text)
    }

    private fun escapeMini(input: String): String =
        input.replace("<", "").replace(">", "")

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
