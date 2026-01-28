package mayorSystem.cloud

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import mayorSystem.ui.menus.AdminApplyBanSearchMenu
import mayorSystem.ui.menus.AdminAuditMenu
import mayorSystem.ui.menus.AdminBonusTermMenu
import mayorSystem.ui.menus.AdminCandidatesMenu
import mayorSystem.ui.menus.AdminElectionMenu
import mayorSystem.ui.menus.AdminElectionSettingsMenu
import mayorSystem.ui.menus.AdminForceElectMenu
import mayorSystem.ui.menus.AdminHealthMenu
import mayorSystem.ui.menus.AdminMenu
import mayorSystem.ui.menus.AdminPerkCatalogMenu
import mayorSystem.ui.menus.AdminPerkRefreshMenu
import mayorSystem.ui.menus.AdminPerkRequestsMenu
import mayorSystem.ui.menus.AdminSettingsApplyMenu
import mayorSystem.ui.menus.AdminSettingsChatPromptsMenu
import mayorSystem.ui.menus.AdminSettingsCustomRequestsMenu
import mayorSystem.ui.menus.AdminSettingsGeneralMenu
import mayorSystem.ui.menus.AdminSettingsMenu
import mayorSystem.ui.menus.AdminSettingsTermMenu
import mayorSystem.ui.menus.AdminSettingsTermExtrasMenu

enum class AdminMenuId(val id: String, val factory: (MayorPlugin) -> Menu) {
    ADMIN("ADMIN", { AdminMenu(it) }),
    CANDIDATES("CANDIDATES", { AdminCandidatesMenu(it) }),
    APPLYBAN("APPLYBAN", { AdminApplyBanSearchMenu(it) }),
    PERKS_CATALOG("PERKS_CATALOG", { AdminPerkCatalogMenu(it) }),
    PERK_REQUESTS("PERK_REQUESTS", { AdminPerkRequestsMenu(it) }),
    PERKS_REFRESH("PERKS_REFRESH", { AdminPerkRefreshMenu(it) }),
    ELECTION("ELECTION", { AdminElectionMenu(it) }),
    FORCE_ELECT("FORCE_ELECT", { AdminForceElectMenu(it) }),
    SETTINGS("SETTINGS", { AdminSettingsMenu(it) }),
    SETTINGS_GENERAL("SETTINGS_GENERAL", { AdminSettingsGeneralMenu(it) }),
    SETTINGS_TERM("SETTINGS_TERM", { AdminSettingsTermMenu(it) }),
    SETTINGS_TERM_EXTRAS("SETTINGS_TERM_EXTRAS", { AdminSettingsTermExtrasMenu(it) }),
    SETTINGS_APPLY("SETTINGS_APPLY", { AdminSettingsApplyMenu(it) }),
    SETTINGS_CUSTOM("SETTINGS_CUSTOM", { AdminSettingsCustomRequestsMenu(it) }),
    SETTINGS_CHAT("SETTINGS_CHAT", { AdminSettingsChatPromptsMenu(it) }),
    SETTINGS_ELECTION("SETTINGS_ELECTION", { AdminElectionSettingsMenu(it) }),
    BONUS_TERM("BONUS_TERM", { AdminBonusTermMenu(it) }),
    AUDIT("AUDIT", { AdminAuditMenu(it) }),
    HEALTH("HEALTH", { AdminHealthMenu(it) })
    ;

    companion object {
        fun fromId(input: String): AdminMenuId? = values().firstOrNull {
            it.id.equals(input, ignoreCase = true) || it.name.equals(input, ignoreCase = true)
        }

        fun ids(): List<String> = values().map { it.id }
    }
}
