package mayorSystem.rewards

import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayRewardSettingsTest {

    @Test
    fun `missing display reward uses old rank behavior`() {
        val enabled = settingsFromLegacy(enabled = true, group = "mayor_rank")
        val disabled = settingsFromLegacy(enabled = false, group = "old_mayor")

        assertFalse(enabled.configured)
        assertTrue(enabled.enabled)
        assertEquals(DisplayRewardMode.RANK, enabled.defaultMode)
        assertTrue(enabled.rank.enabled)
        assertEquals("mayor_rank", enabled.rank.luckPermsGroup)
        assertFalse(enabled.tag.enabled)

        assertFalse(disabled.enabled)
        assertFalse(disabled.rank.enabled)
        assertEquals("old_mayor", disabled.rank.luckPermsGroup)
    }

    @Test
    fun `configured defaults use stable tag id description and icon`() {
        val cfg = YamlConfiguration().apply {
            set("display_reward.enabled", true)
        }
        val settings = DisplayRewardSettings.from(cfg, usernameGroupEnabled = true, usernameGroup = "mayor_current")

        assertEquals("mayor_current", settings.tag.deluxeTagId)
        assertEquals("&6[%title_name%]", settings.tag.display)
        assertEquals("Awarded to the only %title_name%", settings.tag.description)
        assertEquals("DeluxeTags.Tag.mayor_current", settings.tag.permissionNode())
        assertTrue(settings.tag.renderBeforeLuckPerms)
        assertEquals("GOLDEN_HELMET", settings.tag.icon.materialOrDefault().name)
        assertNull(settings.tag.icon.customModelData)
        assertFalse(settings.tag.icon.glint)
    }

    @Test
    fun `tag before rank setting can be disabled`() {
        val cfg = YamlConfiguration().apply {
            set("display_reward.enabled", true)
            set("display_reward.tag.render_before_luckperms", false)
        }
        val settings = DisplayRewardSettings.from(cfg, usernameGroupEnabled = true, usernameGroup = "mayor_current")

        assertFalse(settings.tag.renderBeforeLuckPerms)
    }

    @Test
    fun `previous default tag description and permission normalize to current defaults`() {
        val cfg = YamlConfiguration().apply {
            set("display_reward.enabled", true)
            set("display_reward.tag.description", "Only for the worthy.")
            set("display_reward.tag.permission", "deluxetags.tag.mayor_current")
        }
        val settings = DisplayRewardSettings.from(cfg, usernameGroupEnabled = true, usernameGroup = "mayor_current")

        assertEquals("Awarded to the only %title_name%", settings.tag.description)
        assertEquals("DeluxeTags.Tag.mayor_current", settings.tag.permissionNode())
    }

    @Test
    fun `reward mode parsing is case insensitive and cycles in menu order`() {
        assertEquals(DisplayRewardMode.RANK, DisplayRewardMode.parse("rank"))
        assertEquals(DisplayRewardMode.TAG, DisplayRewardMode.parse("Tag"))
        assertEquals(DisplayRewardMode.BOTH, DisplayRewardMode.parse("both"))
        assertEquals(DisplayRewardMode.TAG, DisplayRewardMode.RANK.next())
        assertEquals(DisplayRewardMode.BOTH, DisplayRewardMode.TAG.next())
        assertEquals(DisplayRewardMode.RANK, DisplayRewardMode.BOTH.next())
    }

    @Test
    fun `invalid tag ids are rejected`() {
        assertTrue(DisplayRewardTagId.isValid("worthy"))
        assertTrue(DisplayRewardTagId.isValid("Worthy_2026"))
        assertFalse(DisplayRewardTagId.isValid(""))
        assertFalse(DisplayRewardTagId.isValid("worth.y"))
        assertFalse(DisplayRewardTagId.isValid("worthy tag"))
        assertFalse(DisplayRewardTagId.isValid("../worthy"))
    }

    @Test
    fun `target precedence is user then group then track then default`() {
        val user = UUID.randomUUID()
        val targets = DisplayRewardTargets(
            tracks = mapOf("staff" to DisplayRewardMode.BOTH),
            groups = mapOf("vip" to DisplayRewardMode.TAG),
            users = mapOf(user.toString().lowercase() to DisplayRewardMode.RANK)
        )
        val userSubject = DisplayRewardSubject(user, "PlayerOne", setOf("staff"), setOf("vip"))
        val groupSubject = DisplayRewardSubject(UUID.randomUUID(), "PlayerThree", setOf("staff"), setOf("vip"))
        val trackSubject = DisplayRewardSubject(UUID.randomUUID(), "PlayerTwo", setOf("staff"), setOf("default"))
        val defaultSubject = DisplayRewardSubject(UUID.randomUUID(), "PlayerFour", setOf("member"), setOf("default"))

        assertEquals(DisplayRewardMode.RANK, targets.resolve(userSubject, DisplayRewardMode.BOTH))
        assertEquals(DisplayRewardMode.TAG, targets.resolve(groupSubject, DisplayRewardMode.RANK))
        assertEquals(DisplayRewardMode.BOTH, targets.resolve(trackSubject, DisplayRewardMode.RANK))
        assertEquals(DisplayRewardMode.RANK, targets.resolve(defaultSubject, DisplayRewardMode.RANK))
    }

    @Test
    fun `legacy rank target path is read as track overrides`() {
        val cfg = YamlConfiguration().apply {
            set("display_reward.enabled", true)
            set("display_reward.targets.ranks.staff", "TAG")
        }
        val settings = DisplayRewardSettings.from(cfg, usernameGroupEnabled = true, usernameGroup = "mayor_current")

        assertEquals(DisplayRewardMode.TAG, settings.targets.tracks["staff"])
        assertEquals(DisplayRewardMode.TAG, settings.targets.legacyRanks["staff"])
    }

    @Test
    fun `invalid target overrides are reported`() {
        val cfg = YamlConfiguration().apply {
            set("display_reward.enabled", true)
            set("display_reward.targets.groups.bad target", "TAG")
            set("display_reward.targets.ranks.member", "NOPE")
            set("display_reward.targets.users.NotAUuid", "RANK")
        }
        val settings = DisplayRewardSettings.from(cfg, usernameGroupEnabled = true, usernameGroup = "mayor_current")

        assertEquals(3, settings.targets.invalid.size)
    }

    @Test
    fun `invalid icon materials are rejected and reset material is valid`() {
        assertNull(TagIconSettings.normalizeMaterial("AIR"))
        assertNull(TagIconSettings.normalizeMaterial("not_a_material"))
        assertEquals("GOLDEN_HELMET", TagIconSettings.normalizeMaterial(TagIconSettings.DEFAULT_MATERIAL))
    }

    @Test
    fun `reward mode lore marks selected with green arrow`() {
        val lines = DisplayRewardModeLore.lines(DisplayRewardMode.TAG)

        assertTrue(lines.any { it == "<gray>></gray> <white>RANK</white>" })
        assertTrue(lines.any { it == "<green>></green> <white>TAG</white>" })
        assertTrue(lines.any { it == "<gray>></gray> <white>BOTH</white>" })
    }

    @Test
    fun `legacy color display renders for admin previews`() {
        val rendered = DisplayRewardText.previewMini("&6[Mayor]")

        assertFalse(rendered.contains("&6"))
        assertTrue(DisplayRewardText.plain("&6[Mayor]").contains("Mayor"))
    }

    private fun settingsFromLegacy(enabled: Boolean, group: String): DisplayRewardSettings {
        val cfg = YamlConfiguration().apply {
            set("title.username_group_enabled", enabled)
            set("title.username_group", group)
        }
        return DisplayRewardSettings.from(cfg, usernameGroupEnabled = enabled, usernameGroup = group)
    }
}
