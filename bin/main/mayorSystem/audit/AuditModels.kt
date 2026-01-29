package mayorSystem.audit

import java.time.OffsetDateTime

/**
 * A small immutable event representing a state mutation or important admin action.
 *
 * Stored as JSON Lines (one JSON object per line) for streaming + size efficiency.
 */
data class AuditEvent(
    val timestamp: OffsetDateTime,
    val actorUuid: String?,
    val actorName: String,
    val action: String,
    val term: Int?,
    val target: String?,
    val details: Map<String, String> = emptyMap()
)
