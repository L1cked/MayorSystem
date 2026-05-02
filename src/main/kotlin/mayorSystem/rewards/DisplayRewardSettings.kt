package mayorSystem.rewards

import mayorSystem.security.Perms
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

data class DisplayRewardSettings(
    val configured: Boolean,
    val enabled: Boolean,
    val defaultMode: DisplayRewardMode,
    val adminViewPermission: String,
    val adminEditPermission: String,
    val rank: RankRewardSettings,
    val tag: TagRewardSettings,
    val targets: DisplayRewardTargets
) {
    fun modeFor(subject: DisplayRewardSubject): DisplayRewardMode =
        targets.resolve(subject, defaultMode)

    companion object {
        fun from(
            cfg: FileConfiguration,
            usernameGroupEnabled: Boolean,
            usernameGroup: String,
            log: Logger? = null
        ): DisplayRewardSettings {
            val configured = cfg.isConfigurationSection("display_reward")
            if (!configured) {
                return DisplayRewardSettings(
                    configured = false,
                    enabled = usernameGroupEnabled,
                    defaultMode = DisplayRewardMode.RANK,
                    adminViewPermission = Perms.ADMIN_REWARD_VIEW,
                    adminEditPermission = Perms.ADMIN_REWARD_EDIT,
                    rank = RankRewardSettings(
                        enabled = usernameGroupEnabled,
                        luckPermsGroup = usernameGroup,
                        autoCreateGroup = true
                    ),
                    tag = TagRewardSettings(),
                    targets = DisplayRewardTargets()
                )
            }

            val defaultModeRaw = cfg.getString("display_reward.default_mode")
            val defaultMode = DisplayRewardMode.parse(defaultModeRaw) ?: run {
                if (!defaultModeRaw.isNullOrBlank()) {
                    log?.warning("Unknown display_reward.default_mode '$defaultModeRaw'. Using RANK.")
                }
                DisplayRewardMode.RANK
            }

            val rankGroup = cfg.getString("display_reward.rank.luckperms_group")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: usernameGroup

            val tagDescription = cfg.getString("display_reward.tag.description")
                ?.takeIf { it.isNotBlank() }
                ?.takeUnless { it.equals("Only for the worthy.", ignoreCase = true) }
                ?: TagRewardSettings.DEFAULT_TAG_DESCRIPTION

            return DisplayRewardSettings(
                configured = true,
                enabled = cfg.getBoolean("display_reward.enabled", usernameGroupEnabled),
                defaultMode = defaultMode,
                adminViewPermission = cfg.getString("display_reward.permissions.admin_view")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: Perms.ADMIN_REWARD_VIEW,
                adminEditPermission = cfg.getString("display_reward.permissions.admin_edit")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: Perms.ADMIN_REWARD_EDIT,
                rank = RankRewardSettings(
                    enabled = cfg.getBoolean("display_reward.rank.enabled", usernameGroupEnabled),
                    luckPermsGroup = rankGroup,
                    autoCreateGroup = cfg.getBoolean("display_reward.rank.auto_create_group", true)
                ),
                tag = TagRewardSettings(
                    enabled = cfg.getBoolean("display_reward.tag.enabled", false),
                    deluxeTagId = cfg.getString("display_reward.tag.deluxe_tag_id")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: TagRewardSettings.DEFAULT_TAG_ID,
                    display = cfg.getString("display_reward.tag.display")
                        ?.takeIf { it.isNotBlank() }
                        ?: TagRewardSettings.DEFAULT_TAG_DISPLAY,
                    description = tagDescription,
                    order = cfg.getInt("display_reward.tag.order", 100),
                    permission = cfg.getString("display_reward.tag.permission")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                    autoCreateIfSupported = cfg.getBoolean("display_reward.tag.auto_create_if_supported", true),
                    selectWhenApplied = cfg.getBoolean("display_reward.tag.select_when_applied", true),
                    clearWhenRemoved = cfg.getBoolean("display_reward.tag.clear_when_removed", true),
                    renderBeforeLuckPerms = cfg.getBoolean("display_reward.tag.render_before_luckperms", true),
                    icon = TagIconSettings(
                        material = cfg.getString("display_reward.tag.icon.material")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: TagIconSettings.DEFAULT_MATERIAL,
                        customModelData = if (cfg.isSet("display_reward.tag.icon.custom_model_data")) {
                            cfg.getInt("display_reward.tag.icon.custom_model_data")
                        } else {
                            null
                        },
                        glint = cfg.getBoolean("display_reward.tag.icon.glint", false)
                    )
                ),
                targets = parseTargets(cfg, log)
            )
        }

        private fun parseTargets(cfg: FileConfiguration, log: Logger?): DisplayRewardTargets {
            val invalid = mutableListOf<InvalidDisplayRewardTarget>()
            val groups = parseTargetSection(
                cfg.getConfigurationSection("display_reward.targets.groups"),
                "display_reward.targets.groups",
                GROUP_NAME_REGEX,
                invalid,
                log
            )
            val tracks = parseTargetSection(
                cfg.getConfigurationSection("display_reward.targets.tracks"),
                "display_reward.targets.tracks",
                GROUP_NAME_REGEX,
                invalid,
                log
            )
            val legacyRanks = parseTargetSection(
                cfg.getConfigurationSection("display_reward.targets.ranks"),
                "display_reward.targets.ranks",
                GROUP_NAME_REGEX,
                invalid,
                log
            )
            val users = parseTargetSection(
                cfg.getConfigurationSection("display_reward.targets.users"),
                "display_reward.targets.users",
                USER_UUID_REGEX,
                invalid,
                log
            )
            val mergedTracks = linkedMapOf<String, DisplayRewardMode>()
            mergedTracks.putAll(legacyRanks)
            mergedTracks.putAll(tracks)
            return DisplayRewardTargets(
                tracks = mergedTracks,
                groups = groups,
                users = users,
                invalid = invalid,
                legacyRanks = legacyRanks
            )
        }

        private fun parseTargetSection(
            section: ConfigurationSection?,
            path: String,
            keyRegex: Regex,
            invalid: MutableList<InvalidDisplayRewardTarget>,
            log: Logger?
        ): Map<String, DisplayRewardMode> {
            if (section == null) return emptyMap()

            val out = linkedMapOf<String, DisplayRewardMode>()
            for (key in section.getKeys(true).sorted()) {
                if (section.isConfigurationSection(key)) continue
                val rawValue = section.getString(key)?.trim().orEmpty()
                val mode = DisplayRewardMode.parse(rawValue)
                val normalizedKey = key.trim().lowercase()
                when {
                    normalizedKey.isBlank() || !keyRegex.matches(key) -> {
                        invalid += InvalidDisplayRewardTarget(path, key, rawValue, "invalid target")
                    }
                    mode == null -> {
                        invalid += InvalidDisplayRewardTarget(path, key, rawValue, "invalid mode")
                    }
                    else -> {
                        out[normalizedKey] = mode
                    }
                }
            }
            if (invalid.isNotEmpty()) {
                log?.warning("Invalid display_reward target override(s) found; run Health for details.")
            }
            return out
        }

        val GROUP_NAME_REGEX = Regex("^[A-Za-z0-9_.-]+$")
        val USER_UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

data class RankRewardSettings(
    val enabled: Boolean = true,
    val luckPermsGroup: String = "mayor_current",
    val autoCreateGroup: Boolean = true
)

data class TagRewardSettings(
    val enabled: Boolean = false,
    val deluxeTagId: String = DEFAULT_TAG_ID,
    val display: String = DEFAULT_TAG_DISPLAY,
    val description: String = DEFAULT_TAG_DESCRIPTION,
    val order: Int = 100,
    val permission: String? = null,
    val autoCreateIfSupported: Boolean = true,
    val selectWhenApplied: Boolean = true,
    val clearWhenRemoved: Boolean = true,
    val renderBeforeLuckPerms: Boolean = true,
    val icon: TagIconSettings = TagIconSettings()
) {
    fun permissionNode(): String {
        val defaultNode = "DeluxeTags.Tag.$deluxeTagId"
        val configured = permission
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
            ?: return defaultNode
        val previousDefault = "deluxetags.tag.$deluxeTagId"
        return if (configured.equals(previousDefault, ignoreCase = true)) defaultNode else configured
    }

    companion object {
        const val DEFAULT_TAG_ID = "mayor_current"
        const val DEFAULT_TAG_DISPLAY = "&6[%title_name%]"
        const val DEFAULT_TAG_DESCRIPTION = "Awarded to the only %title_name%"
    }
}

data class TagIconSettings(
    val material: String = DEFAULT_MATERIAL,
    val customModelData: Int? = null,
    val glint: Boolean = false
) {
    fun materialOrDefault(): Material =
        materialOrNull(material) ?: Material.GOLDEN_HELMET

    companion object {
        const val DEFAULT_MATERIAL = "GOLDEN_HELMET"

        fun materialOrNull(raw: String?): Material? {
            val material = raw
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(Material::matchMaterial)
                ?: return null
            return material.takeIf(::isUsableItemMaterial)
        }

        fun normalizeMaterial(raw: String?): String? =
            materialOrNull(raw)?.name

        fun isUsableItemMaterial(material: Material): Boolean {
            if (material == Material.AIR || material.name.endsWith("_AIR")) return false
            return runCatching { material.isItem }.getOrElse {
                material.name !in nonItemFallbacks
            }
        }

        private val nonItemFallbacks = setOf(
            "WATER",
            "LAVA",
            "FIRE",
            "SOUL_FIRE",
            "LIGHT",
            "MOVING_PISTON",
            "PISTON_HEAD",
            "WALL_TORCH",
            "REDSTONE_WIRE",
            "TRIPWIRE",
            "TALL_SEAGRASS",
            "KELP_PLANT",
            "CAVE_VINES",
            "CAVE_VINES_PLANT",
            "BUBBLE_COLUMN"
        )
    }
}
