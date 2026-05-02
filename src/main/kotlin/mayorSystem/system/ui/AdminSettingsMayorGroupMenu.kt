package mayorSystem.system.ui

import kotlinx.coroutines.launch
import mayorSystem.MayorPlugin
import mayorSystem.rewards.DisplayRewardMode
import mayorSystem.rewards.DisplayRewardModeLore
import mayorSystem.rewards.DisplayRewardSettings
import mayorSystem.rewards.DisplayRewardTargetType
import mayorSystem.rewards.DisplayRewardTagId
import mayorSystem.rewards.DisplayRewardText
import mayorSystem.rewards.TagIconSettings
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class AdminSettingsMayorGroupMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = Component.empty()
    override val rows: Int = 6

    override fun titleFor(player: Player): Component = gc("menus.admin_display_reward.title")

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val reward = plugin.settings.displayReward
        val canEdit = canEditReward(player)
        val tagValid = DisplayRewardTagId.isValid(reward.tag.deluxeTagId)
        val groupValid = DisplayRewardSettings.GROUP_NAME_REGEX.matches(reward.rank.luckPermsGroup)
        val health = rewardHealthSummary()

        inv.setItem(
            4,
            iconCfg(
                Material.NAME_TAG,
                "menus.admin_display_reward.summary",
                mapOf(
                    "enabled" to label(reward.enabled),
                    "mode" to reward.defaultMode.name,
                    "rank_group" to mmSafe(reward.rank.luckPermsGroup),
                    "tag_display" to rewardPreview(reward.tag.display),
                    "health" to health
                )
            )
        )

        val enabledItem = iconCfg(
            if (reward.enabled) Material.LIME_DYE else Material.GRAY_DYE,
            "menus.admin_display_reward.enabled",
            mapOf("value" to label(reward.enabled))
        )
        inv.setItem(10, enabledItem)
        setConfirm(10, enabledItem) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            updateReward(
                p,
                "display_reward.enabled",
                !plugin.settings.displayReward.enabled,
                "admin.settings.display_reward_enabled_set",
                mapOf("value" to (!plugin.settings.displayReward.enabled).toString())
            )
        }

        val modeItem = rewardModeItem(Material.COMPARATOR, "menus.admin_display_reward.default_mode", reward.defaultMode)
        inv.setItem(12, modeItem)
        setConfirm(12, modeItem) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            val next = plugin.settings.displayReward.defaultMode.next()
            updateReward(
                p,
                "display_reward.default_mode",
                next.name,
                "admin.settings.display_reward_default_mode_set",
                mapOf("mode" to next.name)
            )
        }

        val rankEnabled = iconCfg(
            if (reward.rank.enabled) Material.LIME_DYE else Material.GRAY_DYE,
            "menus.admin_display_reward.rank_enabled",
            mapOf("value" to label(reward.rank.enabled))
        )
        inv.setItem(14, rankEnabled)
        setConfirm(14, rankEnabled) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            val next = !plugin.settings.displayReward.rank.enabled
            updateReward(
                p,
                "display_reward.rank.enabled",
                next,
                "admin.settings.display_reward_rank_enabled_set",
                mapOf("value" to next.toString())
            )
        }

        val groupItem = iconCfg(
            if (groupValid) Material.GOLDEN_HELMET else Material.BARRIER,
            "menus.admin_display_reward.rank_group",
            mapOf("group" to mmSafe(reward.rank.luckPermsGroup))
        )
        inv.setItem(16, groupItem)
        setConfirm(16, groupItem) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.admin_display_reward.prompts.rank_group"),
                reward.rank.luckPermsGroup
            ) { who, input ->
                val value = input?.trim().orEmpty()
                if (value.isBlank() || !DisplayRewardSettings.GROUP_NAME_REGEX.matches(value)) {
                    plugin.messages.msg(who, "admin.settings.mayor_group_invalid")
                    plugin.gui.open(who, AdminSettingsMayorGroupMenu(plugin))
                    return@openAnvilPrompt
                }
                updateReward(
                    who,
                    "display_reward.rank.luckperms_group",
                    value,
                    "admin.settings.display_reward_rank_group_set",
                    mapOf("value" to value)
                )
            }
        }

        val tagEnabled = iconCfg(
            if (reward.tag.enabled) Material.LIME_DYE else Material.GRAY_DYE,
            "menus.admin_display_reward.tag_enabled",
            mapOf("value" to label(reward.tag.enabled))
        )
        inv.setItem(19, tagEnabled)
        setConfirm(19, tagEnabled) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            val next = !plugin.settings.displayReward.tag.enabled
            updateReward(
                p,
                "display_reward.tag.enabled",
                next,
                "admin.settings.display_reward_tag_enabled_set",
                mapOf("value" to next.toString())
            )
        }

        val tagId = iconCfg(
            if (tagValid) Material.NAME_TAG else Material.BARRIER,
            "menus.admin_display_reward.tag_id"
        )
        inv.setItem(21, tagId)
        setConfirm(21, tagId) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.admin_display_reward.prompts.tag_id"),
                ""
            ) { who, input ->
                val value = input?.trim().orEmpty()
                if (!DisplayRewardTagId.isValid(value)) {
                    plugin.messages.msg(who, "admin.settings.display_reward_tag_id_invalid")
                    plugin.gui.open(who, AdminSettingsMayorGroupMenu(plugin))
                    return@openAnvilPrompt
                }
                updateReward(
                    who,
                    "display_reward.tag.deluxe_tag_id",
                    value,
                    "admin.settings.display_reward_tag_id_set",
                    mapOf("value" to value)
                )
            }
        }

        val tagDisplay = iconCfg(
            Material.OAK_SIGN,
            "menus.admin_display_reward.tag_display",
            mapOf("display" to rewardPreview(reward.tag.display))
        )
        inv.setItem(23, tagDisplay)
        setConfirm(23, tagDisplay) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.admin_display_reward.prompts.tag_display"),
                reward.tag.display
            ) { who, input ->
                val value = input?.trim().orEmpty().take(64)
                if (value.isBlank()) {
                    plugin.messages.msg(who, "admin.settings.display_reward_value_required")
                    plugin.gui.open(who, AdminSettingsMayorGroupMenu(plugin))
                    return@openAnvilPrompt
                }
                updateReward(
                    who,
                    "display_reward.tag.display",
                    value,
                    "admin.settings.display_reward_tag_display_set",
                    mapOf("value" to value)
                )
            }
        }

        val tagDescription = iconCfg(
            Material.WRITABLE_BOOK,
            "menus.admin_display_reward.tag_description",
            mapOf("description" to mmSafe(reward.tag.description))
        )
        inv.setItem(25, tagDescription)
        setConfirm(25, tagDescription) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.admin_display_reward.prompts.tag_description"),
                reward.tag.description
            ) { who, input ->
                val value = input?.trim().orEmpty().take(96)
                if (value.isBlank()) {
                    plugin.messages.msg(who, "admin.settings.display_reward_value_required")
                    plugin.gui.open(who, AdminSettingsMayorGroupMenu(plugin))
                    return@openAnvilPrompt
                }
                updateReward(
                    who,
                    "display_reward.tag.description",
                    value,
                    "admin.settings.display_reward_tag_description_set",
                    mapOf("value" to value)
                )
            }
        }

        val tagIcon = iconCfg(
            reward.tag.icon.materialOrDefault(),
            "menus.admin_display_reward.tag_icon",
            mapOf(
                "material" to reward.tag.icon.materialOrDefault().name,
                "custom_model_data" to (reward.tag.icon.customModelData?.toString() ?: g("menus.common.none")),
                "glint" to label(reward.tag.icon.glint)
            )
        )
        applyTagIconMeta(tagIcon, reward.tag.icon)
        inv.setItem(28, tagIcon)
        set(28, tagIcon) { p -> plugin.gui.open(p, AdminDisplayRewardTagIconMenu(plugin)) }

        val tagBeforeRank = iconCfg(
            if (reward.tag.renderBeforeLuckPerms) Material.LIME_DYE else Material.GRAY_DYE,
            "menus.admin_display_reward.tag_before_rank",
            mapOf("value" to label(reward.tag.renderBeforeLuckPerms))
        )
        inv.setItem(37, tagBeforeRank)
        setConfirm(37, tagBeforeRank) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            val next = !plugin.settings.displayReward.tag.renderBeforeLuckPerms
            updateReward(
                p,
                "display_reward.tag.render_before_luckperms",
                next,
                "admin.settings.display_reward_tag_before_rank_set",
                mapOf("value" to next.toString())
            )
        }

        targetButton(inv, 30, DisplayRewardTargetKind.TRACKS)
        targetButton(inv, 32, DisplayRewardTargetKind.GROUPS)
        targetButton(inv, 34, DisplayRewardTargetKind.USERS)

        val sync = iconCfg(Material.CLOCK, "menus.admin_display_reward.sync")
        inv.setItem(40, sync)
        setConfirm(40, sync) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(p, plugin.adminActions.syncDisplayReward(p), denyOnNonSuccess = true)
                plugin.gui.open(p, AdminSettingsMayorGroupMenu(plugin))
            }
        }

        val back = iconCfg(Material.ARROW, "menus.common.back")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }

        if (!canEdit) {
            inv.setItem(
                49,
                iconCfg(Material.GRAY_DYE, "menus.admin_display_reward.read_only")
            )
        }
    }

    private fun targetButton(inv: Inventory, slot: Int, kind: DisplayRewardTargetKind) {
        val count = kind.entries(plugin.settings.displayReward).size
        val item = iconCfg(
            kind.material,
            "menus.admin_display_reward.targets.${kind.key}",
            mapOf("count" to count.toString())
        )
        inv.setItem(slot, item)
        set(slot, item) { p -> plugin.gui.open(p, AdminDisplayRewardTargetsMenu(plugin, kind)) }
    }

    private fun updateReward(
        player: Player,
        path: String,
        value: Any?,
        successKey: String,
        placeholders: Map<String, String> = emptyMap()
    ) {
        plugin.scope.launch(plugin.mainDispatcher) {
            dispatchResult(
                player,
                plugin.adminActions.updateDisplayRewardConfig(player, path, value, successKey, placeholders),
                denyOnNonSuccess = true
            )
            plugin.gui.open(player, AdminSettingsMayorGroupMenu(plugin))
        }
    }

    private fun rewardModeItem(material: Material, baseKey: String, mode: DisplayRewardMode): ItemStack =
        icon(
            material,
            g("$baseKey.name", mapOf("mode" to mode.name, "next" to mode.next().name)),
            gl("$baseKey.lore", mapOf("mode" to mode.name, "next" to mode.next().name)) + DisplayRewardModeLore.lines(mode)
        )

    private fun applyTagIconMeta(item: ItemStack, settings: TagIconSettings) {
        val meta = item.itemMeta ?: return
        settings.customModelData?.let {
            val component = meta.customModelDataComponent
            component.floats = listOf(it.toFloat())
            meta.setCustomModelDataComponent(component)
        }
        item.itemMeta = meta
        if (settings.glint) glow(item)
    }

    private fun rewardHealthSummary(): String {
        val checks = plugin.health.run().filter {
            it.id.startsWith("display_reward") ||
                it.id.startsWith("luckperms") ||
                it.id.startsWith("deluxetags")
        }
        val errors = checks.count { it.severity.name == "ERROR" }
        val warns = checks.count { it.severity.name == "WARN" }
        return when {
            errors > 0 -> "ERROR"
            warns > 0 -> "WARN"
            else -> "OK"
        }
    }

    private fun label(value: Boolean): String =
        if (value) g("menus.common.on") else g("menus.common.off")

    private fun rewardPreview(raw: String): String =
        DisplayRewardText.previewMini(plugin.settings.applyTitleTokens(raw))

    private fun canEditReward(player: Player): Boolean =
        player.hasPermission(plugin.settings.displayReward.adminEditPermission) ||
            player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)

    private fun requireRewardEdit(player: Player): Boolean =
        requireAllowed(player, canEditReward(player), "errors.no_permission")
}

