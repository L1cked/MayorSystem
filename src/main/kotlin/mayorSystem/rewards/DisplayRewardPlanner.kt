package mayorSystem.rewards

data class TrackedDisplayReward(
    val uuid: java.util.UUID? = null,
    val lastKnownName: String? = null,
    val rankGroup: String? = null,
    val tagPermission: String? = null,
    val tagId: String? = null,
    val mode: DisplayRewardMode? = null
)

data class DisplayRewardPlan(
    val applyRankGroup: String?,
    val applyTagPermission: String?,
    val applyTagId: String?,
    val removeRankGroups: Set<String>,
    val removeTagPermissions: Set<String>,
    val clearTagIds: Set<String>
)

object DisplayRewardPlanner {
    fun forMayor(
        mode: DisplayRewardMode,
        settings: DisplayRewardSettings,
        tracked: TrackedDisplayReward
    ): DisplayRewardPlan {
        val rankGroup = settings.rank.luckPermsGroup.trim()
            .takeIf { settings.enabled && settings.rank.enabled && mode.includesRank() }
            ?.takeIf { DisplayRewardSettings.GROUP_NAME_REGEX.matches(it) }
        val tagId = settings.tag.deluxeTagId.trim()
            .takeIf { settings.enabled && settings.tag.enabled && mode.includesTag() }
            ?.takeIf { DisplayRewardTagId.isValid(it) }
        val tagPermission = tagId?.let { settings.tag.permissionNode() }

        val removeGroups = linkedSetOf<String>()
        tracked.rankGroup
            ?.takeIf { rankGroup == null || !it.equals(rankGroup, ignoreCase = true) }
            ?.let { removeGroups += it }

        val removeTagPermissions = linkedSetOf<String>()
        tracked.tagPermission
            ?.takeIf { tagPermission == null || !it.equals(tagPermission, ignoreCase = true) }
            ?.let { removeTagPermissions += it }

        val clearTagIds = linkedSetOf<String>()
        tracked.tagId
            ?.takeIf { tagId == null || !it.equals(tagId, ignoreCase = true) }
            ?.let { clearTagIds += it }

        return DisplayRewardPlan(
            applyRankGroup = rankGroup,
            applyTagPermission = tagPermission,
            applyTagId = tagId,
            removeRankGroups = removeGroups,
            removeTagPermissions = removeTagPermissions,
            clearTagIds = clearTagIds
        )
    }

    fun removal(tracked: TrackedDisplayReward, current: TrackedDisplayReward = TrackedDisplayReward()): DisplayRewardPlan {
        val groups = linkedSetOf<String>()
        tracked.rankGroup?.let { groups += it }
        current.rankGroup?.let { groups += it }

        val tagPermissions = linkedSetOf<String>()
        tracked.tagPermission?.let { tagPermissions += it }
        current.tagPermission?.let { tagPermissions += it }

        val tagIds = linkedSetOf<String>()
        tracked.tagId?.let { tagIds += it }
        current.tagId?.let { tagIds += it }

        return DisplayRewardPlan(
            applyRankGroup = null,
            applyTagPermission = null,
            applyTagId = null,
            removeRankGroups = groups,
            removeTagPermissions = tagPermissions,
            clearTagIds = tagIds
        )
    }
}
