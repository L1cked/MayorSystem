package mayorSystem.monitoring

import mayorSystem.MayorPlugin
import net.luckperms.api.LuckPerms
import net.luckperms.api.node.types.InheritanceNode
import org.bukkit.Material
import java.time.Instant
import java.time.OffsetDateTime

enum class HealthSeverity { OK, WARN, ERROR }

data class HealthCheck(
    val id: String,
    val severity: HealthSeverity,
    val title: String,
    val details: List<String> = emptyList(),
    val suggestion: String? = null
)

class HealthService(private val plugin: MayorPlugin) {

    fun run(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()

        out += checkConfigBasics()
        out += checkPerks()
        out += checkEconomy()
        out += checkLuckPermsGroup()
        out += checkSkyblockStyleAddon()
        out += checkMayorNpc()
        out += checkLeaderboardHologram()
        out += checkStoreSanity()

        return out
    }

    private fun checkConfigBasics(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()

        // term.first_term_start parse sanity (Settings may fallback silently).
        val raw = plugin.config.getString("term.first_term_start")
        val parsed = runCatching { raw?.let(OffsetDateTime::parse) }.getOrNull()
        if (raw.isNullOrBlank() || parsed == null) {
            out += HealthCheck(
                id = "config.first_term_start",
                severity = HealthSeverity.WARN,
                title = "term.first_term_start is missing/invalid",
                details = listOf("Current value: ${raw ?: "<missing>"}"),
                suggestion = "Set term.first_term_start to a valid OffsetDateTime (example: 2026-02-01T00:00:00-05:00)."
            )
        } else {
            out += HealthCheck(
                id = "config.first_term_start",
                severity = HealthSeverity.OK,
                title = "term.first_term_start OK",
                details = listOf(parsed.toString())
            )
        }

        // Duration keys
        val termLenRaw = plugin.config.getString("term.length") ?: ""
        val voteRaw = plugin.config.getString("term.vote_window") ?: ""
        val termLenOk = runCatching { java.time.Duration.parse(termLenRaw) }.isSuccess
        val voteOk = runCatching { java.time.Duration.parse(voteRaw) }.isSuccess

        if (!termLenOk) {
            out += HealthCheck(
                id = "config.term_length",
                severity = HealthSeverity.ERROR,
                title = "term.length is invalid",
                details = listOf("Current value: $termLenRaw"),
                suggestion = "Use ISO-8601 duration like P14D."
            )
        } else {
            out += HealthCheck(
                id = "config.term_length",
                severity = HealthSeverity.OK,
                title = "term.length OK",
                details = listOf(termLenRaw)
            )
        }

        if (!voteOk) {
            out += HealthCheck(
                id = "config.vote_window",
                severity = HealthSeverity.ERROR,
                title = "term.vote_window is invalid",
                details = listOf("Current value: $voteRaw"),
                suggestion = "Use ISO-8601 duration like P3D."
            )
        } else {
            out += HealthCheck(
                id = "config.vote_window",
                severity = HealthSeverity.OK,
                title = "term.vote_window OK",
                details = listOf(voteRaw)
            )
        }

        return out
    }

