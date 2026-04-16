package mayorSystem.ui

import mayorSystem.candidates.ui.AdminApplyBanDurationMenu
import mayorSystem.candidates.ui.AdminApplyBanSearchMenu
import mayorSystem.candidates.ui.AdminApplyBanTypeMenu
import mayorSystem.candidates.ui.AdminCandidatesMenu
import mayorSystem.candidates.ui.AdminSettingsApplyMenu
import mayorSystem.candidates.ui.ConfirmRemoveCandidateMenu
import mayorSystem.elections.ui.AdminElectionMenu
import mayorSystem.elections.ui.AdminElectionSettingsMenu
import mayorSystem.elections.ui.AdminFakeVoteAdjustMenu
import mayorSystem.elections.ui.AdminFakeVotesMenu
import mayorSystem.elections.ui.AdminForceElectConfirmMenu
import mayorSystem.elections.ui.AdminForceElectMenu
import mayorSystem.elections.ui.AdminForceElectPerksMenu
import mayorSystem.elections.ui.AdminForceElectSectionsMenu
import mayorSystem.elections.ui.AdminSettingsTermMenu
import mayorSystem.governance.ui.AdminBonusTermMenu
import mayorSystem.governance.ui.GovernanceSettingsMenu
import mayorSystem.maintenance.ui.AdminDebugMenu
import mayorSystem.maintenance.ui.AdminResetElectionConfirmMenu
import mayorSystem.messaging.ui.AdminBroadcastSettingsMenu
import mayorSystem.messaging.ui.AdminMessagingMenu
import mayorSystem.messaging.ui.AdminSettingsChatPromptsMenu
import mayorSystem.monitoring.ui.AdminAuditMenu
import mayorSystem.monitoring.ui.AdminHealthMenu
import mayorSystem.monitoring.ui.AdminMonitoringMenu
import mayorSystem.perks.ui.AdminPerkCatalogMenu
import mayorSystem.perks.ui.AdminPerkRefreshMenu
import mayorSystem.perks.ui.AdminPerkRequestsMenu
import mayorSystem.perks.ui.AdminPerkSectionMenu
import mayorSystem.perks.ui.AdminPerksMenu
import mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu
import mayorSystem.security.Perms
import mayorSystem.system.ui.AdminDisplayMenu
import mayorSystem.system.ui.AdminMenu
import mayorSystem.system.ui.AdminSettingsEnableOptionsMenu
import mayorSystem.system.ui.AdminSettingsGeneralMenu
import mayorSystem.system.ui.AdminSettingsMayorGroupMenu
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.system.ui.AdminSettingsPauseOptionsMenu
import org.bukkit.entity.Player

object AdminMenuAccess {
    fun isAdminMenu(menu: Menu): Boolean = when (menu) {
        is AdminMenu,
        is AdminSettingsMenu,
        is AdminSettingsGeneralMenu,
        is AdminSettingsEnableOptionsMenu,
        is AdminSettingsPauseOptionsMenu,
        is AdminSettingsMayorGroupMenu,
        is AdminSettingsApplyMenu,
        is AdminElectionSettingsMenu,
        is AdminSettingsTermMenu,
        is GovernanceSettingsMenu,
        is AdminBonusTermMenu,
        is AdminMessagingMenu,
        is AdminSettingsChatPromptsMenu,
        is AdminBroadcastSettingsMenu,
        is AdminDisplayMenu,
        is AdminCandidatesMenu,
        is ConfirmRemoveCandidateMenu,
        is AdminApplyBanSearchMenu,
        is AdminApplyBanTypeMenu,
        is AdminApplyBanDurationMenu,
        is AdminElectionMenu,
        is AdminFakeVotesMenu,
        is AdminFakeVoteAdjustMenu,
        is AdminForceElectMenu,
        is AdminForceElectSectionsMenu,
        is AdminForceElectPerksMenu,
        is AdminForceElectConfirmMenu,
        is AdminPerksMenu,
        is AdminPerkCatalogMenu,
        is AdminPerkSectionMenu,
        is AdminPerkRequestsMenu,
        is AdminPerkRefreshMenu,
        is AdminSettingsCustomRequestsMenu,
        is AdminMonitoringMenu,
        is AdminAuditMenu,
        is AdminHealthMenu,
        is AdminDebugMenu,
        is AdminResetElectionConfirmMenu -> true

        else -> false
    }