enum class DisplayRewardTargetKind(
    val key: String,
    val path: String,
    val material: Material,
    val keyPattern: Regex
) {
    TRACKS(
        key = "tracks",
        path = "display_reward.targets.tracks",
        material = Material.COMPASS,
        keyPattern = DisplayRewardSettings.GROUP_NAME_REGEX
    ),
    GROUPS(
        key = "groups",
        path = "display_reward.targets.groups",
        material = Material.BOOKSHELF,
        keyPattern = DisplayRewardSettings.GROUP_NAME_REGEX
    ),
    USERS(
        key = "users",
        path = "display_reward.targets.users",
        material = Material.PLAYER_HEAD,
        keyPattern = Regex("^.+$")
    );

    fun entries(settings: mayorSystem.rewards.DisplayRewardSettings): Map<String, DisplayRewardMode> = when (this) {
        TRACKS -> settings.targets.tracks
        GROUPS -> settings.targets.groups
        USERS -> settings.targets.users
    }

    fun targetType(): DisplayRewardTargetType = when (this) {
        TRACKS -> DisplayRewardTargetType.TRACK
        GROUPS -> DisplayRewardTargetType.GROUP
        USERS -> DisplayRewardTargetType.USER
    }
}

class AdminDisplayRewardTargetsMenu(
    plugin: MayorPlugin,
    private val kind: DisplayRewardTargetKind,
    private val page: Int = 0
) : Menu(plugin) {
    override val title: Component = Component.empty()
    override val rows: Int = 6

    override fun titleFor(player: Player): Component =
        gc("menus.admin_display_reward_targets.title", mapOf("kind" to kindLabel()))

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val entries = kind.entries(plugin.settings.displayReward)
            .toList()
            .sortedBy { it.first }
        val slots = contentSlots()
        val totalPages = ((entries.size - 1) / slots.size + 1).coerceAtLeast(1)
        val safePage = page.coerceIn(0, totalPages - 1)
        val visible = entries.drop(safePage * slots.size).take(slots.size)

        inv.setItem(
            4,
            iconCfg(
                kind.material,
                "menus.admin_display_reward_targets.header",
                mapOf(
                    "kind" to kindLabel(),
                    "count" to entries.size.toString(),
                    "page" to (safePage + 1).toString(),
                    "total_pages" to totalPages.toString()
                )
            )
        )

        for ((index, pair) in visible.withIndex()) {
            val (target, mode) = pair
            val slot = slots[index]
            val targetName = targetDisplayName(target)
            val item = icon(
                kind.material,
                g("menus.admin_display_reward_targets.entry.name", mapOf("target" to mmSafe(targetName))),
                gl(
                    "menus.admin_display_reward_targets.entry.lore",
                    mapOf("target" to mmSafe(targetName), "mode" to mode.label(), "next" to mode.next().label())
                ) + DisplayRewardModeLore.lines(mode)
            )
            inv.setItem(slot, item)
            setConfirm(slot, item) { p, click ->
                if (!requireRewardEdit(p)) return@setConfirm
                if (click == ClickType.SHIFT_RIGHT) {
                    plugin.gui.open(p, AdminDisplayRewardTargetRemoveConfirmMenu(plugin, kind, target, safePage))
                } else {
                    setTargetMode(p, target, mode.next())
                }
            }
        }

        val add = iconCfg(Material.ANVIL, "menus.admin_display_reward_targets.add", mapOf("kind" to kindLabel()))
        inv.setItem(49, add)
        setConfirm(49, add) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.admin_display_reward_targets.prompt_title", mapOf("kind" to kindLabel())),
                ""
            ) { who, input ->
                val target = input?.trim().orEmpty()
                if (target.isBlank() || (kind != DisplayRewardTargetKind.USERS && !kind.keyPattern.matches(target))) {
                    plugin.messages.msg(who, "admin.settings.display_reward_target_invalid")
                    plugin.gui.open(who, AdminDisplayRewardTargetsMenu(plugin, kind, safePage))
                    return@openAnvilPrompt
                }
                setTargetMode(who, target, DisplayRewardMode.RANK)
            }
        }

        val back = iconCfg(Material.ARROW, "menus.common.back")
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, AdminSettingsMayorGroupMenu(plugin)) }

        val prev = icon(Material.ARROW, g("menus.admin_display_reward_targets.prev.name"))
        inv.setItem(48, prev)
        set(48, prev) { p, _ ->
            if (safePage <= 0) {
                denyClick()
            } else {
                plugin.gui.open(p, AdminDisplayRewardTargetsMenu(plugin, kind, safePage - 1))
            }
        }

        val next = icon(Material.ARROW, g("menus.admin_display_reward_targets.next.name"))
        inv.setItem(50, next)
        set(50, next) { p, _ ->
            if (safePage >= totalPages - 1) {
                denyClick()
            } else {
                plugin.gui.open(p, AdminDisplayRewardTargetsMenu(plugin, kind, safePage + 1))
            }
        }
    }

    private fun setTargetMode(player: Player, target: String, mode: DisplayRewardMode) {
        val normalized = target.trim()
        plugin.scope.launch(plugin.mainDispatcher) {
            dispatchResult(
                player,
                plugin.adminActions.setDisplayRewardTarget(player, kind.targetType(), normalized, mode),
                denyOnNonSuccess = true
            )
            plugin.gui.open(player, AdminDisplayRewardTargetsMenu(plugin, kind, page))
        }
    }

    private fun requireRewardEdit(player: Player): Boolean =
        requireAllowed(
            player,
            player.hasPermission(plugin.settings.displayReward.adminEditPermission) ||
                player.hasPermission(Perms.ADMIN_SETTINGS_EDIT),
            "errors.no_permission"
        )

    private fun kindLabel(): String =
        g("menus.admin_display_reward_targets.kind.${kind.key}")

    private fun targetDisplayName(target: String): String =
        if (kind == DisplayRewardTargetKind.USERS) {
            val fallback = plugin.config.getString("admin.display_reward.target_user_names.$target")
            runCatching { displayNamePlain(java.util.UUID.fromString(target), fallback).withoutUuidFallback() }.getOrDefault("Unknown player")
        } else {
            target
        }

    private fun contentSlots(): List<Int> {
        val slots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                slots += row * 9 + col
            }
        }
        return slots
    }
}

