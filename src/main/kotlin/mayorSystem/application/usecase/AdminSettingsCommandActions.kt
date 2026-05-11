package mayorSystem.application.usecase

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import mayorSystem.security.Perms
import mayorSystem.service.ActionResult
import org.bukkit.entity.Player
import java.util.logging.Level

/**
 * Command-facing settings and reload actions.
 *
 * Preserves current config write/audit behavior, reload validation, enable/pause
 * side effects, and permission fallbacks while moving settings workflows out of
 * the broad AdminActions facade.
 */
class AdminSettingsCommandActions(private val plugin: MayorPlugin) {
    private val support = AdminActionSupport(plugin)

    private companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_RELOAD = "reload"
        private const val FIRST_TERM_START_PATH = "term.first_term_start"
    }

    suspend fun updateConfig(
        actor: Player?,
        path: String,
        value: Any?,
        reload: Boolean = true,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_SETTINGS_EDIT)?.let { return it }
        return support.serializedResult(if (reload) KEY_RELOAD else KEY_SETTINGS) {
            runCatching {
                support.updateConfig(actor, path, value, reload)
                ActionResult.Success(successKey, successPlaceholders)
            }.getOrElse {
                support.logFailure("Failed to update config '$path'.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun updateSettingsConfig(
        actor: Player?,
        path: String,
        value: Any?,
        successKey: String,
        successPlaceholders: Map<String, String> = emptyMap()
    ): ActionResult {
        val denied = if (path == "public_enabled") {
            support.requirePerms(actor, Perms.ADMIN_SETTINGS_EDIT, Perms.ADMIN_SYSTEM_TOGGLE)
        } else {
            support.requirePerms(actor, Perms.ADMIN_SETTINGS_EDIT)
        }
        denied?.let { return it }
        validateSettingsConfigUpdate(path)?.let { return it }
        return support.serializedResult(KEY_SETTINGS) {
            runCatching {
                val wasEnabled = if (path == "enabled") support.onMain { plugin.settings.enabled } else null
                val wasPaused = if (path == "pause.enabled") support.onMain { plugin.settings.pauseEnabled } else null
                support.updateConfig(actor, path, value, reload = false)
                reloadSettingsOnly()
                if (path == "enabled" && wasEnabled != null) {
                    support.onMain { handleEnabledToggle(actor, wasEnabled, plugin.settings.enabled) }
                }
                if (path == "pause.enabled" && wasPaused != null) {
                    val nowPaused = support.onMain { plugin.settings.pauseEnabled }
                    handlePauseToggle(actor, wasPaused, nowPaused)
                }
                ActionResult.Success(successKey, successPlaceholders)
            }.getOrElse {
                support.logFailure("Failed to update settings '$path'.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun updateConfig(actor: Player?, path: String, value: Any?, reload: Boolean = true): ActionResult =
        updateConfig(actor, path, value, reload, "admin.settings.config_updated")

    suspend fun updateSettingsConfig(actor: Player?, path: String, value: Any?): ActionResult =
        updateSettingsConfig(actor, path, value, "admin.settings.updated")

    suspend fun updateSettingsConfig(path: String, value: Any?): ActionResult =
        updateSettingsConfig(null, path, value, "admin.settings.updated")

    suspend fun toggleAllowVoteChange(actor: Player?): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_SETTINGS_EDIT)?.let { return it }
        return support.serializedResult(KEY_SETTINGS) {
            runCatching {
                val next = support.onMain { !plugin.settings.allowVoteChange }
                support.updateConfig(actor, "election.allow_vote_change", next, reload = false)
                reloadSettingsOnly()
                ActionResult.Success(
                    "admin.settings.allow_vote_change_set",
                    mapOf("value" to next.toString())
                )
            }.getOrElse {
                support.logFailure("Failed to toggle election.allow_vote_change.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun toggleStepdownAllowReapply(actor: Player?): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_SETTINGS_EDIT)?.let { return it }
        return support.serializedResult(KEY_SETTINGS) {
            runCatching {
                val next = support.onMain { !plugin.settings.stepdownAllowReapply }
                support.updateConfig(actor, "election.stepdown.allow_reapply", next, reload = false)
                reloadSettingsOnly()
                ActionResult.Success(
                    "admin.settings.stepdown_reapply_set",
                    mapOf("value" to next.toString())
                )
            }.getOrElse {
                support.logFailure("Failed to toggle election.stepdown.allow_reapply.", it)
                ActionResult.Failure()
            }
        }
    }

    suspend fun reload(actor: Player?): ActionResult {
        support.requirePerms(actor, Perms.ADMIN_MAINTENANCE_RELOAD, Perms.ADMIN_SETTINGS_RELOAD)?.let { return it }
        return support.serializedResult(KEY_RELOAD) {
            try {
                support.log(actor, "RELOAD")
                val ok = plugin.reloadEverythingVerified()
                if (ok) {
                    ActionResult.Success("admin.settings.reloaded")
                } else {
                    ActionResult.Failure()
                }
            } catch (ex: Throwable) {
                support.logFailure("Failed to reload MayorSystem.", ex)
                ActionResult.Failure()
            }
        }
    }

    private suspend fun reloadSettingsOnly() {
        support.onMain {
            plugin.reloadSettingsOnly()
        }
    }

    private fun handleEnabledToggle(actor: Player?, wasEnabled: Boolean, nowEnabled: Boolean) {
        if (wasEnabled == nowEnabled) return
        val affectsPerks = plugin.settings.enableOptions.contains(SystemGateOption.PERKS)
        val affectsNpc = plugin.settings.enableOptions.contains(SystemGateOption.MAYOR_NPC)
        val affectsSchedule = plugin.settings.enableOptions.contains(SystemGateOption.SCHEDULE)
        if (!nowEnabled) {
            if (affectsPerks) clearActivePerks()
            if (affectsNpc && plugin.hasMayorNpc()) plugin.mayorNpc.forceUpdateMayorForTerm(-1)
            support.log(actor, "SYSTEM_DISABLED")
            return
        }

        if (plugin.hasTermService() && affectsSchedule) {
            val term = plugin.termService.computeNow().first
            if (affectsPerks) plugin.perks.rebuildActiveEffectsForTerm(term)
            plugin.termService.tick()
        } else if (plugin.hasTermService() && affectsPerks) {
            val term = plugin.termService.computeNow().first
            plugin.perks.rebuildActiveEffectsForTerm(term)
        }
        if (affectsNpc && plugin.hasMayorNpc()) {
            plugin.mayorNpc.forceUpdateMayor()
        }
        support.log(actor, "SYSTEM_ENABLED")
    }

    private fun clearActivePerks() {
        val term = if (plugin.hasTermService()) plugin.termService.computeNow().first else -1
        if (term >= 0) {
            runCatching { plugin.perks.clearPerks(term) }
                .onFailure { plugin.logger.log(Level.WARNING, "Failed to clear active perks for term $term.", it) }
        }
        plugin.perks.rebuildActiveEffectsForTerm(-1)
    }

    private suspend fun handlePauseToggle(actor: Player?, wasPaused: Boolean, nowPaused: Boolean) {
        if (wasPaused == nowPaused) return
        val now = OffsetDateTime.now().toInstant()
        val affectsSchedule = support.onMain { plugin.settings.pauseOptions.contains(SystemGateOption.SCHEDULE) }
        val affectsPerks = support.onMain { plugin.settings.pauseOptions.contains(SystemGateOption.PERKS) }
        val affectsNpc = support.onMain { plugin.settings.pauseOptions.contains(SystemGateOption.MAYOR_NPC) }
        if (nowPaused) {
            if (affectsSchedule) startPauseIfNeeded(now)
            if (affectsPerks) support.onMain { clearActivePerks() }
            if (affectsNpc) support.onMain { if (plugin.hasMayorNpc()) plugin.mayorNpc.forceUpdateMayorForTerm(-1) }
            support.log(actor, "SYSTEM_PAUSED")
            return
        }

        if (affectsSchedule) {
            endPauseIfNeeded(now)
            support.onMain {
                if (plugin.hasTermService()) {
                    plugin.termService.invalidateScheduleCache()
                }
            }
        }
        if (affectsPerks) {
            support.onMain {
                if (plugin.hasTermService()) {
                    val term = if (plugin.settings.isBlocked(SystemGateOption.PERKS)) -1 else plugin.termService.computeNow().first
                    plugin.perks.rebuildActiveEffectsForTerm(term)
                }
            }
        }
        if (affectsNpc) {
            support.onMain {
                if (plugin.hasMayorNpc()) {
                    plugin.mayorNpc.forceUpdateMayor()
                }
            }
        }
        support.log(actor, "SYSTEM_RESUMED")
    }

    private suspend fun startPauseIfNeeded(now: Instant) {
        val startedRaw = support.onMain { plugin.config.getString("admin.pause.started_at") }
        if (!startedRaw.isNullOrBlank()) return
        support.saveConfigValue("admin.pause.started_at", now.toString())
    }

    private suspend fun endPauseIfNeeded(now: Instant) {
        val startedRaw = support.onMain { plugin.config.getString("admin.pause.started_at") } ?: return
        val startedAt = runCatching { Instant.parse(startedRaw) }.getOrNull() ?: return
        val deltaMs = Duration.between(startedAt, now).toMillis().coerceAtLeast(0)
        val totalMs = support.onMain { plugin.config.getLong("admin.pause.total_ms", 0L).coerceAtLeast(0) }
        support.saveConfigValues(
            mapOf(
                "admin.pause.total_ms" to totalMs + deltaMs,
                "admin.pause.started_at" to null
            )
        )
    }

    private fun validateSettingsConfigUpdate(path: String): ActionResult? {
        if (path == "public_enabled" && !plugin.settings.enabled) {
            return ActionResult.Rejected("admin.system.master_off")
        }
        if (path != FIRST_TERM_START_PATH) return null
        if (!plugin.hasTermService()) return null
        val currentTerm = runCatching { plugin.termService.computeNow().first }.getOrNull() ?: return null
        if (currentTerm <= 0) return null
        return ActionResult.Rejected(
            "admin.settings.first_term_start_locked",
            mapOf("term" to (currentTerm + 1).toString())
        )
    }

}
