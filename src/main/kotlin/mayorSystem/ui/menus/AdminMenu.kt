package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminMenu(plugin: MayorPlugin) : Menu(plugin) {
    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>🛡 Admin Panel</gradient>")
    override val rows: Int = 5

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        // Layout:
        // - Keep glass-pane border.
        // - Move icons one row higher (favor top interior rows).
        // - Even spacing: no adjacent icons.
        //
        // Interior slots (5 rows with border):
        // Row 2: 10, 12, 14, 16
        // Row 3: 20, 22, 24

        val canCandidates = player.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE)
                || player.hasPermission(Perms.ADMIN_CANDIDATES_RESTORE)
                || player.hasPermission(Perms.ADMIN_CANDIDATES_PROCESS)
                || player.hasPermission(Perms.ADMIN_CANDIDATES_APPLYBAN)
                || player.hasPermission(Perms.LEGACY_ADMIN_CANDIDATES)

        val canPerkRequests = player.hasPermission(Perms.ADMIN_PERKS_REQUESTS)
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)
        val canPerkRefresh = player.hasPermission(Perms.ADMIN_PERKS_REFRESH)
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)

        val canElection = player.hasPermission(Perms.ADMIN_ELECTION_START)
                || player.hasPermission(Perms.ADMIN_ELECTION_END)
                || player.hasPermission(Perms.ADMIN_ELECTION_CLEAR)
                || player.hasPermission(Perms.ADMIN_ELECTION_ELECT)
                || player.hasPermission(Perms.LEGACY_ADMIN_ELECTION)

        val canSettings = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
                || player.hasPermission(Perms.ADMIN_SETTINGS_RELOAD)
                || player.hasPermission(Perms.LEGACY_ADMIN_SETTINGS)

        val canAudit = player.hasPermission(Perms.ADMIN_AUDIT_VIEW)
                || player.hasPermission(Perms.LEGACY_ADMIN_AUDIT)

        val canHealth = player.hasPermission(Perms.ADMIN_HEALTH_VIEW)
                || player.hasPermission(Perms.LEGACY_ADMIN_HEALTH)

        if (canCandidates) {
            val item = icon(Material.NAME_TAG, "<red>Candidates</red>", listOf("<gray>Remove/restore candidates.</gray>"))
            inv.setItem(10, item)
            set(10, item) { p -> plugin.gui.open(p, AdminCandidatesMenu(plugin)) }
        }

        if (canPerkRequests) {
            val item = icon(Material.BOOK, "<gold>Perk Requests</gold>", listOf("<gray>Approve/deny custom perks.</gray>"))
            inv.setItem(12, item)
            set(12, item) { p -> plugin.gui.open(p, AdminPerkRequestsMenu(plugin)) }
        }

        if (canPerkRefresh) {
            val refresh = icon(
                Material.NETHER_STAR,
                "<green>Refresh Perks</green>",
                listOf(
                    "<gray>Click:</gray> <white>open refresh menu</white>",
                    "",
                    "<dark_gray>Re-applies active perk potion effects.</dark_gray>",
                    "<dark_gray>Does not re-run perk commands.</dark_gray>"
                )
            )
            inv.setItem(14, refresh)
            set(14, refresh) { p ->
                plugin.gui.open(p, AdminPerkRefreshMenu(plugin))
            }
        }

        if (canElection) {
            val item = icon(
                Material.COMPARATOR,
                "<yellow>Election Controls</yellow>",
                listOf(
                    "<gray>Force start/end election,</gray>",
                    "<gray>or force-elect a player.</gray>"
                )
            )
            inv.setItem(16, item)
            set(16, item) { p -> plugin.gui.open(p, AdminElectionMenu(plugin)) }
        }

        if (canSettings) {
            val item = icon(Material.REPEATER, "<yellow>Settings</yellow>", listOf("<gray>Edit settings with menus.</gray>"))
            inv.setItem(20, item)
            set(20, item) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
        }

        if (canAudit) {
            val item = icon(
                Material.PAPER,
                "<light_purple>Audit Log</light_purple>",
                listOf(
                    "<gray>See who changed what and when.</gray>",
                    "<dark_gray>Exportable.</dark_gray>"
                )
            )
            inv.setItem(22, item)
            set(22, item) { p -> plugin.gui.open(p, AdminAuditMenu(plugin)) }
        }

        if (canHealth) {
            val item = icon(
                Material.SPYGLASS,
                "<green>Health Check</green>",
                listOf(
                    "<gray>Self-diagnose common issues.</gray>",
                    "<dark_gray>Config, perks, economy, store.</dark_gray>"
                )
            )
            inv.setItem(24, item)
            set(24, item) { p -> plugin.gui.open(p, AdminHealthMenu(plugin)) }
        }

        inv.setItem(36, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(36, inv.getItem(36)!!) { p -> plugin.gui.open(p, MainMenu(plugin)) }
    }
}
