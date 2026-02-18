package mayorSystem.system.ui

import mayorSystem.MayorPlugin
import mayorSystem.candidates.ui.AdminCandidatesMenu
import mayorSystem.elections.ui.AdminElectionMenu
import mayorSystem.maintenance.ui.AdminDebugMenu
import mayorSystem.monitoring.ui.AdminMonitoringMenu
import mayorSystem.perks.ui.AdminPerksMenu
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.ui.menus.MainMenu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminMenu(plugin: MayorPlugin) : Menu(plugin) {
    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Admin Panel</gradient>")
    override val rows: Int = 5

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canSettings = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
                || player.hasPermission(Perms.ADMIN_SYSTEM_TOGGLE)
                || player.hasPermission(Perms.ADMIN_GOVERNANCE_EDIT)
                || player.hasPermission(Perms.ADMIN_MESSAGING_EDIT)
        val canElections = player.hasPermission(Perms.ADMIN_ELECTION_START)
                || player.hasPermission(Perms.ADMIN_ELECTION_END)
                || player.hasPermission(Perms.ADMIN_ELECTION_CLEAR)
                || player.hasPermission(Perms.ADMIN_ELECTION_ELECT)
        val canCandidates = player.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE)
                || player.hasPermission(Perms.ADMIN_CANDIDATES_RESTORE)
                || player.hasPermission(Perms.ADMIN_CANDIDATES_PROCESS)
                || player.hasPermission(Perms.ADMIN_CANDIDATES_APPLYBAN)
        val canPerks = player.hasPermission(Perms.ADMIN_PERKS_REFRESH)
                || player.hasPermission(Perms.ADMIN_PERKS_REQUESTS)
                || player.hasPermission(Perms.ADMIN_PERKS_CATALOG)
        val canMonitoring = player.hasPermission(Perms.ADMIN_AUDIT_VIEW)
                || player.hasPermission(Perms.ADMIN_HEALTH_VIEW)
        val canMaintenance = player.hasPermission(Perms.ADMIN_MAINTENANCE_RELOAD)
                || player.hasPermission(Perms.ADMIN_MAINTENANCE_DEBUG)
                || player.hasPermission(Perms.ADMIN_SETTINGS_RELOAD)

        if (canSettings) {
            val item = icon(Material.REDSTONE_TORCH, "<yellow>Settings</yellow>", listOf("<gray>System and configuration.</gray>"))
            inv.setItem(10, item)
            set(10, item) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
        }

        if (canElections) {
            val item = icon(
                Material.COMPARATOR,
                "<yellow>Elections</yellow>",
                listOf("<gray>Force start/end, settings, and overrides.</gray>")
            )
            inv.setItem(12, item)
            set(12, item) { p -> plugin.gui.open(p, AdminElectionMenu(plugin)) }
        }

        if (canCandidates) {
            val item = icon(Material.NAME_TAG, "<red>Candidates</red>", listOf("<gray>Manage candidate status and bans.</gray>"))
            inv.setItem(14, item)
            set(14, item) { p -> plugin.gui.open(p, AdminCandidatesMenu(plugin)) }
        }

        if (canPerks) {
            val item = icon(Material.BOOK, "<gold>Perks</gold>", listOf("<gray>Catalog, requests, and refresh.</gray>"))
            inv.setItem(16, item)
            set(16, item) { p -> plugin.gui.open(p, AdminPerksMenu(plugin)) }
        }

        if (canMonitoring) {
            val item = icon(Material.SPYGLASS, "<white>Monitoring</white>", listOf("<gray>Audit logs and health checks.</gray>"))
            inv.setItem(28, item)
            set(28, item) { p -> plugin.gui.open(p, AdminMonitoringMenu(plugin)) }
        }

        if (canMaintenance) {
            val item = icon(Material.ANVIL, "<gray>Maintenance</gray>", listOf("<gray>Reloads and admin utilities.</gray>"))
            inv.setItem(30, item)
            set(30, item) { p -> plugin.gui.open(p, AdminDebugMenu(plugin)) }
        }

        inv.setItem(40, icon(Material.ARROW, "<gray><- Back</gray>"))
        set(40, inv.getItem(40)!!) { p -> plugin.gui.open(p, MainMenu(plugin)) }
    }
}

