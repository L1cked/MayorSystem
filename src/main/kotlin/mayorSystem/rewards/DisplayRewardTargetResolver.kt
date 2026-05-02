package mayorSystem.rewards

import java.util.UUID

data class DisplayRewardSubject(
    val uuid: UUID,
    val name: String?,
    val tracks: Set<String>,
    val groups: Set<String>
)

data class InvalidDisplayRewardTarget(
    val path: String,
    val key: String,
    val value: String,
    val reason: String
)

data class DisplayRewardTargets(
    val tracks: Map<String, DisplayRewardMode> = emptyMap(),
    val groups: Map<String, DisplayRewardMode> = emptyMap(),
    val users: Map<String, DisplayRewardMode> = emptyMap(),
    val invalid: List<InvalidDisplayRewardTarget> = emptyList(),
    val legacyRanks: Map<String, DisplayRewardMode> = emptyMap()
) {
    fun resolve(subject: DisplayRewardSubject, defaultMode: DisplayRewardMode): DisplayRewardMode {
        users[subject.uuid.toString().lowercase()]?.let { return it }

        subject.groups
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
            .firstNotNullOfOrNull { groups[it] }
            ?.let { return it }

        subject.tracks
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
            .firstNotNullOfOrNull { tracks[it] }
            ?.let { return it }

        return defaultMode
    }
}