    private fun checkPerks(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()
        if (!plugin.config.getBoolean("perks.enabled", true)) {
            out += HealthCheck(
                id = "perks.enabled",
                severity = HealthSeverity.WARN,
                title = "Perks are disabled",
                suggestion = "Set perks.enabled=true if your server expects term perks."
            )
            return out
        }

        val preset = plugin.perks.presetPerks()
        if (preset.isEmpty()) {
            out += HealthCheck(
                id = "perks.none",
                severity = HealthSeverity.ERROR,
                title = "No enabled perks found",
                suggestion = "Enable at least one perk in perks.sections.*.perks.*.enabled."
            )
            return out
        }

        val now = Instant.now()
        val term = plugin.termService.computeCached(now).second
        val required = plugin.settings.perksAllowed(term)
        if (preset.size < required) {
            out += HealthCheck(
                id = "perks.count_mismatch",
                severity = HealthSeverity.WARN,
                title = "Fewer enabled perks than perks_per_term",
                details = listOf("Enabled perks: ${preset.size}", "Perks needed per candidate: $required"),
                suggestion = "Either enable more perks or lower term.perks_per_term."
            )
        } else {
            out += HealthCheck(
                id = "perks.count_mismatch",
                severity = HealthSeverity.OK,
                title = "Perk count looks OK",
                details = listOf("Enabled perks: ${preset.size}")
            )
        }

        // Icon validity (warn if invalid material keys in config).
        val sec = plugin.config.getConfigurationSection("perks.sections")
        if (sec != null) {
            for (sectionId in sec.getKeys(false)) {
                val perksSec = plugin.config.getConfigurationSection("perks.sections.$sectionId.perks") ?: continue
                for (perkId in perksSec.getKeys(false)) {
                    val base = "perks.sections.$sectionId.perks.$perkId"
                    if (!plugin.config.getBoolean("$base.enabled", true)) continue
                    val iconKey = (plugin.config.getString("$base.icon")
                        ?: plugin.config.getString("perks.sections.$sectionId.icon")
                        ?: "CHEST").uppercase()
                    val ok = runCatching { Material.valueOf(iconKey) }.isSuccess
                    if (!ok) {
                        out += HealthCheck(
                            id = "perks.icon.$sectionId.$perkId",
                            severity = HealthSeverity.WARN,
                            title = "Invalid perk icon material",
                            details = listOf("$sectionId/$perkId icon=$iconKey"),
                            suggestion = "Use a valid Bukkit Material name."
                        )
                    }
                }
            }
        }

        return out
    }

    private fun checkEconomy(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()
        val needEconomy = plugin.settings.applyCost > 0.0
        if (!needEconomy) {
            out += HealthCheck(
                id = "economy.not_needed",
                severity = HealthSeverity.OK,
                title = "Economy not required (apply.cost = 0)"
            )
            return out
        }

        val vaultPresent = plugin.server.pluginManager.getPlugin("Vault") != null
        val available = plugin.economy.isAvailable()

        if (!vaultPresent) {
            out += HealthCheck(
                id = "economy.vault_missing",
                severity = HealthSeverity.ERROR,
                title = "Vault plugin not found",
                details = listOf("apply.cost is ${plugin.settings.applyCost}"),
                suggestion = "Install Vault + a Vault-compatible economy plugin, or set apply.cost=0."
            )
        } else if (!available) {
            out += HealthCheck(
                id = "economy.provider_missing",
                severity = HealthSeverity.ERROR,
                title = "Vault found, but no economy provider is registered",
                suggestion = "Install/enable a Vault-compatible economy plugin."
            )
        } else {
            out += HealthCheck(
                id = "economy.ok",
                severity = HealthSeverity.OK,
                title = "Economy hook OK",
                details = listOf("provider=${plugin.economy.providerName() ?: "<unknown>"}")
            )
        }

        return out
    }

    private fun checkSkyblockStyleAddon(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()

        if (!plugin.config.getBoolean("perks.enabled", true)) {
            out += HealthCheck(
                id = "skyblock_style.perks_disabled",
                severity = HealthSeverity.OK,
                title = "Perks are disabled"
            )
            return out
        }

        val sectionBase = "perks.sections.skyblock_style"
        val configured = plugin.config.contains(sectionBase)
        if (!configured) {
            out += HealthCheck(
                id = "skyblock_style.not_configured",
                severity = HealthSeverity.OK,
                title = "Skyblock-style perks not configured"
            )
            return out
        }

        val enabled = plugin.config.getBoolean("$sectionBase.enabled", true)
        if (!enabled) {
            out += HealthCheck(
                id = "skyblock_style.disabled",
                severity = HealthSeverity.OK,
                title = "Skyblock-style perks are disabled"
            )
            return out
        }

        val addonName = plugin.perks.skyblockStyleAddonName()
        val available = addonName != null

        if (!available) {
            out += HealthCheck(
                id = "skyblock_style.missing",
                severity = HealthSeverity.WARN,
                title = "Skyblock-style perks enabled, but addon is missing",
                details = listOf("section=skyblock_style"),
                suggestion = "Install SystemSkyblockStyleAddon (or SystemSkyblockStyleSystem) or disable the skyblock_style section."
            )
            return out
        }

        out += HealthCheck(
            id = "skyblock_style.ok",
            severity = HealthSeverity.OK,
            title = "Skyblock-style addon detected",
            details = listOf("plugin=$addonName", "section=skyblock_style")
        )

        return out
    }