class AdminDisplayRewardTargetRemoveConfirmMenu(
    plugin: MayorPlugin,
    private val kind: DisplayRewardTargetKind,
    private val target: String,
    private val page: Int
) : Menu(plugin) {
    override val title: Component = mm.deserialize("<red>Confirm Removal</red>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val info = icon(
            Material.BARRIER,
            g("menus.admin_display_reward_targets.remove_confirm.name", mapOf("target" to mmSafe(targetDisplayName()))),
            gl("menus.admin_display_reward_targets.remove_confirm.lore", mapOf("kind" to kindLabel(), "target" to mmSafe(targetDisplayName())))
        )
        inv.setItem(13, info)

        val cancel = icon(Material.RED_DYE, g("menus.common.cancel.name"), gl("menus.common.cancel.lore"))
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ -> plugin.gui.open(p, AdminDisplayRewardTargetsMenu(plugin, kind, page)) }

        val confirm = icon(Material.LIME_DYE, g("menus.common.confirm.name"), gl("menus.common.confirm.lore"))
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.removeDisplayRewardTarget(p, kind.targetType(), target),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminDisplayRewardTargetsMenu(plugin, kind, page))
            }
        }
    }

    private fun requireRewardEdit(player: Player): Boolean =
        requireAllowed(
            player,
            player.hasPermission(plugin.settings.displayReward.adminEditPermission) ||
                player.hasPermission(Perms.ADMIN_SETTINGS_EDIT),
            "errors.no_permission"
        )

    private fun kindLabel(): String =
        g("menus.admin_display_reward_targets.kind.${kind.key}")

    private fun targetDisplayName(): String =
        if (kind == DisplayRewardTargetKind.USERS) {
            val fallback = plugin.config.getString("admin.display_reward.target_user_names.$target")
            runCatching { displayNamePlain(java.util.UUID.fromString(target), fallback).withoutUuidFallback() }.getOrDefault("Unknown player")
        } else {
            target
        }
}

