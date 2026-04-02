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
    const val ADMIN_PANEL_OPEN = "mayor.admin.panel.open"

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

    const val ADMIN_MAINTENANCE_RELOAD = "mayor.admin.maintenance.reload"
    const val ADMIN_MAINTENANCE_DEBUG = "mayor.admin.maintenance.debug"

    const val ADMIN_AUDIT_VIEW = "mayor.admin.audit.view"
    const val ADMIN_HEALTH_VIEW = "mayor.admin.health.view"

    const val ADMIN_NPC_MAYOR = "mayor.admin.npc.mayor"
    const val ADMIN_HOLOGRAM_LEADERBOARD = "mayor.admin.hologram.leaderboard"


    /**
     * True if the player can open the admin panel (explicit node or any admin access).
     */
    fun canOpenAdminPanel(player: Player): Boolean =
        player.hasPermission(ADMIN_PANEL_OPEN) || isAdmin(player)

    /**
     * True if the player has access to staff/admin features.
     *
     * This checks the new permission structure only.
     */
    fun isAdmin(player: Player): Boolean {
        if (player.hasPermission(ADMIN_ACCESS)) return true

        return player.hasPermission(ADMIN_PANEL_OPEN)
                || player.hasPermission(ADMIN_SYSTEM_TOGGLE)
                || player.hasPermission(ADMIN_CANDIDATES_REMOVE)
                || player.hasPermission(ADMIN_CANDIDATES_RESTORE)
                || player.hasPermission(ADMIN_CANDIDATES_PROCESS)
                || player.hasPermission(ADMIN_CANDIDATES_APPLYBAN)
                || player.hasPermission(ADMIN_PERKS_REFRESH)
                || player.hasPermission(ADMIN_PERKS_REQUESTS)
                || player.hasPermission(ADMIN_PERKS_CATALOG)
                || player.hasPermission(ADMIN_GOVERNANCE_EDIT)
                || player.hasPermission(ADMIN_MESSAGING_EDIT)
                || player.hasPermission(ADMIN_ELECTION_START)
                || player.hasPermission(ADMIN_ELECTION_END)
                || player.hasPermission(ADMIN_ELECTION_CLEAR)
                || player.hasPermission(ADMIN_ELECTION_ELECT)
                || player.hasPermission(ADMIN_ELECTION_FAKE_VOTES)
                || player.hasPermission(ADMIN_SETTINGS_EDIT)
                || player.hasPermission(ADMIN_SETTINGS_RELOAD)
                || player.hasPermission(ADMIN_MAINTENANCE_RELOAD)
                || player.hasPermission(ADMIN_MAINTENANCE_DEBUG)
                || player.hasPermission(ADMIN_AUDIT_VIEW)
                || player.hasPermission(ADMIN_HEALTH_VIEW)
                || player.hasPermission(ADMIN_NPC_MAYOR)
                || player.hasPermission(ADMIN_HOLOGRAM_LEADERBOARD)
    }
}

