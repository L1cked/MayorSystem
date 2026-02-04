package mayorSystem.system.ui

import mayorSystem.MayorPlugin
import mayorSystem.candidates.ui.AdminSettingsApplyMenu
import mayorSystem.economy.ui.AdminSettingsSellBonusesMenu
import mayorSystem.elections.ui.AdminElectionSettingsMenu
import mayorSystem.elections.ui.AdminSettingsTermMenu
import mayorSystem.governance.ui.GovernanceSettingsMenu
import mayorSystem.messaging.ui.AdminBroadcastSettingsMenu
import mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu
import mayorSystem.perks.ui.AdminPerkCatalogMenu
import mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminSettingsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Admin Settings</gradient>")
    override val rows: Int = 5

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val canSystem = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
                || player.hasPermission(Perms.ADMIN_SYSTEM_TOGGLE)
        val canGovernance = player.hasPermission(Perms.ADMIN_GOVERNANCE_EDIT)
                || player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        val canTerm = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        val canElectionRules = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        val canApply = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        val canCustomRequests = player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        val canMessaging = player.hasPermission(Perms.ADMIN_MESSAGING_EDIT)
                || player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        val canEconomy = player.hasPermission(Perms.ADMIN_ECONOMY_EDIT)
                || player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)
        val canCatalog = player.hasPermission(Perms.ADMIN_PERKS_CATALOG)

        val canAccess = canSystem
                || canGovernance
                || canTerm
                || canElectionRules
                || canApply
                || canCustomRequests
                || canMessaging
                || canEconomy
                || canCatalog

        if (!canAccess) {
            inv.setItem(
                13,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have permission to access admin settings.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray><- Back</gray>")
            inv.setItem(36, back)
            set(36, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        if (canSystem) {
            val general = icon(
                Material.REDSTONE_TORCH,
                "<yellow>System</yellow>",
                listOf("<gray>Enable/disable core features.</gray>")
            )
            inv.setItem(10, general)
            set(10, general) { p -> plugin.gui.open(p, AdminSettingsGeneralMenu(plugin)) }
        }

        if (canGovernance) {
            val governance = icon(
                Material.BELL,
                "<yellow>Governance</yellow>",
                listOf("<gray>Tie policy, stepdown, bonus terms.</gray>")
            )
            inv.setItem(12, governance)
            set(12, governance) { p -> plugin.gui.open(p, GovernanceSettingsMenu(plugin)) }
        }

        if (canTerm) {
            val term = icon(
                Material.CLOCK,
                "<yellow>Term & Schedule</yellow>",
                listOf("<gray>Term length, vote window, start time.</gray>")
            )
            inv.setItem(14, term)
            set(14, term) { p -> plugin.gui.open(p, AdminSettingsTermMenu(plugin)) }
        }

        if (canElectionRules) {
            val electionRules = icon(
                Material.TARGET,
                "<yellow>Election Rules</yellow>",
                listOf("<gray>Vote change rules and stepdown re-apply.</gray>")
            )
            inv.setItem(16, electionRules)
            set(16, electionRules) { p -> plugin.gui.open(p, AdminElectionSettingsMenu(plugin)) }
        }

        if (canApply) {
            val apply = icon(
                Material.EMERALD,
                "<yellow>Apply Requirements</yellow>",
                listOf("<gray>Playtime requirements and application cost.</gray>")
            )
            inv.setItem(19, apply)
            set(19, apply) { p -> plugin.gui.open(p, AdminSettingsApplyMenu(plugin)) }
        }

        if (canCustomRequests) {
            val customReq = icon(
                Material.COMPARATOR,
                "<yellow>Custom Requests</yellow>",
                listOf("<gray>Who can request perks and how many per term.</gray>")
            )
            inv.setItem(21, customReq)
            set(21, customReq) { p -> plugin.gui.open(p, AdminSettingsCustomRequestsMenu(plugin)) }
        }

        if (canMessaging) {
            val prompts = icon(
                Material.WRITABLE_BOOK,
                "<yellow>Chat Prompts</yellow>",
                listOf("<gray>Max lengths for bio and request fields.</gray>")
            )
            inv.setItem(23, prompts)
            set(23, prompts) { p -> plugin.gui.open(p, AdminSettingsChatPromptsMenu(plugin)) }

            val broadcasts = icon(
                Material.BELL,
                "<yellow>Election Broadcasts</yellow>",
                listOf("<gray>Toggle election announcements.</gray>")
            )
            inv.setItem(25, broadcasts)
            set(25, broadcasts) { p -> plugin.gui.open(p, AdminBroadcastSettingsMenu(plugin)) }
        }

        if (canEconomy) {
            val sellBonuses = icon(
                Material.GOLD_INGOT,
                "<yellow>Sell Bonuses</yellow>",
                listOf("<gray>Configure /sell bonus stacking.</gray>")
            )
            inv.setItem(28, sellBonuses)
            set(28, sellBonuses) { p -> plugin.gui.open(p, AdminSettingsSellBonusesMenu(plugin)) }
        }

        if (canCatalog) {
            val catalog = icon(
                Material.CHEST,
                "<gold>Perk Catalog</gold>",
                listOf("<gray>Enable/disable sections and perks.</gray>")
            )
            inv.setItem(30, catalog)
            set(30, catalog) { p -> plugin.gui.open(p, AdminPerkCatalogMenu(plugin)) }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(36, back)
        set(36, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }
}