    private fun checkLuckPermsGroup(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()

        if (!plugin.settings.usernameGroupEnabled) {
            out += HealthCheck(
                id = "luckperms.group.disabled",
                severity = HealthSeverity.OK,
                title = "Mayor LuckPerms group integration is disabled"
            )
            return out
        }

        val configuredGroup = plugin.settings.usernameGroup.trim()
        if (configuredGroup.isBlank() || !GROUP_NAME_REGEX.matches(configuredGroup)) {
            out += HealthCheck(
                id = "luckperms.group.invalid",
                severity = HealthSeverity.ERROR,
                title = "Configured LuckPerms group name is invalid",
                details = listOf("title.username_group=${if (configuredGroup.isBlank()) "<blank>" else configuredGroup}"),
                suggestion = "Use only letters, numbers, _, -, . for title.username_group."
            )
            return out
        }

        val lpPlugin = plugin.server.pluginManager.getPlugin("LuckPerms")
        if (lpPlugin == null || !lpPlugin.isEnabled) {
            out += HealthCheck(
                id = "luckperms.plugin.missing",
                severity = HealthSeverity.ERROR,
                title = "LuckPerms plugin is not enabled",
                details = listOf("title.username_group=$configuredGroup"),
                suggestion = "Install/enable LuckPerms, or disable title.username_group_enabled."
            )
            return out
        }

        val lp = plugin.server.servicesManager.getRegistration(LuckPerms::class.java)?.provider
        if (lp == null) {
            out += HealthCheck(
                id = "luckperms.service.missing",
                severity = HealthSeverity.ERROR,
                title = "LuckPerms API service is unavailable",
                details = listOf("plugin=LuckPerms (enabled)"),
                suggestion = "Reload/restart server and verify no startup errors from LuckPerms."
            )
            return out
        }

        out += HealthCheck(
            id = "luckperms.service.ok",
            severity = HealthSeverity.OK,
            title = "LuckPerms service available",
            details = listOf("title.username_group=$configuredGroup")
        )

        val group = lp.groupManager.getGroup(configuredGroup)
        if (group == null) {
            out += HealthCheck(
                id = "luckperms.group.missing",
                severity = HealthSeverity.WARN,
                title = "Configured LuckPerms group does not exist yet",
                details = listOf("group=$configuredGroup"),
                suggestion = "Use Sync Group Now or elect/re-elect mayor to trigger auto-create."
            )
        } else {
            out += HealthCheck(
                id = "luckperms.group.ok",
                severity = HealthSeverity.OK,
                title = "Configured LuckPerms group exists",
                details = listOf("group=$configuredGroup")
            )
        }

        if (!plugin.isReady() || !plugin.hasTermService()) {
            out += HealthCheck(
                id = "luckperms.mayor_node.skipped",
                severity = HealthSeverity.WARN,
                title = "Skipped elected mayor node verification",
                details = listOf("reason=plugin not ready yet"),
                suggestion = "Run Health again once startup finishes."
            )
            return out
        }

        val currentTerm = plugin.termService.computeCached(Instant.now()).first
        if (currentTerm < 0) {
            out += HealthCheck(
                id = "luckperms.mayor_node.none",
                severity = HealthSeverity.OK,
                title = "No active term yet; mayor node check skipped"
            )
            return out
        }

        val mayorUuid = plugin.store.winner(currentTerm)
        if (mayorUuid == null) {
            out += HealthCheck(
                id = "luckperms.mayor_node.none",
                severity = HealthSeverity.WARN,
                title = "No elected mayor for current term",
                details = listOf("term=${currentTerm + 1}"),
                suggestion = "Complete election/finalize term, then re-run Health."
            )
            return out
        }

        if (group == null) {
            out += HealthCheck(
                id = "luckperms.mayor_node.skipped",
                severity = HealthSeverity.WARN,
                title = "Skipped elected mayor node verification",
                details = listOf("reason=group '$configuredGroup' missing", "mayor_uuid=$mayorUuid"),
                suggestion = "Create the group (or trigger auto-create) then run Health again."
            )
            return out
        }

        val mayorUser = lp.userManager.getUser(mayorUuid)
        if (mayorUser == null) {
            out += HealthCheck(
                id = "luckperms.mayor_node.unloaded",
                severity = HealthSeverity.WARN,
                title = "Elected mayor LuckPerms user is not loaded",
                details = listOf("mayor_uuid=$mayorUuid", "group=$configuredGroup"),
                suggestion = "Have the mayor join once, then click Sync Group Now and re-run Health."
            )
            return out
        }

        val hasPersistentNode = mayorUser.data()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .any { it.groupName.equals(configuredGroup, ignoreCase = true) }
        val hasTransientNode = mayorUser.transientData()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .any { it.groupName.equals(configuredGroup, ignoreCase = true) }
        val hasNode = hasPersistentNode || hasTransientNode

        if (hasNode) {
            out += HealthCheck(
                id = "luckperms.mayor_node.ok",
                severity = HealthSeverity.OK,
                title = "Elected mayor has expected LuckPerms group node",
                details = listOf(
                    "mayor_uuid=$mayorUuid",
                    "group=$configuredGroup",
                    "persistent=$hasPersistentNode",
                    "transient=$hasTransientNode"
                )
            )
        } else {
            out += HealthCheck(
                id = "luckperms.mayor_node.missing",
                severity = HealthSeverity.ERROR,
                title = "Elected mayor is missing expected LuckPerms group node",
                details = listOf("mayor_uuid=$mayorUuid", "group=$configuredGroup"),
                suggestion = "Click Sync Group Now in Admin Settings > Mayor Group."
            )
        }

        return out
    }

