package mayorSystem.papi

import mayorSystem.MayorPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import mayorSystem.data.CandidateEntry
import org.bukkit.entity.Player

class MayorPlaceholderExpansion(private val plugin: MayorPlugin) : PlaceholderExpansion() {

    private data class LeaderboardSnapshot(
        val term: Int,
        val entries: List<Pair<CandidateEntry, Int>>,
        val createdAt: Long
    )

    private val cacheTtlMs = 2000L
    @Volatile
    private var snapshot: LeaderboardSnapshot? = null
    private val lock = Any()

    override fun getIdentifier(): String = "mayorsystem"

    override fun getAuthor(): String = "MayorSystem"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (!plugin.isReady()) return ""

        val (_, electionTerm) = plugin.termService.computeNow()
        val term = electionTerm
        if (params.equals("leaderboard_term", ignoreCase = true)) {
            return if (term >= 0) (term + 1).toString() else ""
        }

        val parts = params.split('_')
        if (parts.size == 3 && parts[0].equals("leaderboard", ignoreCase = true)) {
            val pos = parts[1].toIntOrNull() ?: return ""
            if (pos <= 0) return ""
            val field = parts[2].lowercase()
            val entries = leaderboard(term, pos)
            val entry = entries.getOrNull(pos - 1) ?: return ""
            return when (field) {
                "name" -> entry.first.lastKnownName
                "votes" -> entry.second.toString()
                "uuid" -> entry.first.uuid.toString()
                else -> ""
            }
        }

        return ""
    }

    private fun leaderboard(term: Int, limit: Int): List<Pair<CandidateEntry, Int>> {
        if (term < 0) return emptyList()
        val now = System.currentTimeMillis()
        val cached = snapshot
        if (cached != null && cached.term == term && now - cached.createdAt < cacheTtlMs && cached.entries.size >= limit) {
            return cached.entries
        }
        synchronized(lock) {
            val current = snapshot
            if (current != null && current.term == term && now - current.createdAt < cacheTtlMs && current.entries.size >= limit) {
                return current.entries
            }
            val entries = plugin.store.topCandidates(term, limit, includeRemoved = false)
            snapshot = LeaderboardSnapshot(term = term, entries = entries, createdAt = now)
            return entries
        }
    }
}
