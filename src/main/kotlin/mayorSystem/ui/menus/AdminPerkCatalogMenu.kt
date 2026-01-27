package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
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
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
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
            inv.setItem(49, back)
            set(49, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
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

                val item = icon(
                    iconMat,
                    "$name <gray>(${if (enabled) "ON" else "OFF"})</gray>",
                    listOf(
                        "<gray>Left click:</gray> <white>toggle section</white>",
                        "<gray>Right click:</gray> <white>manage perks</white>"
                    )
                )
                inv.setItem(slot, item)
                set(slot, item) { p, click ->
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
        inv.setItem(49, back)
        set(49, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private val ClickType.isRightClick get() =
        this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
}
