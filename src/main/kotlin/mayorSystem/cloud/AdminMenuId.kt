package mayorSystem.cloud

import mayorSystem.MayorPlugin
import mayorSystem.candidates.ui.AdminApplyBanSearchMenu
import mayorSystem.candidates.ui.AdminCandidatesMenu
import mayorSystem.candidates.ui.AdminSettingsApplyMenu
import mayorSystem.economy.ui.AdminEconomyMenu
import mayorSystem.economy.ui.AdminSettingsSellBonusesMenu
import mayorSystem.elections.ui.AdminElectionMenu
import mayorSystem.elections.ui.AdminElectionSettingsMenu
import mayorSystem.elections.ui.AdminForceElectMenu
import mayorSystem.elections.ui.AdminSettingsTermMenu
import mayorSystem.governance.ui.AdminBonusTermMenu
import mayorSystem.governance.ui.GovernanceSettingsMenu
import mayorSystem.maintenance.ui.AdminDebugMenu
import mayorSystem.messaging.ui.AdminMessagingMenu
import mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu
import mayorSystem.monitoring.ui.AdminAuditMenu
import mayorSystem.monitoring.ui.AdminHealthMenu
import mayorSystem.monitoring.ui.AdminMonitoringMenu
import mayorSystem.perks.ui.AdminPerkCatalogMenu
import mayorSystem.perks.ui.AdminPerkRefreshMenu
import mayorSystem.perks.ui.AdminPerkRequestsMenu
import mayorSystem.perks.ui.AdminPerksMenu
import mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminMenu
import mayorSystem.system.ui.AdminSettingsGeneralMenu
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu

