package mayorSystem.cloud

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.ui.menus.AdminApplyBanSearchMenu
import mayorSystem.ui.menus.AdminAuditMenu
import mayorSystem.ui.menus.AdminBonusTermMenu
import mayorSystem.ui.menus.AdminCandidatesMenu
import mayorSystem.ui.menus.AdminDebugMenu
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
    FORCE_ELECT(
        "FORCE_ELECT",
        listOf(Perms.ADMIN_ELECTION_ELECT, Perms.LEGACY_ADMIN_ELECTION, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminForceElectMenu(it) }
    ),
    SETTINGS(
        "SETTINGS",
        listOf(
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.ADMIN_PERKS_CATALOG,
            Perms.LEGACY_ADMIN_SETTINGS,
            Perms.LEGACY_ADMIN_PERKS,
            Perms.LEGACY_ADMIN_UMBRELLA
        ),
        { AdminSettingsMenu(it) }
    ),
    SETTINGS_GENERAL(
        "SETTINGS_GENERAL",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminSettingsGeneralMenu(it) }
    ),
    SETTINGS_TERM(
        "SETTINGS_TERM",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminSettingsTermMenu(it) }
    ),
    SETTINGS_TERM_EXTRAS(
        "SETTINGS_TERM_EXTRAS",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminSettingsTermExtrasMenu(it) }
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
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminSettingsChatPromptsMenu(it) }
    ),
    SETTINGS_ELECTION(
        "SETTINGS_ELECTION",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
        { AdminElectionSettingsMenu(it) }
    ),
    BONUS_TERM(
        "BONUS_TERM",
        listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.LEGACY_ADMIN_SETTINGS, Perms.LEGACY_ADMIN_UMBRELLA),
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
            Perms.ADMIN_AUDIT_VIEW,
            Perms.ADMIN_HEALTH_VIEW,
            Perms.ADMIN_SETTINGS_EDIT,
            Perms.LEGACY_ADMIN_AUDIT,
            Perms.LEGACY_ADMIN_HEALTH,
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