    private fun checkMayorNpc(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()

        val enabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        val requested = plugin.config.getString("npc.mayor.provider")?.lowercase()?.trim() ?: "auto"
        val backend = plugin.config.getString("npc.mayor.backend")?.lowercase()?.trim() ?: "<unknown>"

        val citizens = plugin.server.pluginManager.getPlugin("Citizens")?.takeIf { it.isEnabled } != null
        val fancy = (plugin.server.pluginManager.getPlugin("FancyNpcs")?.takeIf { it.isEnabled }
            ?: plugin.server.pluginManager.getPlugin("FancyNPCs")?.takeIf { it.isEnabled }) != null

        val availableList = buildList {
            if (citizens) add("Citizens")
            if (fancy) add("FancyNpcs")
        }

        if (!enabled) {
            out += HealthCheck(
                id = "npc.mayor.disabled",
                severity = HealthSeverity.OK,
                title = "Mayor NPC is disabled",
                details = listOf("provider=$requested", "backend=$backend")
            )
            return out
        }

        // Enabled:
        if (availableList.isEmpty()) {
            out += HealthCheck(
                id = "npc.mayor.no_provider",
                severity = HealthSeverity.ERROR,
                title = "Mayor NPC enabled but no supported NPC plugin is installed",
                details = listOf("provider=$requested", "backend=$backend"),
                suggestion = "Install Citizens or FancyNpcs (then restart), or set npc.mayor.enabled=false."
            )
            return out
        }

        // Requested provider explicitly but missing
        if (requested == "citizens" && !citizens) {
            out += HealthCheck(
                id = "npc.mayor.citizens_missing",
                severity = HealthSeverity.ERROR,
                title = "npc.mayor.provider=citizens but Citizens is not enabled",
                details = listOf("available=${availableList.joinToString(", ")}", "backend=$backend"),
                suggestion = "Enable Citizens or set npc.mayor.provider=auto/fancynpcs."
            )
            return out
        }
        if (requested == "fancynpcs" && !fancy) {
            out += HealthCheck(
                id = "npc.mayor.fancynpcs_missing",
                severity = HealthSeverity.ERROR,
                title = "npc.mayor.provider=fancynpcs but FancyNpcs is not enabled",
                details = listOf("available=${availableList.joinToString(", ")}", "backend=$backend"),
                suggestion = "Enable FancyNpcs or set npc.mayor.provider=auto/citizens."
            )
            return out
        }

        out += HealthCheck(
            id = "npc.mayor.ok",
            severity = HealthSeverity.OK,
            title = "Mayor NPC provider available",
            details = listOf(
                "available=${availableList.joinToString(", ")}",
                "provider=$requested",
                "backend=$backend"
            )
        )
        return out
    }

