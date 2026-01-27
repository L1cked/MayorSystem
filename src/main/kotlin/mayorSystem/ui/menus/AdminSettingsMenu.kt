package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

/**
 * Settings are split into sections to avoid the "wall of toggles" problem.
 */
class AdminSettingsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>⚙ Admin Settings</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        // Intentionally no border/filler in this menu.
        // We keep the outer ring empty and place buttons inside.

        val canEditSettings = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
                || player.hasPermission(Perms.LEGACY_ADMIN_SETTINGS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        val canCatalog = player.hasPermission(Perms.ADMIN_PERKS_CATALOG)
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)

        if (!canEditSettings && !canCatalog) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have permission to access admin settings.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(43, back)
            set(43, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        val general = icon(
            Material.REDSTONE_TORCH,
            "<yellow>General</yellow>",
            listOf("<gray>Enable/disable core features.</gray>")
        )
        inv.setItem(20, general)
        set(20, general) { p ->
            if (!canEditSettings) {
                deny(p, "You do not have permission to edit settings.")
                plugin.gui.open(p, AdminSettingsMenu(plugin))
                return@set
            }
            plugin.gui.open(p, AdminSettingsGeneralMenu(plugin))
        }

        val term = icon(
            Material.CLOCK,
            "<yellow>Term & Schedule</yellow>",
            listOf("<gray>Term length, vote window, start time, bonus terms.</gray>")
        )
        inv.setItem(22, term)
        set(22, term) { p ->
            if (!canEditSettings) {
                deny(p, "You do not have permission to edit settings.")
                plugin.gui.open(p, AdminSettingsMenu(plugin))
                return@set
            }
            plugin.gui.open(p, AdminSettingsTermMenu(plugin))
        }

        val apply = icon(
            Material.EMERALD,
            "<yellow>Apply Requirements</yellow>",
            listOf("<gray>Playtime requirements and application cost.</gray>")
        )
        inv.setItem(24, apply)
        set(24, apply) { p ->
            if (!canEditSettings) {
                deny(p, "You do not have permission to edit settings.")
                plugin.gui.open(p, AdminSettingsMenu(plugin))
                return@set
            }
            plugin.gui.open(p, AdminSettingsApplyMenu(plugin))
        }

        val customReq = icon(
            Material.COMPARATOR,
            "<yellow>Custom Requests</yellow>",
            listOf("<gray>Who can request perks and how many per term.</gray>")
        )
        inv.setItem(29, customReq)
        set(29, customReq) { p ->
            if (!canEditSettings) {
                deny(p, "You do not have permission to edit settings.")
                plugin.gui.open(p, AdminSettingsMenu(plugin))
                return@set
            }
            plugin.gui.open(p, AdminSettingsCustomRequestsMenu(plugin))
        }

        val election = icon(
            Material.TARGET,
            "<yellow>Election Rules</yellow>",
            listOf("<gray>Vote change rules, tie policy, etc.</gray>")
        )
        inv.setItem(31, election)
        set(31, election) { p ->
            if (!canEditSettings) {
                deny(p, "You do not have permission to edit settings.")
                plugin.gui.open(p, AdminSettingsMenu(plugin))
                return@set
            }
            plugin.gui.open(p, AdminElectionSettingsMenu(plugin))
        }

        val catalog = icon(
            Material.CHEST,
            "<gold>Perk Catalog</gold>",
            listOf("<gray>Enable/disable sections and perks.</gray>")
        )
        inv.setItem(33, catalog)
        set(33, catalog) { p ->
            if (!canCatalog) {
                deny(p, "You do not have permission to manage the perk catalog.")
                plugin.gui.open(p, AdminSettingsMenu(plugin))
                return@set
            }
            plugin.gui.open(p, AdminPerkCatalogMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(43, back)
        set(43, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }
}
