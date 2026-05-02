package mayorSystem.monitoring

import mayorSystem.MayorPlugin
import mayorSystem.rewards.DeluxeTagsIntegration
import mayorSystem.rewards.DisplayRewardMode
import mayorSystem.rewards.DisplayRewardSubject
import mayorSystem.rewards.DisplayRewardTagId
import mayorSystem.rewards.DisplayRewardText
import mayorSystem.rewards.TagIconSettings
import mayorSystem.rewards.TagRewardSettings
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.node.types.PermissionNode
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Material
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

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
        out += checkDisplayReward()
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

    private fun checkDisplayReward(): List<HealthCheck> {
        val out = mutableListOf<HealthCheck>()
        val reward = plugin.settings.displayReward

        out += HealthCheck(
            id = "display_reward.state",
            severity = if (reward.enabled) HealthSeverity.OK else HealthSeverity.WARN,
            title = if (reward.enabled) "Display reward is enabled" else "Display reward is disabled",
            details = listOf(
                "default_mode=${reward.defaultMode.name}",
                "rank=${reward.rank.enabled}",
                "tag=${reward.tag.enabled}"
            )
        )

        val rawMode = plugin.config.getString("display_reward.default_mode")
        if (reward.configured && rawMode != null && DisplayRewardMode.parse(rawMode) == null) {
            out += HealthCheck(
                id = "display_reward.default_mode.invalid",
                severity = HealthSeverity.ERROR,
                title = "Reward Mode is invalid",
                details = listOf("display_reward.default_mode=$rawMode"),
                suggestion = "Use RANK, TAG, or BOTH."
            )
        }

        reward.targets.invalid.forEachIndexed { index, invalid ->
            out += HealthCheck(
                id = "display_reward.targets.invalid.$index",
                severity = HealthSeverity.ERROR,
                title = "Target reward override is invalid",
                details = listOf(
                    "path=${invalid.path}",
                    "target=${invalid.key}",
                    "value=${invalid.value}",
                    "reason=${invalid.reason}"
                ),
                suggestion = "Use target names with valid characters and mode RANK, TAG, or BOTH."
            )
        }
        if (reward.targets.legacyRanks.isNotEmpty()) {
            out += HealthCheck(
                id = "display_reward.targets.ranks",
                severity = HealthSeverity.WARN,
                title = "Rank target entries are using the older path",
                details = listOf("entries=${reward.targets.legacyRanks.size}"),
                suggestion = "Move entries to display_reward.targets.tracks when convenient."
            )
        }

        if (!reward.enabled) return out

        val rankConfigured = reward.rank.enabled && modesContainRank(reward)
        val tagConfigured = reward.tag.enabled && modesContainTag(reward)

        val configuredGroup = reward.rank.luckPermsGroup.trim()
        val validGroup = configuredGroup.isNotBlank() && GROUP_NAME_REGEX.matches(configuredGroup)
        if (rankConfigured && !validGroup) {
            out += HealthCheck(
                id = "luckperms.group.invalid",
                severity = HealthSeverity.ERROR,
                title = "Rank reward group is invalid",
                details = listOf("display_reward.rank.luckperms_group=${if (configuredGroup.isBlank()) "<blank>" else configuredGroup}"),
                suggestion = "Use only letters, numbers, _, -, . for the rank group."
            )
        }

        val lpPlugin = plugin.server.pluginManager.getPlugin("LuckPerms")
        val lpPluginOk = lpPlugin != null && lpPlugin.isEnabled
        if ((rankConfigured || tagConfigured) && !lpPluginOk) {
            out += HealthCheck(
                id = "luckperms.plugin.missing",
                severity = HealthSeverity.WARN,
                title = "LuckPerms is not enabled",
                details = listOf("rank_reward=$rankConfigured", "tag_access=$tagConfigured"),
                suggestion = "Enable LuckPerms for rank rewards and tag access."
            )
        }

        val lp = if (lpPluginOk) runCatching { LuckPermsProvider.get() }.getOrNull() else null
        if ((rankConfigured || tagConfigured) && lpPluginOk && lp == null) {
            out += HealthCheck(
                id = "luckperms.service.missing",
                severity = HealthSeverity.WARN,
                title = "LuckPerms service is unavailable",
                details = listOf("plugin=LuckPerms"),
                suggestion = "Restart the server and review LuckPerms startup messages."
            )
        } else if ((rankConfigured || tagConfigured) && lp != null) {
            out += HealthCheck(
                id = "luckperms.service.ok",
                severity = HealthSeverity.OK,
                title = "LuckPerms service available",
                details = listOf("rank_reward=$rankConfigured", "tag_access=$tagConfigured")
            )
        }

        val group = if (lp != null && validGroup) lp.groupManager.getGroup(configuredGroup) else null
        if (rankConfigured && validGroup && lp != null && group == null) {
            out += HealthCheck(
                id = "luckperms.group.missing",
                severity = HealthSeverity.WARN,
                title = "Rank reward group does not exist yet",
                details = listOf(
                    "group=$configuredGroup",
                    "auto_create=${reward.rank.autoCreateGroup}"
                ),
                suggestion = if (reward.rank.autoCreateGroup) {
                    "Use Sync Reward Now to create the rank group."
                } else {
                    "Create the LuckPerms group, then use Sync Reward Now."
                }
            )
        } else if (rankConfigured && validGroup && lp != null) {
            out += HealthCheck(
                id = "luckperms.group.ok",
                severity = HealthSeverity.OK,
                title = "Rank reward group exists",
                details = listOf("group=$configuredGroup")
            )
        }

        val tagId = reward.tag.deluxeTagId.trim()
        val validTagId = DisplayRewardTagId.isValid(tagId)
        val tagDisplayPlain = DisplayRewardText.plain(plugin.settings.applyTitleTokens(reward.tag.display))
        val tagDescriptionPlain = plugin.settings.applyTitleTokens(reward.tag.description)
        out += HealthCheck(
            id = "deluxetags.tag_id.stable",
            severity = if (validTagId) HealthSeverity.OK else HealthSeverity.ERROR,
            title = "Tag ID setting checked",
            details = listOf("configured=${tagId.isNotBlank()}", "uses_default=${tagId == TagRewardSettings.DEFAULT_TAG_ID}")
        )
        out += HealthCheck(
            id = "deluxetags.description.checked",
            severity = HealthSeverity.OK,
            title = "Tag description setting checked",
            details = listOf("description=$tagDescriptionPlain", "default=${plugin.settings.applyTitleTokens(TagRewardSettings.DEFAULT_TAG_DESCRIPTION)}")
        )
        out += HealthCheck(
            id = "deluxetags.display.placeholder_order",
            severity = HealthSeverity.OK,
            title = "Display placeholder order",
            details = listOf(
                "Use %deluxetags_tag% before LuckPerms prefix/rank in visible formats.",
                "If brace placeholders are required, use {deluxetags_tag}."
            )
        )
        out += HealthCheck(
            id = "display_reward.tag_before_rank",
            severity = HealthSeverity.OK,
            title = if (reward.tag.renderBeforeLuckPerms) {
                "MayorSystem shows tag before rank"
            } else {
                "MayorSystem tag placement is off"
            },
            details = listOf(
                if (reward.tag.renderBeforeLuckPerms) {
                    "Setting: On"
                } else {
                    "Setting: Off"
                },
                if (reward.tag.renderBeforeLuckPerms) {
                    "MayorSystem-rendered names include the tag before the rank."
                } else {
                    "External display formatting is expected to place the tag."
                }
            )
        )
        deluxeTagsVisiblePlaceholderCheck()?.let { out += it }
        if (tagConfigured && !validTagId) {
            out += HealthCheck(
                id = "deluxetags.tag_id.invalid",
                severity = HealthSeverity.ERROR,
                title = "Tag ID is invalid",
                details = listOf("The configured tag ID cannot be used."),
                suggestion = "Use letters, numbers, _, or - for the tag ID."
            )
        }

        val iconMaterial = TagIconSettings.materialOrNull(reward.tag.icon.material)
        if (iconMaterial == null) {
            out += HealthCheck(
                id = "display_reward.tag_icon.invalid",
                severity = HealthSeverity.ERROR,
                title = "Tag icon material is invalid",
                details = listOf("material=${reward.tag.icon.material}"),
                suggestion = "Use a Bukkit item material such as GOLDEN_HELMET."
            )
        } else {
            out += HealthCheck(
                id = "display_reward.tag_icon.ok",
                severity = HealthSeverity.OK,
                title = "Tag icon is usable",
                details = listOf(
                    "material=${iconMaterial.name}",
                    "custom_model_data=${reward.tag.icon.customModelData ?: "<none>"}",
                    "glint=${reward.tag.icon.glint}"
                )
            )
        }

        val deluxeTags = DeluxeTagsIntegration(plugin)
        val deluxeCaps = deluxeTags.capabilities()
        if (tagConfigured) {
            if (!deluxeCaps.present) {
                out += HealthCheck(
                    id = "deluxetags.plugin.missing",
                    severity = HealthSeverity.WARN,
                    title = "DeluxeTags is not enabled",
                    details = listOf("The tag reward is unavailable."),
                    suggestion = "Enable DeluxeTags, then use Sync Reward Now."
                )
            } else {
                out += HealthCheck(
                    id = "deluxetags.plugin.ok",
                    severity = HealthSeverity.OK,
                    title = "DeluxeTags is enabled",
                    details = listOf(
                        "api=${deluxeCaps.apiAvailable}",
                        "commands=${deluxeCaps.commandAvailable}"
                    )
                )
            }

            if (validTagId && deluxeCaps.present) {
                val tagExists = deluxeTags.hasTag(tagId)
                if (tagExists) {
                    val snapshot = deluxeTags.tagSnapshot(tagId)
                    out += HealthCheck(
                        id = "deluxetags.tag.ok",
                        severity = HealthSeverity.OK,
                        title = "Tag reward is available",
                          details = buildList {
                              snapshot?.let {
                                  add("display=${DisplayRewardText.plain(it.display)}")
                                  add("description=${it.description}")
                              }
                          }
                      )
                  } else {
                    out += HealthCheck(
                        id = "deluxetags.tag.missing",
                        severity = HealthSeverity.WARN,
                        title = "Tag reward is not loaded",
                          details = listOf(
                              "display=$tagDisplayPlain",
                              "description=$tagDescriptionPlain",
                              "auto_create=${reward.tag.autoCreateIfSupported}"
                          ),
                        suggestion = if (deluxeCaps.canCreateTags && reward.tag.autoCreateIfSupported) {
                            "Use Sync Reward Now to create and load the tag."
                        } else {
                            "Create the tag in DeluxeTags, then use Sync Reward Now."
                        }
                      )
                  }

                  val capabilitySeverity = if (deluxeCaps.canSelectTags && deluxeCaps.canClearTags) {
                      HealthSeverity.OK
                } else {
                    HealthSeverity.WARN
                }
                out += HealthCheck(
                    id = "deluxetags.tag_actions",
                    severity = capabilitySeverity,
                    title = "Tag actions available",
                    details = listOf(
                        "select=${deluxeCaps.canSelectTags}",
                        "clear=${deluxeCaps.canClearTags}",
                        "create=${deluxeCaps.canCreateTags}",
                        "per_tag_icon=${deluxeCaps.perTagIconSupported}"
                    ),
                    suggestion = if (capabilitySeverity == HealthSeverity.OK) null else "Select the tag manually in DeluxeTags, then use Sync Reward Now."
                )
            }
        }

        reward.targets.tracks.keys.forEach { trackName ->
            if (lp != null && lp.trackManager.getTrack(trackName) == null) {
                out += HealthCheck(
                    id = "luckperms.track.missing.$trackName",
                    severity = HealthSeverity.WARN,
                    title = "Reward track is not loaded",
                    details = listOf("track=$trackName"),
                    suggestion = "Create or load the LuckPerms track, or remove the override."
                )
            }
        }
        reward.targets.groups.keys.forEach { groupName ->
            if (lp != null && lp.groupManager.getGroup(groupName) == null) {
                out += HealthCheck(
                    id = "luckperms.target_group.missing.$groupName",
                    severity = HealthSeverity.WARN,
                    title = "Reward group is not loaded",
                    details = listOf("group=$groupName"),
                    suggestion = "Create or load the LuckPerms group, or remove the override."
                )
            }
        }

        if (!plugin.isReady() || !plugin.hasTermService()) {
            out += HealthCheck(
                id = "display_reward.mayor_state.skipped",
                severity = HealthSeverity.WARN,
                title = "Skipped current reward verification",
                details = listOf("reason=plugin not ready yet"),
                suggestion = "Run Health again after startup finishes."
            )
            return out
        }

        val currentTerm = plugin.termService.computeCached(Instant.now()).first
        if (currentTerm < 0) {
            out += HealthCheck(
                id = "display_reward.mayor_state.none",
                severity = HealthSeverity.OK,
                title = "No active term yet; reward check skipped"
            )
            return out
        }

        val mayorUuid = plugin.store.winner(currentTerm)
        if (mayorUuid == null) {
            out += HealthCheck(
                id = "display_reward.mayor_state.none",
                severity = HealthSeverity.WARN,
                title = "No elected mayor for current term",
                details = listOf("term=${currentTerm + 1}"),
                suggestion = "Complete the election, then run Health again."
            )
            return out
        }

        val mayorUser = lp?.userManager?.getUser(mayorUuid)
        val mayorName = playerName(mayorUuid)
        val subject = if (mayorUser != null) {
            subjectFromUser(mayorUuid, mayorUser, lp)
        } else {
            DisplayRewardSubject(mayorUuid, plugin.server.getOfflinePlayer(mayorUuid).name, emptySet(), emptySet())
        }
        val expectedMode = reward.modeFor(subject)
        out += HealthCheck(
            id = "display_reward.mayor_state.expected",
            severity = HealthSeverity.OK,
            title = "Current mayor reward expectation",
            details = listOf(
                "mayor=$mayorName",
                "mode=${expectedMode.name}",
                "rank=${expectedMode.includesRank() && reward.rank.enabled}",
                "tag=${expectedMode.includesTag() && reward.tag.enabled}"
            )
        )

        if ((expectedMode.includesRank() && reward.rank.enabled) || (expectedMode.includesTag() && reward.tag.enabled)) {
            if (lp != null && mayorUser == null) {
                out += HealthCheck(
                    id = "display_reward.mayor_state.unloaded",
                    severity = HealthSeverity.WARN,
                    title = "Current mayor LuckPerms user is not loaded",
                    details = listOf("mayor=$mayorName"),
                    suggestion = "Have the mayor join, then use Sync Reward Now."
                )
            }
        }

        if (expectedMode.includesRank() && reward.rank.enabled && validGroup && group != null && mayorUser != null) {
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
                    title = "Current mayor has the expected rank reward",
                    details = listOf(
                        "mayor=$mayorName",
                        "group=$configuredGroup",
                        "persistent=$hasPersistentNode",
                        "transient=$hasTransientNode"
                    )
                )
            } else {
                out += HealthCheck(
                    id = "luckperms.mayor_node.missing",
                    severity = HealthSeverity.ERROR,
                    title = "Current mayor is missing the rank reward",
                    details = listOf("mayor=$mayorName", "group=$configuredGroup"),
                    suggestion = "Use Sync Reward Now in Display Reward settings."
                )
            }
        }

        if (expectedMode.includesTag() && reward.tag.enabled && validTagId) {
            if (mayorUser != null) {
                val permission = reward.tag.permissionNode()
                val hasPermission = mayorUser.data()
                    .toCollection()
                    .filterIsInstance<PermissionNode>()
                    .any { it.permission.equals(permission, ignoreCase = true) } ||
                    mayorUser.transientData()
                        .toCollection()
                        .filterIsInstance<PermissionNode>()
                        .any { it.permission.equals(permission, ignoreCase = true) }

                out += HealthCheck(
                    id = if (hasPermission) "deluxetags.mayor_permission.ok" else "deluxetags.mayor_permission.missing",
                    severity = if (hasPermission) HealthSeverity.OK else HealthSeverity.WARN,
                    title = if (hasPermission) "Current mayor has the tag access" else "Current mayor is missing tag access",
                    details = listOf("mayor=$mayorName", "tag_access=${if (hasPermission) "present" else "missing"}"),
                    suggestion = if (hasPermission) null else "Use Sync Reward Now in Display Reward settings."
                )
            }

            if (deluxeCaps.present && deluxeCaps.apiAvailable) {
                val active = deluxeTags.activeTagId(mayorUuid)
                val activeMatches = active.equals(tagId, ignoreCase = true)
                out += HealthCheck(
                    id = if (activeMatches) "deluxetags.mayor_tag.ok" else "deluxetags.mayor_tag.missing",
                    severity = if (activeMatches) HealthSeverity.OK else HealthSeverity.WARN,
                    title = if (activeMatches) "Current mayor has the expected tag" else "Current mayor tag is not selected",
                    details = listOf("mayor=$mayorName", "tag_selected=$activeMatches"),
                    suggestion = if (activeMatches) null else "Use Sync Reward Now in Display Reward settings."
                )
            }
        }

        val trackedUuid = trackedRewardUuid()
        if (trackedUuid != null && trackedUuid != mayorUuid) {
            out += HealthCheck(
                id = "display_reward.tracked.stale",
                severity = HealthSeverity.WARN,
                title = "Tracked reward cleanup may be pending",
                details = listOf("tracked=${playerName(trackedUuid)}", "current_mayor=$mayorName"),
                suggestion = "Use Sync Reward Now to reconcile previous rewards."
            )
        } else {
            out += HealthCheck(
                id = "display_reward.tracked.ok",
                severity = HealthSeverity.OK,
                title = "Tracked reward cleanup state looks current"
            )
        }

        val deferred = plugin.config.getConfigurationSection("admin.display_reward.deferred_tag_cleanup")
        if (deferred != null && deferred.getKeys(false).isNotEmpty()) {
            out += HealthCheck(
                id = "deluxetags.cleanup.deferred",
                severity = HealthSeverity.WARN,
                title = "Tag cleanup is waiting for a player name",
                details = deferred.getKeys(false).map { uuid ->
                    val parsed = runCatching { UUID.fromString(uuid) }.getOrNull()
                    val name = parsed?.let {
                        playerName(it, plugin.config.getString("admin.display_reward.deferred_tag_cleanup.$uuid.last_known_name"))
                    } ?: "Unknown player"
                    name
                },
                suggestion = "Refresh the reward after the player joins or after their name is known."
            )
        }

        plugin.config.getString("admin.display_reward.last_deluxetags_failure.message")?.let { message ->
            out += HealthCheck(
                id = "deluxetags.last_failure",
                severity = HealthSeverity.WARN,
                title = "Last tag action needs attention",
                details = listOf(redactDisplayRewardInternals(message, tagId)),
                suggestion = "Review DeluxeTags setup, then use Sync Reward Now."
            )
        }

        return out
    }

    private fun modesContainRank(reward: mayorSystem.rewards.DisplayRewardSettings): Boolean =
        reward.defaultMode.includesRank() ||
            reward.targets.tracks.values.any { it.includesRank() } ||
            reward.targets.groups.values.any { it.includesRank() } ||
            reward.targets.users.values.any { it.includesRank() }

    private fun modesContainTag(reward: mayorSystem.rewards.DisplayRewardSettings): Boolean =
        reward.defaultMode.includesTag() ||
            reward.targets.tracks.values.any { it.includesTag() } ||
            reward.targets.groups.values.any { it.includesTag() } ||
            reward.targets.users.values.any { it.includesTag() }

    private fun deluxeTagsVisiblePlaceholderCheck(): HealthCheck? {
        val deluxe = plugin.server.pluginManager.getPlugin("DeluxeTags") ?: return null
        val file = deluxe.dataFolder.resolve("config.yml")
        if (!file.isFile) return null

        val cfg = YamlConfiguration.loadConfiguration(file)
        val identifierPlaceholder = "%deluxetags_identifier%"
        val identifierBrace = "{deluxetags_identifier}"
        val visiblePaths = listOf(
            "format_chat.format",
            "gui.tag_select_item.displayname",
            "gui.has_tag_item.displayname"
        )
        val internalNamePaths = visiblePaths.filter { path ->
            val value = cfg.getString(path).orEmpty()
            value.contains(identifierPlaceholder, ignoreCase = true) ||
                value.contains(identifierBrace, ignoreCase = true)
        }

        return if (internalNamePaths.isEmpty()) {
            HealthCheck(
                id = "deluxetags.visible_placeholder.ok",
                severity = HealthSeverity.OK,
                title = "Visible tag placeholders checked",
                details = listOf("Display text uses the visible tag placeholder.")
            )
        } else {
            HealthCheck(
                id = "deluxetags.visible_placeholder.needs_update",
                severity = HealthSeverity.WARN,
                title = "Visible tag placeholders need attention",
                details = internalNamePaths.map { "$it shows the tag name instead of the tag text." },
                suggestion = "Use %deluxetags_tag% for visible tag text; use {deluxetags_tag} where brace placeholders are required."
            )
        }
    }

    private fun subjectFromUser(uuid: UUID, user: User, lp: LuckPerms): DisplayRewardSubject {
        val groups = linkedSetOf<String>()
        user.data()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .mapTo(groups) { it.groupName }
        user.transientData()
            .toCollection()
            .filterIsInstance<InheritanceNode>()
            .mapTo(groups) { it.groupName }
        val normalizedGroups = groups.map { it.lowercase() }.toSet()
        val tracks = lp.trackManager.loadedTracks
            .filter { track -> track.groups.any { normalizedGroups.contains(it.lowercase()) } }
            .mapTo(linkedSetOf()) { it.name }
        return DisplayRewardSubject(
            uuid = uuid,
            name = plugin.server.getOfflinePlayer(uuid).name,
            tracks = tracks,
            groups = groups
        )
    }

    private fun playerName(uuid: UUID, fallback: String? = null): String {
        val resolved = runCatching { plugin.playerDisplayNames.resolve(uuid, fallback).plain.trim() }.getOrNull()
        return resolved
            ?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?: fallback?.trim()?.takeIf { it.isNotBlank() }
            ?: plugin.server.getPlayer(uuid)?.name
            ?: "Unknown player"
    }

    private fun redactUuids(raw: String): String =
        raw.replace(UUID_REGEX, "Unknown player")

    private fun redactDisplayRewardInternals(raw: String, tagId: String): String =
        redactUuids(raw)
            .let { text ->
                if (tagId.isBlank()) text else text.replace(tagId, "configured tag", ignoreCase = true)
            }
            .replace(TagRewardSettings.DEFAULT_TAG_ID, "configured tag", ignoreCase = true)

    private fun trackedRewardUuid(): UUID? =
        plugin.config.getString("admin.display_reward.tracked_uuid")
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: plugin.config.getString("admin.mayor_group.tracked_uuid")
                ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }

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
        val requested = plugin.config.getString("hologram.leaderboard.provider")?.lowercase()?.trim() ?: "auto"
        val backend = if (plugin.hasLeaderboardHologram()) plugin.leaderboardHologram.backendId() else "<unknown>"
        val decentEnabled = plugin.server.pluginManager.getPlugin("DecentHolograms")?.takeIf { it.isEnabled } != null
        val fancyEnabled = plugin.server.pluginManager.getPlugin("FancyHolograms")?.takeIf { it.isEnabled } != null
        val availableList = buildList {
            if (decentEnabled) add("DecentHolograms")
            if (fancyEnabled) add("FancyHolograms")
        }

        if (!enabled) {
            out += HealthCheck(
                id = "hologram.leaderboard.disabled",
                severity = HealthSeverity.OK,
                title = "Leaderboard hologram is disabled",
                details = listOf(
                    "available=${availableList.joinToString(", ").ifBlank { "none" }}",
                    "provider=$requested",
                    "backend=$backend",
                    "mode=$mode"
                )
            )
            return out
        }

        if (!decentEnabled && !fancyEnabled) {
            out += HealthCheck(
                id = "hologram.leaderboard.no_plugin",
                severity = HealthSeverity.ERROR,
                title = "Leaderboard hologram is enabled but no supported hologram plugin is installed",
                details = listOf(
                    "provider=$requested",
                    "backend=$backend",
                    "mode=$mode"
                ),
                suggestion = "Install/enable DecentHolograms or FancyHolograms, or set hologram.leaderboard.enabled=false."
            )
            return out
        }

        if (requested == "decentholograms" && !decentEnabled) {
            out += HealthCheck(
                id = "hologram.leaderboard.decent_missing",
                severity = HealthSeverity.ERROR,
                title = "hologram.leaderboard.provider=decentholograms but DecentHolograms is not enabled",
                details = listOf("backend=$backend", "mode=$mode"),
                suggestion = "Enable DecentHolograms or set hologram.leaderboard.provider=auto/fancyholograms."
            )
            return out
        }

        if (requested == "fancyholograms" && !fancyEnabled) {
            out += HealthCheck(
                id = "hologram.leaderboard.fancy_missing",
                severity = HealthSeverity.ERROR,
                title = "hologram.leaderboard.provider=fancyholograms but FancyHolograms is not enabled",
                details = listOf("backend=$backend", "mode=$mode"),
                suggestion = "Enable FancyHolograms or set hologram.leaderboard.provider=auto/decentholograms."
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
            details = listOf(
                "available=${availableList.joinToString(", ")}",
                "provider=$requested",
                "backend=$backend",
                "mode=$mode",
                loc
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

    private companion object {
        val GROUP_NAME_REGEX = Regex("^[A-Za-z0-9_.-]+$")
        val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

