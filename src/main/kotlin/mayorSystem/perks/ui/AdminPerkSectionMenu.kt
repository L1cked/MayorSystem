package mayorSystem.perks.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.perks.ui.AdminPerksMenu
import mayorSystem.ui.UiClickSound
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory

class AdminPerkSectionMenu(plugin: MayorPlugin, private val sectionId: String) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gold>📂 Section:</gold> <white>$sectionId</white>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val hasPerm = player.hasPermission(Perms.ADMIN_PERKS_CATALOG)
        if (!hasPerm) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have permission to manage the perk catalog.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, AdminPerksMenu(plugin)) }
            return
        }

        val blockReason = plugin.perks.perkSectionBlockReason(sectionId)
        if (blockReason != null) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>Section unavailable</red>",
                    listOf("<gray>$blockReason</gray>", "<gray>Install the addon to enable.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, AdminPerkCatalogMenu(plugin)) }
            return
        }

        val base = "perks.sections.$sectionId"
        val enabled = plugin.config.getBoolean("$base.enabled", true)

        val toggleSection = icon(
            if (enabled) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>Section Enabled:</yellow> <white>$enabled</white>",
            listOf("<gray>Toggle this entire section.</gray>")
        )
        inv.setItem(4, toggleSection)
        setConfirm(4, toggleSection) { p ->
            plugin.adminActions.setPerkSectionEnabled(p, sectionId, !enabled)
            plugin.gui.open(p, AdminPerkSectionMenu(plugin, sectionId))
        }

        val limitRaw = plugin.config.getInt("$base.pick_limit", 0)
        val limitLabel = if (limitRaw <= 0) "Unlimited" else limitRaw.toString()
        val limitItem = icon(
            Material.COMPARATOR,
            "<yellow>Perk limit:</yellow> <white>$limitLabel</white>",
            listOf(
                "<gray>Max perks selectable from this section.</gray>",
                "",
                "<gray>Left:</gray> <white>+1</white>",
                "<gray>Right:</gray> <white>-1</white>",
                "<gray>Shift-Left:</gray> <white>+5</white>",
                "<gray>Shift-Right:</gray> <white>Unlimited</white>"
            )
        )
        inv.setItem(6, limitItem)
        setConfirm(6, limitItem) { p, click ->
            val current = plugin.config.getInt("$base.pick_limit", 0).coerceAtLeast(0)
            val next = when (click) {
                ClickType.SHIFT_LEFT -> current + 5
                ClickType.LEFT -> current + 1
                ClickType.SHIFT_RIGHT -> 0
                ClickType.RIGHT -> (current - 1).coerceAtLeast(0)
                else -> current
            }
            plugin.adminActions.updatePerkConfig(p, "$base.pick_limit", next)
            plugin.gui.open(p, AdminPerkSectionMenu(plugin, sectionId))
        }

        val perksSec = plugin.config.getConfigurationSection("$base.perks")
        if (perksSec != null) {
            var slot = 10
            for (perkId in perksSec.getKeys(false)) {
                if (slot >= inv.size - 9) break
                val pBase = "$base.perks.$perkId"
                val perkEnabled = plugin.config.getBoolean("$pBase.enabled", true)
                val name = plugin.config.getString("$pBase.display_name") ?: "<white>$perkId</white>"
                val lore = plugin.config.getStringList("$pBase.lore")
                val adminLore = plugin.config.getStringList("$pBase.admin_lore")
                val hasMultiplier = plugin.config.contains("$pBase.sell_multiplier")
                val appliesTo = plugin.config.getString("$pBase.applies_to")?.uppercase()
                val lockedBySell = hasMultiplier && !plugin.perks.canEnableSellCategory(appliesTo)

                val combinedLore = if (adminLore.isNotEmpty()) {
                    lore + listOf("") + adminLore
                } else {
                    lore
                }

                val item = icon(
                    if (lockedBySell) Material.BARRIER else if (perkEnabled) Material.LIME_DYE else Material.RED_DYE,
                    "$name <gray>(${if (perkEnabled) "ON" else "OFF"})</gray>",
                    combinedLore + buildList {
                        add("")
                        if (lockedBySell) {
                            add("<red>Requires SystemSellAddon.</red>")
                            add("<dark_gray>(SystemSellAddon)</dark_gray>")
                        } else {
                            add("<gray>Click to toggle this perk.</gray>")
                        }
                    }
                )
                inv.setItem(slot, item)
                set(slot, item) { p ->
                    if (lockedBySell) {
                        denyMsg(p, "admin.perks.sell_category_locked", mapOf("perk" to perkId))
                        return@set
                    }
                    overrideClickSound(UiClickSound.CONFIRM)
                    plugin.adminActions.setPerkEnabled(p, sectionId, perkId, !perkEnabled)
                    plugin.gui.open(p, AdminPerkSectionMenu(plugin, sectionId))
                }

                slot++
                if (slot % 9 == 8) slot += 2 // skip right border + next row left border
            }
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminPerkCatalogMenu(plugin)) }
    }
}

