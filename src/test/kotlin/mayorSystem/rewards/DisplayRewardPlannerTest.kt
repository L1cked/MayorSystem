package mayorSystem.rewards

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayRewardPlannerTest {

    @Test
    fun `rank mode applies only rank reward`() {
        val settings = settings()
        val plan = DisplayRewardPlanner.forMayor(DisplayRewardMode.RANK, settings, TrackedDisplayReward())

        assertEquals("mayor_current", plan.applyRankGroup)
        assertNull(plan.applyTagId)
        assertNull(plan.applyTagPermission)
    }

    @Test
    fun `tag mode applies only tag reward`() {
        val settings = settings(tagEnabled = true)
        val plan = DisplayRewardPlanner.forMayor(DisplayRewardMode.TAG, settings, TrackedDisplayReward())

        assertNull(plan.applyRankGroup)
        assertEquals("mayor_current", plan.applyTagId)
        assertEquals("DeluxeTags.Tag.mayor_current", plan.applyTagPermission)
    }

    @Test
    fun `both mode applies rank and tag rewards`() {
        val settings = settings(tagEnabled = true)
        val plan = DisplayRewardPlanner.forMayor(DisplayRewardMode.BOTH, settings, TrackedDisplayReward())

        assertEquals("mayor_current", plan.applyRankGroup)
        assertEquals("mayor_current", plan.applyTagId)
        assertEquals("DeluxeTags.Tag.mayor_current", plan.applyTagPermission)
    }

    @Test
    fun `stale tracked reward is removed when mode changes`() {
        val settings = settings(tagEnabled = true)
        val tracked = TrackedDisplayReward(
            rankGroup = "old_mayor",
            tagPermission = "deluxetags.tag.old",
            tagId = "old"
        )
        val plan = DisplayRewardPlanner.forMayor(DisplayRewardMode.TAG, settings, tracked)

        assertEquals(setOf("old_mayor"), plan.removeRankGroups)
        assertEquals(setOf("deluxetags.tag.old"), plan.removeTagPermissions)
        assertEquals(setOf("old"), plan.clearTagIds)
        assertEquals("mayor_current", plan.applyTagId)
    }

    @Test
    fun `changing tag display does not change tag id or permission`() {
        val cfg = YamlConfiguration().apply {
            set("display_reward.enabled", true)
            set("display_reward.tag.enabled", true)
            set("display_reward.tag.deluxe_tag_id", "mayor_current")
            set("display_reward.tag.display", "&b[Different]")
        }
        val settings = DisplayRewardSettings.from(cfg, usernameGroupEnabled = true, usernameGroup = "mayor_current")
        val plan = DisplayRewardPlanner.forMayor(DisplayRewardMode.TAG, settings, TrackedDisplayReward())

        assertEquals("mayor_current", plan.applyTagId)
        assertEquals("DeluxeTags.Tag.mayor_current", plan.applyTagPermission)
    }

    @Test
    fun `old rank only behavior still plans rank reward`() {
        val cfg = YamlConfiguration()
        val settings = DisplayRewardSettings.from(cfg, usernameGroupEnabled = true, usernameGroup = "legacy_mayor")
        val plan = DisplayRewardPlanner.forMayor(settings.defaultMode, settings, TrackedDisplayReward())

        assertEquals("legacy_mayor", plan.applyRankGroup)
        assertNull(plan.applyTagId)
    }

    private fun settings(tagEnabled: Boolean = false): DisplayRewardSettings {
        val cfg = YamlConfiguration().apply {
            set("display_reward.enabled", true)
            set("display_reward.default_mode", "RANK")
            set("display_reward.rank.enabled", true)
            set("display_reward.rank.luckperms_group", "mayor_current")
            set("display_reward.tag.enabled", tagEnabled)
            set("display_reward.tag.deluxe_tag_id", "mayor_current")
        }
        return DisplayRewardSettings.from(cfg, usernameGroupEnabled = true, usernameGroup = "mayor_current")
    }
}
