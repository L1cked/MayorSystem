package mayorSystem.elections.ui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AdminForceElectFlow {

    enum class Mode {
        ELECT_NOW,
        SET_FORCED
    }

    data class Session(
        val termIndex: Int,
        val target: UUID,
        val targetName: String,
        val mode: Mode,
        val chosenPerks: LinkedHashSet<String> = linkedSetOf()
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun start(
        adminId: UUID,
        termIndex: Int,
        target: UUID,
        targetName: String,
        preselected: Set<String>,
        mode: Mode
    ): Session {
        val s = Session(termIndex, target, targetName, mode, LinkedHashSet(preselected))
        sessions[adminId] = s
        return s
    }

    fun get(adminId: UUID): Session? = sessions[adminId]

    fun clear(adminId: UUID) {
        sessions.remove(adminId)
    }
}