enum class AdminMenuId(
    val id: String,
    val requiredPerms: List<String>,
    val factory: (MayorPlugin) -> Menu
) {
    ADMIN(
        "ADMIN",
        listOf(Perms.ADMIN_ACCESS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminMenu(it) }
    ),
    SYSTEM(
        "SYSTEM",
        listOf(
            Perms.ADMIN_SYSTEM_TOGGLE,
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminSettingsGeneralMenu(it) }
    ),
    GOVERNANCE(
        "GOVERNANCE",
        listOf(
            Perms.ADMIN_GOVERNANCE_EDIT,
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { GovernanceSettingsMenu(it) }
    ),
    ELECTION(
        "ELECTION",
        listOf(
            Perms.ADMIN_ELECTION_START,
            Perms.ADMIN_ELECTION_END,
            Perms.ADMIN_ELECTION_CLEAR,
            Perms.ADMIN_ELECTION_ELECT,
            Perms.LEGACY_ADMIN_ELECTION,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminElectionMenu(it) }
    ),
    ELECTION_SETTINGS(
        "ELECTION_SETTINGS",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminElectionSettingsMenu(it) }
    ),
    ELECTION_TERM(
        "ELECTION_TERM",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminSettingsTermMenu(it) }
    ),
    FORCE_ELECT(
        "FORCE_ELECT",
        listOf(Perms.ADMIN_ELECTION_ELECT, Perms.LEGACY_ADMIN_ELECTION, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminForceElectMenu(it) }
    ),
    CANDIDATES(
        "CANDIDATES",
        listOf(
            Perms.ADMIN_CANDIDATES_REMOVE,
            Perms.ADMIN_CANDIDATES_RESTORE,
            Perms.ADMIN_CANDIDATES_PROCESS,
            Perms.ADMIN_CANDIDATES_APPLYBAN,
            Perms.LEGACY_ADMIN_CANDIDATES,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminCandidatesMenu(it) }
    ),
    APPLYBAN(
        "APPLYBAN",
        listOf(
            Perms.ADMIN_CANDIDATES_APPLYBAN,
            Perms.LEGACY_ADMIN_CANDIDATES,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminApplyBanSearchMenu(it) }
    ),
    PERKS(
        "PERKS",
        listOf(
            Perms.ADMIN_PERKS_CATALOG,
            Perms.ADMIN_PERKS_REQUESTS,
            Perms.ADMIN_PERKS_REFRESH,
            Perms.LEGACY_ADMIN_PERKS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminPerksMenu(it) }
    ),
    PERKS_CATALOG(
        "PERKS_CATALOG",
        listOf(Perms.ADMIN_PERKS_CATALOG, Perms.LEGACY_ADMIN_PERKS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminPerkCatalogMenu(it) }
    ),
    PERK_REQUESTS(
        "PERK_REQUESTS",
        listOf(Perms.ADMIN_PERKS_REQUESTS, Perms.LEGACY_ADMIN_PERKS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminPerkRequestsMenu(it) }
    ),
    PERKS_REFRESH(
        "PERKS_REFRESH",
        listOf(Perms.ADMIN_PERKS_REFRESH, Perms.LEGACY_ADMIN_PERKS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminPerkRefreshMenu(it) }
    ),
    ECONOMY(
        "ECONOMY",
        listOf(
            Perms.ADMIN_ECONOMY_EDIT,
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminEconomyMenu(it) }
    ),
    MESSAGING(
        "MESSAGING",
        listOf(
            Perms.ADMIN_MESSAGING_EDIT,
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminMessagingMenu(it) }
    ),
    MONITORING(
        "MONITORING",
        listOf(
            Perms.ADMIN_AUDIT_VIEW,
            Perms.ADMIN_HEALTH_VIEW,
            Perms.LEGACY_ADMIN_AUDIT,
            Perms.LEGACY_ADMIN_HEALTH,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminMonitoringMenu(it) }
    ),
    MAINTENANCE(
        "MAINTENANCE",
        listOf(
            Perms.ADMIN_MAINTENANCE_RELOAD,
            Perms.ADMIN_MAINTENANCE_DEBUG,
            Perms.ADMIN_SETTINGS_RELOAD,
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminDebugMenu(it) }
    ),
    SETTINGS(
        "SETTINGS",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_SYSTEM_TOGGLE,
            Perms.ADMIN_GOVERNANCE_EDIT,
            Perms.ADMIN_MESSAGING_EDIT,
            Perms.ADMIN_ECONOMY_EDIT,
            Perms.ADMIN_PERKS_CATALOG,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_PERKS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminSettingsMenu(it) }
    ),
    SETTINGS_GENERAL(
        "SETTINGS_GENERAL",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_SYSTEM_TOGGLE,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminSettingsGeneralMenu(it) }
    ),
    SETTINGS_TERM(
        "SETTINGS_TERM",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminSettingsTermMenu(it) }
    ),
    SETTINGS_TERM_EXTRAS(
        "SETTINGS_TERM_EXTRAS",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_GOVERNANCE_EDIT,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { GovernanceSettingsMenu(it) }
    ),
    SETTINGS_APPLY(
        "SETTINGS_APPLY",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminSettingsApplyMenu(it) }
    ),
    SETTINGS_CUSTOM(
        "SETTINGS_CUSTOM",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminSettingsCustomRequestsMenu(it) }
    ),
    SETTINGS_CHAT(
        "SETTINGS_CHAT",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_MESSAGING_EDIT,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminSettingsChatPromptsMenu(it) }
    ),
    SETTINGS_ELECTION(
        "SETTINGS_ELECTION",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminElectionSettingsMenu(it) }
    ),
    BONUS_TERM(
        "BONUS_TERM",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_GOVERNANCE_EDIT,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminBonusTermMenu(it) }
    ),
    AUDIT(
        "AUDIT",
        listOf(Perms.ADMIN_AUDIT_VIEW, Perms.LEGACY_ADMIN_AUDIT, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminAuditMenu(it) }
    ),
    HEALTH(
        "HEALTH",
        listOf(Perms.ADMIN_HEALTH_VIEW, Perms.LEGACY_ADMIN_HEALTH, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminHealthMenu(it) }
    ),
    DEBUG(
        "DEBUG",
        listOf(
            Perms.ADMIN_MAINTENANCE_RELOAD,
            Perms.ADMIN_MAINTENANCE_DEBUG,
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_SETTINGS_RELOAD,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminDebugMenu(it) }
    )
    ;

    companion object {
        fun fromId(input: String): AdminMenuId? = values().firstOrNull {
            it.id.equals(input, ignoreCase = true) || it.name.equals(input, ignoreCase = true)
        }

        fun ids(): List<String> = values().map { it.id }
    }

    fun canOpen(player: org.bukkit.entity.Player): Boolean =
        requiredPerms.any { player.hasPermission(it) }
}

