package mayorSystem.showcase

import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import java.time.Instant

enum class ShowcaseMode {
    SWITCHING,
    INDIVIDUAL;

    companion object {
        fun parse(raw: String?): ShowcaseMode {
            val norm = raw?.trim()?.uppercase()
            return when (norm) {
                "INDIVIDUAL" -> INDIVIDUAL
                else -> SWITCHING
            }
        }
    }
}

enum class ShowcaseTarget {
    NPC,
    HOLOGRAM
}

class ShowcaseService(private val plugin: MayorPlugin) {

    fun mode(): ShowcaseMode =
        ShowcaseMode.parse(plugin.config.getString("showcase.mode"))

    fun electionOpenNow(): Boolean = isElectionOpenNow()

    fun desiredTarget(electionOpen: Boolean = isElectionOpenNow()): ShowcaseTarget =
        switchingTarget(electionOpen)

    fun activeTarget(): ShowcaseTarget? {
        if (mode() != ShowcaseMode.SWITCHING) return null
        val electionOpen = isElectionOpenNow()
        val desired = switchingTarget(electionOpen)
        val npcOk = npcEnabled()
        val holoOk = hologramEnabled()

        return when {
            desired == ShowcaseTarget.NPC && npcOk -> ShowcaseTarget.NPC
            desired == ShowcaseTarget.HOLOGRAM && holoOk -> ShowcaseTarget.HOLOGRAM
            desired == ShowcaseTarget.NPC && holoOk -> ShowcaseTarget.HOLOGRAM
            desired == ShowcaseTarget.HOLOGRAM && npcOk -> ShowcaseTarget.NPC
            else -> null
        }
    }

    fun sync() {
        val mode = mode()
        val showNpc: Boolean
        val showHologram: Boolean

        if (mode == ShowcaseMode.INDIVIDUAL) {
            showNpc = npcEnabled()
            showHologram = hologramEnabled()
        } else {
            val electionOpen = isElectionOpenNow()
            val desired = switchingTarget(electionOpen)
            val npcOk = npcEnabled()
            val holoOk = hologramEnabled()

            var npc = desired == ShowcaseTarget.NPC && npcOk
            var holo = desired == ShowcaseTarget.HOLOGRAM && holoOk

            // Fallback if the desired target isn't available.
            if (!npc && desired == ShowcaseTarget.NPC && holoOk) holo = true
            if (!holo && desired == ShowcaseTarget.HOLOGRAM && npcOk) npc = true

            showNpc = npc
            showHologram = holo
        }

        plugin.mayorNpc.setActive(showNpc)
        if (showNpc) {
            if (plugin.hasTermService()) {
                val term = plugin.termService.computeNow().first
                plugin.mayorNpc.forceUpdateMayorForTerm(term)
            } else {
                plugin.mayorNpc.forceUpdateMayor()
            }
        }

        plugin.leaderboardHologram.setActive(showHologram)
        if (showHologram) {
            plugin.leaderboardHologram.refreshIfActive()
        }
    }

    private fun switchingTarget(electionOpen: Boolean): ShowcaseTarget {
        if (electionOpen) return ShowcaseTarget.HOLOGRAM
        return if (hasElectedMayor()) ShowcaseTarget.NPC else ShowcaseTarget.HOLOGRAM
    }

    private fun hasElectedMayor(): Boolean {
        if (!plugin.hasTermService()) return false
        val now = Instant.now()
        val (currentTerm, _) = plugin.termService.computeCached(now)
        if (currentTerm < 0) return false
        if (plugin.config.getBoolean("admin.mayor_vacant.$currentTerm", false)) return false
        return plugin.store.winner(currentTerm) != null
    }

    private fun npcEnabled(): Boolean {
        if (!plugin.hasMayorNpc()) return false
        if (!plugin.config.getBoolean("npc.mayor.enabled", false)) return false
        if (plugin.settings.isBlocked(SystemGateOption.MAYOR_NPC)) return false
        return true
    }

    private fun hologramEnabled(): Boolean {
        if (!plugin.hasLeaderboardHologram()) return false
        if (!plugin.settings.enabled) return false
        if (!plugin.config.getBoolean("hologram.leaderboard.enabled", false)) return false
        if (!plugin.leaderboardHologram.isAvailable()) return false
        return true
    }

    private fun isElectionOpenNow(): Boolean {
        if (!plugin.hasTermService()) return false
        val now = Instant.now()
        val (_, electionTerm) = plugin.termService.computeCached(now)
        val term = if (electionTerm < 0) 0 else electionTerm
        return plugin.termService.isElectionOpen(now, term)
    }
}
