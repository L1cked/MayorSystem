package mayorSystem.config

/**
 * How to resolve an election tie (same highest vote count).
 *
 * Some policies need extra context (incumbent, appliedAt). If the required
 * context is missing, the system falls back to [ALPHABETICAL].
 */
enum class TiePolicy {
    /** Deterministic “random”: uses a stable seed so results don't change across restarts. */
    SEEDED_RANDOM,

    /** Picks the incumbent mayor if they're part of the tied top candidates; otherwise falls back. */
    INCUMBENT,

    /** Picks the candidate who applied earliest for this election term; otherwise falls back. */
    EARLIEST_APPLICATION,

    /** Picks the lexicographically earliest candidate name (A→Z). */
    ALPHABETICAL;

    fun next(): TiePolicy {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    fun prev(): TiePolicy {
        val all = entries
        return all[(ordinal - 1 + all.size) % all.size]
    }
}
