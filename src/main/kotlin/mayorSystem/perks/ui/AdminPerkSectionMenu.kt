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

        val perks = plugin.perks.perksForSection(sectionId, includeDisabled = true)
        if (perks.isNotEmpty()) {
            var slot = 10
            for (perk in perks) {
                if (slot >= inv.size - 9) break
                val perkEnabled = perk.enabled
                val name = plugin.perks.resolveText(player, perk.displayNameMm)
                val lore = plugin.perks.resolveLore(player, perk.loreMm)
                val adminLore = plugin.perks.resolveLore(player, perk.adminLoreMm)

                val combinedLore = if (adminLore.isNotEmpty()) {
                    lore + listOf("") + adminLore
                } else {
                    lore
                }

                val item = icon(
                    if (perkEnabled) Material.LIME_DYE else Material.RED_DYE,
                    "$name <gray>(${if (perkEnabled) "ON" else "OFF"})</gray>",
                    combinedLore + buildList {
                        add("")
                        add("<gray>Click to toggle this perk.</gray>")
                    }
                )
                inv.setItem(slot, item)
                set(slot, item) { p ->
                    overrideClickSound(UiClickSound.CONFIRM)
                    plugin.adminActions.setPerkEnabled(p, sectionId, perk.id, !perkEnabled)
                    plugin.gui.open(p, AdminPerkSectionMenu(plugin, sectionId))
                }

                slot++
                if (slot % 9 == 8) slot += 2 // skip right border + next row left border
            }
        } else {
            val reason = plugin.perks.sectionEmptyReason(sectionId)
                ?: "No perks configured for this section."
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>No perks found</red>",
                    listOf("<gray>$reason</gray>")
                )
            )
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminPerkCatalogMenu(plugin)) }
    }
}