    private fun checkLeaderboardHologram(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()

        val enabled = plugin.config.getBoolean("hologram.leaderboard.enabled", false)
        val mode = plugin.config.getString("showcase.mode")?.uppercase()?.trim() ?: "SWITCHING"
        val decentEnabled = plugin.server.pluginManager.getPlugin("DecentHolograms")?.isEnabled == true

        if (!enabled) {
            out += HealthCheck(
                id = "hologram.leaderboard.disabled",
                severity = HealthSeverity.OK,
                title = "Leaderboard hologram is disabled",
                details = listOf("mode=$mode")
            )
            return out
        }

        if (!decentEnabled) {
            out += HealthCheck(
                id = "hologram.leaderboard.no_plugin",
                severity = HealthSeverity.ERROR,
                title = "DecentHolograms is not enabled",
                details = listOf("mode=$mode"),
                suggestion = "Install/enable DecentHolograms or set hologram.leaderboard.enabled=false."
            )
            return out
        }

        val loc = if (mode == "SWITCHING") {
            val worldName = plugin.config.getString("npc.mayor.world") ?: "<unset>"
            "npc.mayor.world=$worldName"
        } else {
            val worldName = plugin.config.getString("hologram.leaderboard.world") ?: "<unset>"
            "hologram.world=$worldName"
        }

        out += HealthCheck(
            id = "hologram.leaderboard.ok",
            severity = HealthSeverity.OK,
            title = "Leaderboard hologram ready",
            details = listOf("mode=$mode", loc)
        )
        return out
    }

    private fun checkStoreSanity(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()
        val now = Instant.now()
        val electionTerm = plugin.termService.computeCached(now).second
        val open = plugin.termService.isElectionOpen(now, electionTerm)
        if (open) {
            val cands = plugin.store.candidates(electionTerm, includeRemoved = false)
            if (cands.isEmpty()) {
                out += HealthCheck(
                    id = "store.no_candidates",
                    severity = HealthSeverity.WARN,
                    title = "Election is open, but there are no candidates",
                    suggestion = "This is OK for fresh terms, but check if applications are being blocked unexpectedly."
                )
            } else {
                out += HealthCheck(
                    id = "store.candidates_present",
                    severity = HealthSeverity.OK,
                    title = "Candidates present for open election",
                    details = listOf("count=${cands.size}")
                )
            }
        } else {
            out += HealthCheck(
                id = "store.election_closed",
                severity = HealthSeverity.OK,
                title = "Election currently closed"
            )
        }
        return out
    }

    private companion object {
        val GROUP_NAME_REGEX = Regex("^[A-Za-z0-9_.-]+$")
    }
}

