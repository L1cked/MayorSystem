package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class AdminResetElectionConfirmMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<red>Confirm Reset</red>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canReset = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
                || player.hasPermission(Perms.LEGACY_ADMIN_SETTINGS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
        if (!canReset) {
            inv.setItem(
                13,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have permission to reset elections.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray><- Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p, _ -> plugin.gui.open(p, AdminDebugMenu(plugin)) }
            return
        }

        val info = icon(
            Material.BARRIER,
            "<red>Reset Elections</red>",
            listOf(
                "<gray>This will wipe all term data.</gray>",
                "<gray>No mayor will be active.</gray>"
            )
        )
        inv.setItem(13, info)

        // Cancel (left) / Confirm (right) — consistent confirmation layout
        val cancel = icon(Material.GRAY_DYE, "<gray>Cancel</gray>", listOf("<dark_gray>Go back.</dark_gray>"))
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ ->
            plugin.gui.open(p, AdminDebugMenu(plugin))
        }

        val confirm = icon(
            Material.LIME_DYE,
            "<green>Confirm</green>",
            listOf("<gray>Reset term data now.</gray>")
        )
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ ->
            plugin.scope.launch(plugin.mainDispatcher) {
                plugin.adminActions.resetElectionTerms(p)
                plugin.messages.msg(p, "admin.settings.election_reset")
                plugin.gui.open(p, AdminDebugMenu(plugin))
            }
        }
    }
}
