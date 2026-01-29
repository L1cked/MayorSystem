package mayorSystem.data

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Candidate lifecycle states.
 *
 * ACTIVE  -> normal candidate.
 * PROCESS -> temporarily in review (ex: staff is investigating). Not electable.
 * REMOVED -> removed from the election. Votes for them are refunded.
 */
enum class CandidateStatus { ACTIVE, PROCESS, REMOVED }

enum class RequestStatus { PENDING, APPROVED, DENIED }

data class CandidateEntry(
    val uuid: UUID,
    val lastKnownName: String,
    val status: CandidateStatus
)

data class CustomPerkRequest(
    val id: Int,
    val candidate: UUID,
    val title: String,
    val description: String,
    val status: RequestStatus,
    val createdAt: OffsetDateTime,
    val onStart: List<String> = emptyList(),
    val onEnd: List<String> = emptyList()
)

/**
 * Global ban from applying for elections.
 *
 * - permanent=true -> until is ignored
 * - permanent=false -> until must be non-null
 */
data class ApplyBan(
    val uuid: UUID,
    val lastKnownName: String,
    val permanent: Boolean,
    val until: OffsetDateTime?,
    val createdAt: OffsetDateTime
)