private fun String.withoutUuidFallback(): String =
    takeUnless { runCatching { java.util.UUID.fromString(it.trim()) }.isSuccess } ?: "Unknown player"

class AdminDisplayRewardTagIconMenu(plugin: MayorPlugin) : Menu(plugin) {
    override val title: Component = Component.empty()
    override val rows: Int = 4

    override fun titleFor(player: Player): Component = gc("menus.admin_display_reward_tag_icon.title")

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val reward = plugin.settings.displayReward
        val iconMaterial = reward.tag.icon.materialOrDefault()
        val preview = iconCfg(
            iconMaterial,
            "menus.admin_display_reward_tag_icon.preview",
            mapOf(
                "material" to iconMaterial.name,
                "custom_model_data" to (reward.tag.icon.customModelData?.toString() ?: g("menus.common.none")),
                "glint" to if (reward.tag.icon.glint) g("menus.common.on") else g("menus.common.off")
            )
        )
        applyTagIconMeta(preview, reward.tag.icon)
        inv.setItem(4, preview)

        val material = iconCfg(Material.ITEM_FRAME, "menus.admin_display_reward_tag_icon.material", mapOf("material" to iconMaterial.name))
        inv.setItem(11, material)
        setConfirm(11, material) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.gui.openAnvilPrompt(p, gc("menus.admin_display_reward_tag_icon.prompts.material"), iconMaterial.name) { who, input ->
                val normalized = TagIconSettings.normalizeMaterial(input)
                if (normalized == null) {
                    plugin.messages.msg(who, "admin.settings.display_reward_icon_invalid")
                    plugin.gui.open(who, AdminDisplayRewardTagIconMenu(plugin))
                    return@openAnvilPrompt
                }
                plugin.scope.launch(plugin.mainDispatcher) {
                    dispatchResult(who, plugin.adminActions.setDisplayRewardTagIconMaterial(who, normalized), denyOnNonSuccess = true)
                    plugin.gui.open(who, AdminDisplayRewardTagIconMenu(plugin))
                }
            }
        }

        val customModel = iconCfg(
            Material.PAPER,
            "menus.admin_display_reward_tag_icon.custom_model_data",
            mapOf("custom_model_data" to (reward.tag.icon.customModelData?.toString() ?: g("menus.common.none")))
        )
        inv.setItem(13, customModel)
        setConfirm(13, customModel) { p, click ->
            if (!requireRewardEdit(p)) return@setConfirm
            if (click == ClickType.SHIFT_RIGHT) {
                plugin.scope.launch(plugin.mainDispatcher) {
                    dispatchResult(p, plugin.adminActions.setDisplayRewardTagIconCustomModelData(p, null), denyOnNonSuccess = true)
                    plugin.gui.open(p, AdminDisplayRewardTagIconMenu(plugin))
                }
                return@setConfirm
            }
            plugin.gui.openAnvilPrompt(
                p,
                gc("menus.admin_display_reward_tag_icon.prompts.custom_model_data"),
                reward.tag.icon.customModelData?.toString() ?: ""
            ) { who, input ->
                val value = input?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
                if (value == null) {
                    plugin.messages.msg(who, "admin.settings.display_reward_custom_model_invalid")
                    plugin.gui.open(who, AdminDisplayRewardTagIconMenu(plugin))
                    return@openAnvilPrompt
                }
                plugin.scope.launch(plugin.mainDispatcher) {
                    dispatchResult(who, plugin.adminActions.setDisplayRewardTagIconCustomModelData(who, value), denyOnNonSuccess = true)
                    plugin.gui.open(who, AdminDisplayRewardTagIconMenu(plugin))
                }
            }
        }

        val glint = iconCfg(
            if (reward.tag.icon.glint) Material.ENCHANTED_BOOK else Material.BOOK,
            "menus.admin_display_reward_tag_icon.glint",
            mapOf("value" to if (reward.tag.icon.glint) g("menus.common.on") else g("menus.common.off"))
        )
        inv.setItem(15, glint)
        setConfirm(15, glint) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(p, plugin.adminActions.toggleDisplayRewardTagIconGlint(p), denyOnNonSuccess = true)
                plugin.gui.open(p, AdminDisplayRewardTagIconMenu(plugin))
            }
        }

        val reset = iconCfg(Material.GOLDEN_HELMET, "menus.admin_display_reward_tag_icon.reset")
        inv.setItem(22, reset)
        setConfirm(22, reset) { p, _ ->
            if (!requireRewardEdit(p)) return@setConfirm
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(p, plugin.adminActions.resetDisplayRewardTagIcon(p), denyOnNonSuccess = true)
                plugin.gui.open(p, AdminDisplayRewardTagIconMenu(plugin))
            }
        }

        val back = iconCfg(Material.ARROW, "menus.common.back")
        inv.setItem(27, back)
        set(27, back) { p, _ -> plugin.gui.open(p, AdminSettingsMayorGroupMenu(plugin)) }
    }

    private fun requireRewardEdit(player: Player): Boolean =
        requireAllowed(
            player,
            player.hasPermission(plugin.settings.displayReward.adminEditPermission) ||
                player.hasPermission(Perms.ADMIN_SETTINGS_EDIT),
            "errors.no_permission"
        )

    private fun applyTagIconMeta(item: ItemStack, settings: TagIconSettings) {
        val meta = item.itemMeta ?: return
        settings.customModelData?.let {
            val component = meta.customModelDataComponent
            component.floats = listOf(it.toFloat())
            meta.setCustomModelDataComponent(component)
        }
        item.itemMeta = meta
        if (settings.glint) glow(item)
    }
}
