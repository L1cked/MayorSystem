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

class AdminPerkCatalogMenu(plugin: MayorPlugin) : Menu(plugin) {
    override val title: Component = mm.deserialize("<gold>📦 Perk Catalog</gold>")
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

        val header = icon(
            Material.BOOK,
            "<gold>Perk Catalog</gold>",
            listOf(
                "<gray>Manage perk sections and perks.</gray>",
                "<dark_gray>Left click: toggle section</dark_gray>",
                "<dark_gray>Right click: manage perks</dark_gray>"
            )
        )
        inv.setItem(4, header)

        val sec = plugin.config.getConfigurationSection("perks.sections")
        if (sec != null) {
            var slot = 10
            for (id in sec.getKeys(false)) {
                if (slot >= inv.size - 10) break
                val base = "perks.sections.$id"
                val enabled = plugin.config.getBoolean("$base.enabled", true)
                val name = plugin.config.getString("$base.display_name") ?: "<white>$id</white>"
                val iconMat = Material.matchMaterial(plugin.config.getString("$base.icon") ?: "CHEST") ?: Material.CHEST
                val blockReason = plugin.perks.perkSectionBlockReason(id)
                val available = blockReason == null

                val item = icon(
                    if (available) iconMat else Material.BARRIER,
                    if (available) "$name <gray>(${if (enabled) "ON" else "OFF"})</gray>" else "$name <red>(LOCKED)</red>",
                    buildList {
                        if (available) {
                            add("<gray>Left click:</gray> <white>toggle section</white>")
                            add("<gray>Right click:</gray> <white>manage perks</white>")
                        } else {
                            add("<red>$blockReason</red>")
                            add("<gray>Install the addon to enable.</gray>")
                        }
                    }
                )
                inv.setItem(slot, item)
                set(slot, item) { p, click ->
                    if (!available) {
                        denyMm(p, "<red>$blockReason</red>")
                        return@set
                    }
                    if (click.isRightClick) {
                        plugin.gui.open(p, AdminPerkSectionMenu(plugin, id))
                        return@set
                    }
                    overrideClickSound(UiClickSound.CONFIRM)
                    plugin.adminActions.setPerkSectionEnabled(p, id, !enabled)
                    plugin.gui.open(p, AdminPerkCatalogMenu(plugin))
                }

                slot += if ((slot + 1) % 9 == 0) 3 else 1
            }
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminPerksMenu(plugin)) }
    }

    private val ClickType.isRightClick get() =
        this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
}

