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
    private var page: Int = 0

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
        if (sec == null || sec.getKeys(false).isEmpty()) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>No perk sections configured</red>",
                    listOf("<gray>Configure perks.sections in config.yml.</gray>")
                )
            )
        } else {
            val slots = contentSlots(inv)
            val ids = sec.getKeys(false).toList()
            val totalPages = maxOf(1, (ids.size + slots.size - 1) / slots.size)
            page = page.coerceIn(0, totalPages - 1)
            val shown = ids.drop(page * slots.size).take(slots.size)
            for ((index, id) in shown.withIndex()) {
                val slot = slots[index]
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

            }

            val prev = icon(Material.ARROW, "<gray>Prev</gray>")
            inv.setItem(46, prev)
            set(46, prev) { p ->
                if (page <= 0) {
                    denyClick()
                } else {
                    page -= 1
                    plugin.gui.open(p, this)
                }
            }

            val next = icon(Material.ARROW, "<gray>Next</gray>")
            inv.setItem(53, next)
            set(53, next) { p ->
                if (page >= totalPages - 1) {
                    denyClick()
                } else {
                    page += 1
                    plugin.gui.open(p, this)
                }
            }
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminPerksMenu(plugin)) }
    }

    private val ClickType.isRightClick get() =
        this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
}

