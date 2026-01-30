package mayorSystem.service

import mayorSystem.MayorPlugin
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
        out += checkSellBonus()
        out += checkMayorNpc()
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
                suggestion = "Install Vault and an economy provider (EssentialsX Economy, CMI, etc.), or set apply.cost=0."
            )
        } else if (!available) {
            out += HealthCheck(
                id = "economy.provider_missing",
                severity = HealthSeverity.ERROR,
                title = "Vault found, but no economy provider is registered",
                suggestion = "Install/enable an economy plugin that hooks into Vault."
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

    private fun checkSellBonus(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()

        val enabled = plugin.config.getBoolean("sell_bonus.enabled", true)
        if (!enabled) {
            out += HealthCheck(
                id = "sell_bonus.disabled",
                severity = HealthSeverity.OK,
                title = "Sell bonuses are disabled"
            )
            return out
        }

        val econOk = plugin.economy.isAvailable()
        if (!econOk) {
            out += HealthCheck(
                id = "sell_bonus.no_economy",
                severity = HealthSeverity.ERROR,
                title = "Sell bonuses enabled, but no Vault economy is available",
                suggestion = "Install Vault + any Vault-compatible economy plugin (EssentialsX Economy, CMI, etc.), or disable sell_bonus.enabled."
            )
            return out
        }

        val commands = plugin.config.getStringList("sell_bonus.commands")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("sell", "sellall") }

        val statuses = runCatching { plugin.sellBonus.integrationStatuses() }.getOrDefault(emptyList())
        val apiActive = statuses.any { it.active }
        val details = buildList {
            add("economy=${plugin.economy.providerName() ?: "<unknown>"}")
            add("fallback=Vault balance-delta")
            add("commands=${commands.joinToString(", ")}")
            for (s in statuses) {
                val mark = if (s.active) "ACTIVE" else "inactive"
                add("${s.detectedPlugin}: $mark (${s.details})")
            }
        }

        out += HealthCheck(
            id = "sell_bonus.ok",
            severity = if (apiActive) HealthSeverity.OK else HealthSeverity.WARN,
            title = if (apiActive) "Sell bonus integration ready" else "Sell bonus integration using fallback",
            details = details,
            suggestion = if (apiActive) null else "If you want more reliable bonuses for GUI sells, install a supported sell plugin API (ShopGUIPlus, EconomyShopGUI, DSellSystem) or keep using the fallback."
        )

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
}