    fun canOpen(player: Player, menu: Menu): Boolean = when (menu) {
        is AdminMenu -> Perms.canOpenAdminPanel(player)
        is AdminSettingsMenu -> Perms.hasAny(player, Perms.ADMIN_SETTINGS_MENU_PERMS)
        is AdminSettingsGeneralMenu -> Perms.hasAny(player, listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.ADMIN_SYSTEM_TOGGLE))
        is AdminSettingsEnableOptionsMenu,
        is AdminSettingsPauseOptionsMenu,
        is AdminSettingsMayorGroupMenu,
        is AdminSettingsApplyMenu,
        is AdminElectionSettingsMenu,
        is AdminSettingsTermMenu,
        is AdminSettingsCustomRequestsMenu -> player.hasPermission(Perms.ADMIN_SETTINGS_EDIT)

        is GovernanceSettingsMenu,
        is AdminBonusTermMenu -> Perms.hasAny(player, Perms.ADMIN_GOVERNANCE_PERMS)

        is AdminMessagingMenu,
        is AdminSettingsChatPromptsMenu,
        is AdminBroadcastSettingsMenu -> Perms.hasAny(player, Perms.ADMIN_MESSAGING_PERMS)

        is AdminDisplayMenu -> Perms.hasAny(player, Perms.ADMIN_DISPLAY_PERMS)

        is AdminCandidatesMenu -> Perms.hasAny(player, Perms.ADMIN_CANDIDATE_PERMS)
        is ConfirmRemoveCandidateMenu -> player.hasPermission(Perms.ADMIN_CANDIDATES_REMOVE)

        is AdminApplyBanSearchMenu,
        is AdminApplyBanTypeMenu,
        is AdminApplyBanDurationMenu -> player.hasPermission(Perms.ADMIN_CANDIDATES_APPLYBAN)

        is AdminElectionMenu -> Perms.hasAny(player, Perms.ADMIN_ELECTION_PERMS)
        is AdminFakeVotesMenu,
        is AdminFakeVoteAdjustMenu -> player.hasPermission(Perms.ADMIN_ELECTION_FAKE_VOTES)
        is AdminForceElectMenu,
        is AdminForceElectSectionsMenu,
        is AdminForceElectPerksMenu,
        is AdminForceElectConfirmMenu -> player.hasPermission(Perms.ADMIN_ELECTION_ELECT)

        is AdminPerksMenu -> Perms.hasAny(player, Perms.ADMIN_PERK_PERMS)
        is AdminPerkCatalogMenu,
        is AdminPerkSectionMenu -> player.hasPermission(Perms.ADMIN_PERKS_CATALOG)
        is AdminPerkRequestsMenu -> player.hasPermission(Perms.ADMIN_PERKS_REQUESTS)
        is AdminPerkRefreshMenu -> player.hasPermission(Perms.ADMIN_PERKS_REFRESH)

        is AdminMonitoringMenu -> Perms.hasAny(player, Perms.ADMIN_MONITORING_PERMS)
        is AdminAuditMenu -> player.hasPermission(Perms.ADMIN_AUDIT_VIEW)
        is AdminHealthMenu -> player.hasPermission(Perms.ADMIN_HEALTH_VIEW)

        is AdminDebugMenu -> Perms.hasAny(player, Perms.ADMIN_MAINTENANCE_PERMS)
        is AdminResetElectionConfirmMenu -> player.hasPermission(Perms.ADMIN_MAINTENANCE_DEBUG)

        else -> true
    }
}
