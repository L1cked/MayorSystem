package mayorSystem.config

/**
 * Who is allowed to create custom perk requests.
 *
 * - NONE: anyone with access to the Candidate menu (i.e., has applied) can request.
 * - ELECTED_ONCE: player must have been mayor at least once in the past.
 * - APPLY_REQUIREMENTS: player must meet the apply requirements (currently: playtime threshold).
 */
enum class CustomRequestCondition {
    NONE,
    ELECTED_ONCE,
    APPLY_REQUIREMENTS;

    fun next(): CustomRequestCondition = when (this) {
        NONE -> ELECTED_ONCE
        ELECTED_ONCE -> APPLY_REQUIREMENTS
        APPLY_REQUIREMENTS -> NONE
    }

    fun prev(): CustomRequestCondition = when (this) {
        NONE -> APPLY_REQUIREMENTS
        ELECTED_ONCE -> NONE
        APPLY_REQUIREMENTS -> ELECTED_ONCE
    }
}

