package mayorSystem.security

import org.bukkit.entity.Player

/**
 * Central permission constants.
 *
 * Command structure convention:
 *   /mayor admin <section> <action> ...
 * Permission structure convention:
 *   mayor.admin.<section>.<action>
 */
object Perms {

    // Public/player commands
    const val USE = "mayor.use"
    const val APPLY = "mayor.apply"
    const val VOTE = "mayor.vote"
    const val CANDIDATE = "mayor.candidate"

    // Admin root access (granted as a child of any admin action permission)
    const val ADMIN_ACCESS = "mayor.admin.access"

    // Admin sections/actions
    const val ADMIN_SYSTEM_TOGGLE = "mayor.admin.system.toggle"

    const val ADMIN_CANDIDATES_REMOVE = "mayor.admin.candidates.remove"
    const val ADMIN_CANDIDATES_RESTORE = "mayor.admin.candidates.restore"
    const val ADMIN_CANDIDATES_PROCESS = "mayor.admin.candidates.process"
    const val ADMIN_CANDIDATES_APPLYBAN = "mayor.admin.candidates.applyban"

    const val ADMIN_PERKS_REFRESH = "mayor.admin.perks.refresh"
    const val ADMIN_PERKS_REQUESTS = "mayor.admin.perks.requests"
    const val ADMIN_PERKS_CATALOG = "mayor.admin.perks.catalog"

    const val ADMIN_GOVERNANCE_EDIT = "mayor.admin.governance.edit"
    const val ADMIN_MESSAGING_EDIT = "mayor.admin.messaging.edit"

    const val ADMIN_ELECTION_START = "mayor.admin.election.start"
    const val ADMIN_ELECTION_END = "mayor.admin.election.end"
    const val ADMIN_ELECTION_CLEAR = "mayor.admin.election.clear"
    const val ADMIN_ELECTION_ELECT = "mayor.admin.election.elect"
    const val ADMIN_ELECTION_FAKE_VOTES = "mayor.admin.election.fakevotes"

    const val ADMIN_SETTINGS_EDIT = "mayor.admin.settings.edit"
    const val ADMIN_SETTINGS_RELOAD = "mayor.admin.settings.reload"
    const val ADMIN_REWARD_VIEW = "mayor.admin.reward.view"
    const val ADMIN_REWARD_EDIT = "mayor.admin.reward.edit"

    const val ADMIN_MAINTENANCE_RELOAD = "mayor.admin.maintenance.reload"
    const val ADMIN_MAINTENANCE_DEBUG = "mayor.admin.maintenance.debug"

    const val ADMIN_AUDIT_VIEW = "mayor.admin.audit.view"
    const val ADMIN_HEALTH_VIEW = "mayor.admin.health.view"

    const val ADMIN_NPC_MAYOR = "mayor.admin.npc.mayor"
    const val ADMIN_HOLOGRAM_LEADERBOARD = "mayor.admin.hologram.leaderboard"

    val ADMIN_ACTION_PERMS: List<String> = listOf(
        ADMIN_SYSTEM_TOGGLE,
        ADMIN_CANDIDATES_REMOVE,
        ADMIN_CANDIDATES_RESTORE,
        ADMIN_CANDIDATES_PROCESS,
        ADMIN_CANDIDATES_APPLYBAN,
        ADMIN_PERKS_REFRESH,
        ADMIN_PERKS_REQUESTS,
        ADMIN_PERKS_CATALOG,
        ADMIN_GOVERNANCE_EDIT,
        ADMIN_MESSAGING_EDIT,
        ADMIN_ELECTION_START,
        ADMIN_ELECTION_END,
        ADMIN_ELECTION_CLEAR,
        ADMIN_ELECTION_ELECT,
        ADMIN_ELECTION_FAKE_VOTES,
        ADMIN_SETTINGS_EDIT,
        ADMIN_SETTINGS_RELOAD,
        ADMIN_REWARD_VIEW,
        ADMIN_REWARD_EDIT,
        ADMIN_MAINTENANCE_RELOAD,
        ADMIN_MAINTENANCE_DEBUG,
        ADMIN_AUDIT_VIEW,
        ADMIN_HEALTH_VIEW,
        ADMIN_NPC_MAYOR,
        ADMIN_HOLOGRAM_LEADERBOARD
    )

    val ADMIN_CANDIDATE_PERMS: List<String> = listOf(
        ADMIN_CANDIDATES_REMOVE,
        ADMIN_CANDIDATES_RESTORE,
        ADMIN_CANDIDATES_PROCESS,
        ADMIN_CANDIDATES_APPLYBAN
    )

    val ADMIN_ELECTION_PERMS: List<String> = listOf(
        ADMIN_ELECTION_START,
        ADMIN_ELECTION_END,
        ADMIN_ELECTION_CLEAR,
        ADMIN_ELECTION_ELECT,
        ADMIN_ELECTION_FAKE_VOTES
    )

    val ADMIN_PERK_PERMS: List<String> = listOf(
        ADMIN_PERKS_CATALOG,
        ADMIN_PERKS_REQUESTS,
        ADMIN_PERKS_REFRESH
    )

    val ADMIN_MESSAGING_PERMS: List<String> = listOf(
        ADMIN_MESSAGING_EDIT,
        ADMIN_SETTINGS_EDIT
    )

    val ADMIN_GOVERNANCE_PERMS: List<String> = listOf(
        ADMIN_GOVERNANCE_EDIT,
        ADMIN_SETTINGS_EDIT
    )

    val ADMIN_MONITORING_PERMS: List<String> = listOf(
        ADMIN_AUDIT_VIEW,
        ADMIN_HEALTH_VIEW
    )

    val ADMIN_MAINTENANCE_PERMS: List<String> = listOf(
        ADMIN_MAINTENANCE_RELOAD,
        ADMIN_MAINTENANCE_DEBUG,
        ADMIN_SETTINGS_RELOAD
    )

    val ADMIN_DISPLAY_PERMS: List<String> = listOf(
        ADMIN_SETTINGS_EDIT,
        ADMIN_NPC_MAYOR,
        ADMIN_HOLOGRAM_LEADERBOARD
    )

    val ADMIN_REWARD_PERMS: List<String> = listOf(
        ADMIN_REWARD_VIEW,
        ADMIN_REWARD_EDIT,
        ADMIN_SETTINGS_EDIT
    )

    val ADMIN_SETTINGS_MENU_PERMS: List<String> = listOf(
        ADMIN_SETTINGS_EDIT,
        ADMIN_SYSTEM_TOGGLE,
        ADMIN_GOVERNANCE_EDIT,
        ADMIN_MESSAGING_EDIT,
        ADMIN_PERKS_CATALOG,
        ADMIN_REWARD_VIEW,
        ADMIN_REWARD_EDIT,
        ADMIN_NPC_MAYOR,
        ADMIN_HOLOGRAM_LEADERBOARD
    )

    fun hasAny(player: Player, permissions: Iterable<String>): Boolean =
        permissions.any(player::hasPermission)


    /**
     * True if the player can open the admin panel via root admin access or any admin action permission.
     */
    fun canOpenAdminPanel(player: Player): Boolean =
        player.hasPermission(ADMIN_ACCESS) || hasAny(player, ADMIN_ACTION_PERMS)

    /**
     * True if the player has access to staff/admin features.
     *
     * This checks the new permission structure only.
     */
    fun isAdmin(player: Player): Boolean = canOpenAdminPanel(player)
}

