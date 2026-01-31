package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory

class AdminSettingsTermExtrasMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>? Settings: Term Extras</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val bonus = icon(
            Material.NETHER_STAR,
            "<yellow>Bonus Terms</yellow>",
            listOf(
                "<gray>Configure bonus term cadence</gray>",
                "<gray>and extra perks.</gray>"
            )
        )
        inv.setItem(11, bonus)
        set(11, bonus) { p -> plugin.gui.open(p, AdminBonusTermMenu(plugin)) }

        val bcEnabled = plugin.config.getBoolean("election.broadcast.enabled", true)
        val bcModeRaw = plugin.config.getString("election.broadcast.mode", "TITLE") ?: "TITLE"
        val bcMode = when {
            bcModeRaw.equals("CHAT", true) -> "CHAT"
            bcModeRaw.equals("BOTH", true) -> "BOTH"
            else -> "TITLE"
        }

        val broadcastItem = icon(
            if (bcEnabled) Material.BELL else Material.GRAY_DYE,
            "<yellow>Election Broadcasts</yellow>",
            listOf(
                "<gray>Broadcasts:</gray> <white>Election Open + New Mayor</white>",
                "<gray>Enabled:</gray> <white>$bcEnabled</white>",
                "<gray>Mode:</gray> <white>$bcMode</white>",
                "",
                "<gray>Left click:</gray> <white>Toggle</white>",
                "<gray>Right click:</gray> <white>Switch CHAT/TITLE/BOTH</white>"
            )
        )
        inv.setItem(15, broadcastItem)
        setConfirm(15, broadcastItem) { p, click ->
            if (click.isRightClick) {
                val next = when (bcMode) {
                    "TITLE" -> "CHAT"
                    "CHAT" -> "BOTH"
                    else -> "TITLE"
                }
                plugin.adminActions.updateSettingsConfig(p, "election.broadcast.mode", next)
            } else if (click.isLeftClick) {
                plugin.adminActions.updateSettingsConfig(p, "election.broadcast.enabled", !bcEnabled)
            }
            plugin.gui.open(p, AdminSettingsTermExtrasMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray>? Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private val ClickType.isLeftClick get() = this == ClickType.LEFT || this == ClickType.SHIFT_LEFT
    private val ClickType.isRightClick get() = this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
}

