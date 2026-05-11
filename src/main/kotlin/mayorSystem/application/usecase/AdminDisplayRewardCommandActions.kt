package mayorSystem.application.usecase

import java.util.UUID
import java.util.concurrent.CompletableFuture
import mayorSystem.MayorPlugin
import mayorSystem.rewards.DisplayRewardMode
import mayorSystem.rewards.DisplayRewardSettings
import mayorSystem.rewards.DisplayRewardSubject
import mayorSystem.rewards.DisplayRewardTargetType
import mayorSystem.rewards.DisplayRewardText
import mayorSystem.security.Perms
import mayorSystem.service.ActionResult
import org.bukkit.entity.Player

/**
 * Command-facing display reward admin actions.
 *
 * Keeps display reward config paths, optional LuckPerms validation, audit
 * logging, prefix/tag refresh behavior, and permission fallbacks identical while
 * moving this workflow out of the broad AdminActions facade.
 */
class AdminDisplayRewardCommandActions(private val plugin: MayorPlugin) {
    private val support = AdminActionSupport(plugin)

    private companion object {
        private const val KEY_SETTINGS = "settings"
        private const val TARGET_USER_NAMES_PATH = "admin.display_reward.target_user_names"
    }

    suspend fun updateDisplayRewardConfig(
        actor: Player?,
        path: String,
        value: Any?,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult {
        support.requirePerms(actor, plugin.settings.displayReward.adminEditPermission, Perms.ADMIN_SETTINGS_EDIT)?.let {
            return it
        }
        return support.serializedResult(KEY_SETTINGS) {
            runCatching {
                support.updateConfig(actor, path, value, reload = false)
                reloadSettingsOnly()
                if (plugin.hasDisplayRewardTags()) {
                    plugin.displayRewardTags.clear()
                }
                if (plugin.hasMayorUsernamePrefix()) {
                    plugin.mayorUsernamePrefix.syncAllOnline(actor)
                }
                ActionResult.Success(successKey, displayRewardPlaceholders(path, successPlaceholders))
            }.getOrElse {
                support.logFailure("Failed to update display reward '$path'.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun syncDisplayReward(actor: Player?): ActionResult {
        support.requirePerms(actor, plugin.settings.displayReward.adminEditPermission, Perms.ADMIN_SETTINGS_EDIT)?.let {
            return it
        }
        return support.serializedResult(KEY_SETTINGS) {
            runCatching {
                if (plugin.hasMayorUsernamePrefix()) {
                    plugin.mayorUsernamePrefix.syncAllOnline(actor)
                }
                if (plugin.hasDisplayRewardTags()) {
                    plugin.displayRewardTags.clear()
                }
                support.log(actor, "DISPLAY_REWARD_SYNC")
                ActionResult.Success("admin.settings.display_reward_synced")
            }.getOrElse {
                support.logFailure("Failed to sync display reward.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun setDisplayRewardDefaultMode(actor: Player?, mode: DisplayRewardMode): ActionResult =
        updateDisplayRewardConfig(
            actor,
            "display_reward.default_mode",
            mode.name,
            "admin.settings.display_reward_default_mode_set",
            mapOf("mode" to mode.label())
        )

    suspend fun setDisplayRewardTarget(actor: Player?, type: DisplayRewardTargetType, target: String, mode: DisplayRewardMode): ActionResult {
        support.requirePerms(actor, plugin.settings.displayReward.adminEditPermission, Perms.ADMIN_SETTINGS_EDIT)?.let {
            return it
        }
        val resolved = resolveRewardTarget(type, target) ?: return ActionResult.Rejected(
            "admin.settings.display_reward_target_invalid"
        )
        return support.serializedResult(KEY_SETTINGS) {
            runCatching {
                support.updateConfig(actor, "${type.configPath}.${resolved.key}", mode.name, reload = false)
                rememberRewardTargetName(type, resolved)
                reloadSettingsOnly()
                if (plugin.hasDisplayRewardTags()) plugin.displayRewardTags.clear()
                if (plugin.hasMayorUsernamePrefix()) plugin.mayorUsernamePrefix.syncAllOnline(actor)
                support.log(
                    actor,
                    "DISPLAY_REWARD_TARGET_SET",
                    target = resolved.key,
                    details = mapOf("type" to type.name, "mode" to mode.name)
                )
                ActionResult.Success(
                    "admin.settings.display_reward_target_set",
                    mapOf("target" to resolved.displayName, "mode" to mode.label())
                )
            }.getOrElse {
                support.logFailure("Failed to set display reward target '$target'.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun removeDisplayRewardTarget(actor: Player?, type: DisplayRewardTargetType, target: String): ActionResult {
        support.requirePerms(actor, plugin.settings.displayReward.adminEditPermission, Perms.ADMIN_SETTINGS_EDIT)?.let {
            return it
        }
        val resolved = resolveRewardTarget(type, target) ?: return ActionResult.Rejected(
            "admin.settings.display_reward_target_invalid"
        )
        return support.serializedResult(KEY_SETTINGS) {
            runCatching {
                support.updateConfig(actor, "${type.configPath}.${resolved.key}", null, reload = false)
                forgetRewardTargetName(type, resolved.key)
                reloadSettingsOnly()
                if (plugin.hasDisplayRewardTags()) plugin.displayRewardTags.clear()
                if (plugin.hasMayorUsernamePrefix()) plugin.mayorUsernamePrefix.syncAllOnline(actor)
                support.log(actor, "DISPLAY_REWARD_TARGET_REMOVE", target = resolved.key, details = mapOf("type" to type.name))
                ActionResult.Success(
                    "admin.settings.display_reward_target_removed",
                    mapOf("target" to resolved.displayName)
                )
            }.getOrElse {
                support.logFailure("Failed to remove display reward target '$target'.", it)
                ActionResult.Failure()
            }
        }
    }

    fun inspectDisplayRewardTarget(actor: Player?, type: DisplayRewardTargetType, target: String): ActionResult {
        support.requirePerms(actor, plugin.settings.displayReward.adminViewPermission, plugin.settings.displayReward.adminEditPermission, Perms.ADMIN_SETTINGS_EDIT)?.let {
            return it
        }
        val resolved = resolveRewardTarget(type, target)
            ?: return ActionResult.Rejected("admin.settings.display_reward_target_invalid")
        val mode = configuredMode(type, resolved) ?: when (type) {
            DisplayRewardTargetType.USER -> effectiveUserMode(resolved)
                ?: return ActionResult.Rejected("admin.settings.display_reward_target_missing")
            DisplayRewardTargetType.TRACK,
            DisplayRewardTargetType.GROUP -> return ActionResult.Rejected("admin.settings.display_reward_target_missing")
        }
        return inspectResult(type, resolved, mode)
    }

    fun inspectDisplayRewardTargetAsync(actor: Player?, type: DisplayRewardTargetType, target: String): CompletableFuture<ActionResult> {
        support.requirePerms(actor, plugin.settings.displayReward.adminViewPermission, plugin.settings.displayReward.adminEditPermission, Perms.ADMIN_SETTINGS_EDIT)?.let {
            return CompletableFuture.completedFuture(it)
        }
        val resolved = resolveRewardTarget(type, target)
            ?: return CompletableFuture.completedFuture(ActionResult.Rejected("admin.settings.display_reward_target_invalid"))
        configuredMode(type, resolved)?.let {
            return CompletableFuture.completedFuture(inspectResult(type, resolved, it))
        }
        if (type != DisplayRewardTargetType.USER) {
            return CompletableFuture.completedFuture(ActionResult.Rejected("admin.settings.display_reward_target_missing"))
        }
        val uuid = runCatching { UUID.fromString(resolved.key) }.getOrNull()
            ?: return CompletableFuture.completedFuture(ActionResult.Rejected("admin.settings.display_reward_target_invalid"))
        val modeFuture = if (plugin.hasMayorUsernamePrefix()) {
            plugin.mayorUsernamePrefix.displayRewardModeFor(uuid)
        } else {
            CompletableFuture.completedFuture(effectiveUserMode(resolved) ?: plugin.settings.displayReward.defaultMode)
        }
        return modeFuture
            .thenApply<ActionResult> { inspectResult(type, resolved, it) }
            .exceptionally {
                support.logFailure("Failed to inspect display reward target '$target'.", it)
                ActionResult.Failure()
            }
    }

    fun listDisplayRewardTargets(actor: Player?, type: DisplayRewardTargetType?): ActionResult {
        support.requirePerms(actor, plugin.settings.displayReward.adminViewPermission, plugin.settings.displayReward.adminEditPermission, Perms.ADMIN_SETTINGS_EDIT)?.let {
            return it
        }
        val entries = buildList {
            fun addAll(t: DisplayRewardTargetType, values: Map<String, DisplayRewardMode>) {
                values.toSortedMap().forEach { (target, mode) ->
                    add("${t.configKey}:${displayRewardTargetName(t, target)}=${mode.label()}")
                }
            }
            when (type) {
                DisplayRewardTargetType.TRACK -> addAll(type, plugin.settings.displayReward.targets.tracks)
                DisplayRewardTargetType.GROUP -> addAll(type, plugin.settings.displayReward.targets.groups)
                DisplayRewardTargetType.USER -> addAll(type, plugin.settings.displayReward.targets.users)
                null -> {
                    addAll(DisplayRewardTargetType.TRACK, plugin.settings.displayReward.targets.tracks)
                    addAll(DisplayRewardTargetType.GROUP, plugin.settings.displayReward.targets.groups)
                    addAll(DisplayRewardTargetType.USER, plugin.settings.displayReward.targets.users)
                }
            }
        }
        return ActionResult.Success(
            "admin.settings.display_reward_target_list",
            mapOf("targets" to entries.ifEmpty { listOf("(none)") }.joinToString(", "))
        )
    }

    private fun displayRewardPlaceholders(path: String, placeholders: Map<String, String>): Map<String, String> {
        if (placeholders.isEmpty()) return placeholders
        if (path != "display_reward.tag.display") return placeholders
        val value = placeholders["value"] ?: return placeholders
        return placeholders + ("value" to DisplayRewardText.previewMini(plugin.settings.applyTitleTokens(value)))
    }

    private fun configuredMode(type: DisplayRewardTargetType, target: ResolvedRewardTarget): DisplayRewardMode? =
        when (type) {
            DisplayRewardTargetType.TRACK -> plugin.settings.displayReward.targets.tracks[target.key]
            DisplayRewardTargetType.GROUP -> plugin.settings.displayReward.targets.groups[target.key]
            DisplayRewardTargetType.USER -> plugin.settings.displayReward.targets.users[target.key]
        }

    private fun effectiveUserMode(target: ResolvedRewardTarget): DisplayRewardMode? {
        val uuid = runCatching { UUID.fromString(target.key) }.getOrNull() ?: return null
        return plugin.settings.displayReward.modeFor(
            DisplayRewardSubject(
                uuid = uuid,
                name = target.displayName,
                tracks = emptySet(),
                groups = emptySet()
            )
        )
    }

    private fun inspectResult(type: DisplayRewardTargetType, target: ResolvedRewardTarget, mode: DisplayRewardMode): ActionResult.Success =
        ActionResult.Success(
            "admin.settings.display_reward_target_inspect",
            mapOf("type" to type.label, "target" to target.displayName, "mode" to mode.label())
        )

    private fun resolveRewardTarget(type: DisplayRewardTargetType, raw: String): ResolvedRewardTarget? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return when (type) {
            DisplayRewardTargetType.TRACK -> value
                .takeIf { DisplayRewardSettings.GROUP_NAME_REGEX.matches(it) }
                ?.lowercase()
                ?.takeIf { target ->
                    val provider = plugin.permissionGroups
                    !provider.available || provider.trackExists(target)
                }
                ?.let { ResolvedRewardTarget(it, it) }
            DisplayRewardTargetType.GROUP -> value
                .takeIf { DisplayRewardSettings.GROUP_NAME_REGEX.matches(it) }
                ?.lowercase()
                ?.takeIf { target ->
                    val provider = plugin.permissionGroups
                    !provider.available || provider.groupExists(target)
                }
                ?.let { ResolvedRewardTarget(it, it) }
            DisplayRewardTargetType.USER -> {
                val uuid = plugin.playerIdentities.knownUuidByName(value)
                uuid?.let { ResolvedRewardTarget(it.toString().lowercase(), displayRewardUserName(it, value.takeUnless(::isUuidString))) }
            }
        }
    }

    private fun displayRewardTargetName(type: DisplayRewardTargetType, key: String): String =
        if (type == DisplayRewardTargetType.USER) {
            val fallback = rememberedRewardTargetName(key)
            runCatching { displayRewardUserName(UUID.fromString(key), fallback) }.getOrDefault("Unknown player")
        } else {
            key
        }

    private fun displayRewardUserName(uuid: UUID, fallback: String?): String {
        val resolved = plugin.playerDisplayNames.resolve(uuid, fallback).plain.trim()
        return resolved
            .takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) && !isUuidString(it) }
            ?: "Unknown player"
    }

    private fun isUuidString(raw: String): Boolean =
        runCatching { UUID.fromString(raw.trim()) }.isSuccess

    private suspend fun rememberRewardTargetName(type: DisplayRewardTargetType, target: ResolvedRewardTarget) {
        if (type != DisplayRewardTargetType.USER) return
        val name = target.displayName.trim().takeIf {
            it.isNotBlank() && !it.equals("Unknown player", ignoreCase = true) && !isUuidString(it)
        } ?: return
        support.saveConfigValue("$TARGET_USER_NAMES_PATH.${target.key}", name)
    }

    private suspend fun forgetRewardTargetName(type: DisplayRewardTargetType, key: String) {
        if (type != DisplayRewardTargetType.USER) return
        support.saveConfigValue("$TARGET_USER_NAMES_PATH.$key", null)
    }

    private fun rememberedRewardTargetName(key: String): String? =
        plugin.config.getString("$TARGET_USER_NAMES_PATH.$key")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isUuidString(it) }

    private data class ResolvedRewardTarget(val key: String, val displayName: String)

    private suspend fun reloadSettingsOnly() {
        support.onMain {
            plugin.reloadSettingsOnly()
        }
    }

}
