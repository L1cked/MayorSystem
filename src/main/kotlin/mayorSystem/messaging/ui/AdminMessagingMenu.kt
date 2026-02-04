package mayorSystem.messaging.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminMessagingMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#00c6ff:#0072ff>Messaging</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canEdit = player.hasPermission(Perms.ADMIN_MESSAGING_EDIT)
                || player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
                || player.hasPermission(Perms.LEGACY_ADMIN_SETTINGS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)

        if (!canEdit) {
            inv.setItem(
                13,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have access to messaging settings.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray><- Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
            return
        }

        val prompts = icon(
            Material.WRITABLE_BOOK,
            "<yellow>Chat Prompts</yellow>",
            listOf("<gray>Max lengths for bio and request fields.</gray>")
        )
        inv.setItem(11, prompts)
        set(11, prompts) { p -> plugin.gui.open(p, AdminSettingsChatPromptsMenu(plugin)) }

        val broadcasts = icon(
            Material.BELL,
            "<gold>Election Broadcasts</gold>",
            listOf("<gray>Toggle election announcements.</gray>")
        )
        inv.setItem(15, broadcasts)
        set(15, broadcasts) { p -> plugin.gui.open(p, AdminBroadcastSettingsMenu(plugin)) }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }
}

