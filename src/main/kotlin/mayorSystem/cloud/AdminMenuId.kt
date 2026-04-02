package mayorSystem.cloud

import mayorSystem.MayorPlugin
import mayorSystem.candidates.ui.AdminApplyBanSearchMenu
import mayorSystem.candidates.ui.AdminCandidatesMenu
import mayorSystem.candidates.ui.AdminSettingsApplyMenu
import mayorSystem.elections.ui.AdminElectionMenu
import mayorSystem.elections.ui.AdminFakeVotesMenu
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
import mayorSystem.system.ui.AdminSettingsMayorGroupMenu
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu

enum class AdminMenuId(
    val id: String,
    val requiredPerms: List<String>,
    val factory: (MayorPlugin) -> Menu
) {
    ADMIN(
        "ADMIN",
        listOf(Perms.ADMIN_ACCESS),
        { AdminMenu(it) }
    ),
    SYSTEM(
        "SYSTEM",
        listOf(
            Perms.ADMIN_SYSTEM_TOGGLE,
            Perms.ADMIN_SETTINGS_EDIT
        ),
        { AdminSettingsGeneralMenu(it) }
    ),
    GOVERNANCE(
        "GOVERNANCE",
        listOf(
            Perms.ADMIN_GOVERNANCE_EDIT,
            Perms.ADMIN_SETTINGS_EDIT
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
            Perms.ADMIN_ELECTION_FAKE_VOTES
        ),
        { AdminElectionMenu(it) }
    ),
    ELECTION_SETTINGS(
        "ELECTION_SETTINGS",
        listOf(Perms.ADMIN_SETTINGS_EDIT),
        { AdminElectionSettingsMenu(it) }
    ),
    ELECTION_TERM(
        "ELECTION_TERM",
        listOf(Perms.ADMIN_SETTINGS_EDIT),
        { AdminSettingsTermMenu(it) }
    ),
    FORCE_ELECT(
        "FORCE_ELECT",
        listOf(Perms.ADMIN_ELECTION_ELECT),
        { AdminForceElectMenu(it) }
    ),
    FAKE_VOTES(
        "FAKE_VOTES",
        listOf(Perms.ADMIN_ELECTION_FAKE_VOTES),
        { AdminFakeVotesMenu(it, it.termService.computeNow().second) }
    ),
    CANDIDATES(
        "CANDIDATES",
        listOf(
            Perms.ADMIN_CANDIDATES_REMOVE,
            Perms.ADMIN_CANDIDATES_RESTORE,
            Perms.ADMIN_CANDIDATES_PROCESS,
            Perms.ADMIN_CANDIDATES_APPLYBAN
        ),
        { AdminCandidatesMenu(it) }
    ),
    APPLYBAN(
        "APPLYBAN",
        listOf(
            Perms.ADMIN_CANDIDATES_APPLYBAN
        ),
        { AdminApplyBanSearchMenu(it) }
    ),
    PERKS(
        "PERKS",
        listOf(
            Perms.ADMIN_PERKS_CATALOG,
            Perms.ADMIN_PERKS_REQUESTS,
            Perms.ADMIN_PERKS_REFRESH
        ),
        { AdminPerksMenu(it) }
    ),
    PERKS_CATALOG(
        "PERKS_CATALOG",
        listOf(Perms.ADMIN_PERKS_CATALOG),
        { AdminPerkCatalogMenu(it) }
    ),
    PERK_REQUESTS(
        "PERK_REQUESTS",
        listOf(Perms.ADMIN_PERKS_REQUESTS),
        { AdminPerkRequestsMenu(it) }
    ),
    PERKS_REFRESH(
        "PERKS_REFRESH",
        listOf(Perms.ADMIN_PERKS_REFRESH),
        { AdminPerkRefreshMenu(it) }
    ),
    MESSAGING(
        "MESSAGING",
        listOf(
            Perms.ADMIN_MESSAGING_EDIT,
            Perms.ADMIN_SETTINGS_EDIT
        ),
        { AdminMessagingMenu(it) }
    ),
    MONITORING(
        "MONITORING",
        listOf(
            Perms.ADMIN_AUDIT_VIEW,
            Perms.ADMIN_HEALTH_VIEW
        ),
        { AdminMonitoringMenu(it) }
    ),
    MAINTENANCE(
        "MAINTENANCE",
        listOf(
            Perms.ADMIN_MAINTENANCE_RELOAD,
            Perms.ADMIN_MAINTENANCE_DEBUG,
            Perms.ADMIN_SETTINGS_RELOAD
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
            Perms.ADMIN_PERKS_CATALOG
        ),
        { AdminSettingsMenu(it) }
    ),
    SETTINGS_GENERAL(
        "SETTINGS_GENERAL",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_SYSTEM_TOGGLE
        ),
        { AdminSettingsGeneralMenu(it) }
    ),
    SETTINGS_MAYOR_GROUP(
        "SETTINGS_MAYOR_GROUP",
        listOf(Perms.ADMIN_SETTINGS_EDIT),
        { AdminSettingsMayorGroupMenu(it) }
    ),
    SETTINGS_TERM(
        "SETTINGS_TERM",
        listOf(Perms.ADMIN_SETTINGS_EDIT),
        { AdminSettingsTermMenu(it) }
    ),
    SETTINGS_TERM_EXTRAS(
        "SETTINGS_TERM_EXTRAS",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_GOVERNANCE_EDIT
        ),
        { GovernanceSettingsMenu(it) }
    ),
    SETTINGS_APPLY(
        "SETTINGS_APPLY",
        listOf(Perms.ADMIN_SETTINGS_EDIT),
        { AdminSettingsApplyMenu(it) }
    ),
    SETTINGS_CUSTOM(
        "SETTINGS_CUSTOM",
        listOf(Perms.ADMIN_SETTINGS_EDIT),
        { AdminSettingsCustomRequestsMenu(it) }
    ),
    SETTINGS_CHAT(
        "SETTINGS_CHAT",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_MESSAGING_EDIT
        ),
        { AdminSettingsChatPromptsMenu(it) }
    ),
    SETTINGS_ELECTION(
        "SETTINGS_ELECTION",
        listOf(Perms.ADMIN_SETTINGS_EDIT),
        { AdminElectionSettingsMenu(it) }
    ),
    BONUS_TERM(
        "BONUS_TERM",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_GOVERNANCE_EDIT
        ),
        { AdminBonusTermMenu(it) }
    ),
    AUDIT(
        "AUDIT",
        listOf(Perms.ADMIN_AUDIT_VIEW),
        { AdminAuditMenu(it) }
    ),
    HEALTH(
        "HEALTH",
        listOf(Perms.ADMIN_HEALTH_VIEW),
        { AdminHealthMenu(it) }
    ),
    DEBUG(
        "DEBUG",
        listOf(
            Perms.ADMIN_MAINTENANCE_RELOAD,
            Perms.ADMIN_MAINTENANCE_DEBUG,
            Perms.ADMIN_SETTINGS_RELOAD
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

