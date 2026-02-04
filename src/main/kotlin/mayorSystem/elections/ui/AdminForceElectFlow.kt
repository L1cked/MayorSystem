package mayorSystem.elections.ui

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AdminForceElectFlow {

    data class Session(
        val termIndex: Int,
        val target: UUID,
        val targetName: String,
        val chosenPerks: LinkedHashSet<String> = linkedSetOf()
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun start(adminId: UUID, termIndex: Int, target: UUID, targetName: String, preselected: Set<String>): Session {
        val s = Session(termIndex, target, targetName, LinkedHashSet(preselected))
        sessions[adminId] = s
        return s
    }

    fun get(adminId: UUID): Session? = sessions[adminId]

    fun clear(adminId: UUID) {
        sessions.remove(adminId)
    }
}

