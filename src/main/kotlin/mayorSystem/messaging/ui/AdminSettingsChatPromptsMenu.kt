package mayorSystem.messaging.ui

import mayorSystem.MayorPlugin
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory

/**
 * Admin settings for chat prompt max lengths.
 *
 * This controls:
 * - Candidate bio prompt length
 * - Custom perk request title length
 * - Custom perk request description length
 */
class AdminSettingsChatPromptsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Settings: Chat Prompts</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings

        val help = listOf(
            "<gray>Left/right:</gray> <white>+/-5</white>",
            "<gray>Shift:</gray> <white>+/-25</white>",
            "<dark_gray>Min 1, max 500.</dark_gray>"
        )

        val bio = icon(
            Material.NAME_TAG,
            "<yellow>Bio max length:</yellow> <white>${s.chatPromptMaxBioChars}</white>",
            help + listOf("", "<gray>Used for candidate bio editing.</gray>")
        )
        inv.setItem(11, bio)
        setConfirm(11, bio) { p, click ->
            val next = nextInt(s.chatPromptMaxBioChars, click)
            plugin.adminActions.updateSettingsConfig(p, "ux.chat_prompts.max_length.bio", next)
            plugin.gui.open(p, AdminSettingsChatPromptsMenu(plugin))
        }

        val title = icon(
            Material.OAK_SIGN,
            "<yellow>Request title max length:</yellow> <white>${s.chatPromptMaxTitleChars}</white>",
            help + listOf("", "<gray>Used for custom perk request titles.</gray>")
        )
        inv.setItem(13, title)
        setConfirm(13, title) { p, click ->
            val next = nextInt(s.chatPromptMaxTitleChars, click)
            plugin.adminActions.updateSettingsConfig(p, "ux.chat_prompts.max_length.title", next)
            plugin.gui.open(p, AdminSettingsChatPromptsMenu(plugin))
        }

        val desc = icon(
            Material.BOOK,
            "<yellow>Request description max length:</yellow> <white>${s.chatPromptMaxDescChars}</white>",
            help + listOf("", "<gray>Used for custom perk request descriptions.</gray>")
        )
        inv.setItem(15, desc)
        setConfirm(15, desc) { p, click ->
            val next = nextInt(s.chatPromptMaxDescChars, click)
            plugin.adminActions.updateSettingsConfig(p, "ux.chat_prompts.max_length.description", next)
            plugin.gui.open(p, AdminSettingsChatPromptsMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private fun nextInt(current: Int, click: ClickType): Int {
        val delta = when {
            click.isShiftClick && click.isLeftClick -> 25
            click.isShiftClick && click.isRightClick -> -25
            click.isLeftClick -> 5
            click.isRightClick -> -5
            else -> 0
        }
        return (current + delta).coerceIn(1, 500)
    }

    // ClickType helpers
    private val ClickType.isLeftClick get() = this == ClickType.LEFT || this == ClickType.SHIFT_LEFT
    private val ClickType.isRightClick get() = this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
    private val ClickType.isShiftClick get() = this == ClickType.SHIFT_LEFT || this == ClickType.SHIFT_RIGHT
}

