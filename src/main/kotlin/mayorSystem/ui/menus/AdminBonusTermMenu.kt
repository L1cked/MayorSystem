package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory

/**
 * Bonus term configuration UI.
 *
 * Config keys:
 * term.bonus_term.enabled
 * term.bonus_term.every_x_terms
 * term.bonus_term.perks_per_bonus_term
 */
class AdminBonusTermMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#56ab2f:#a8e063>⭐ Bonus Terms</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val enabled = plugin.config.getBoolean("term.bonus_term.enabled", false)
        val everyX = plugin.config.getInt("term.bonus_term.every_x_terms", 4).coerceAtLeast(1)
        val perksPerBonus = plugin.config.getInt("term.bonus_term.perks_per_bonus_term", plugin.settings.perksPerTerm).coerceAtLeast(1)
        val normalPerks = plugin.config.getInt("term.perks_per_term", 2).coerceAtLeast(1)

        inv.setItem(
            4,
            icon(
                Material.NETHER_STAR,
                "<gold>Bonus Term Rules</gold>",
                listOf(
                    "<gray>Enabled:</gray> " + if (enabled) "<green>true</green>" else "<red>false</red>",
                    "<gray>Every X terms:</gray> <white>$everyX</white>",
                    "<gray>Perks on bonus term:</gray> <white>$perksPerBonus</white>",
                    "<gray>Normal perks:</gray> <white>$normalPerks</white>",
                    "",
                    "<dark_gray>Click the buttons below to change values.</dark_gray>"
                )
            )
        )

        // Enabled toggle
        val toggleItem = icon(
            if (enabled) Material.LIME_DYE else Material.GRAY_DYE,
            "<yellow>Toggle Bonus Terms</yellow>",
            listOf("<gray>Click to toggle.</gray>")
        )
        inv.setItem(20, toggleItem)
        setConfirm(20, toggleItem) { p, _ ->
            plugin.adminActions.updateSettingsConfig(p, "term.bonus_term.enabled", !enabled)
            plugin.gui.open(p, AdminBonusTermMenu(plugin))
        }

        // every_x_terms adjust
        val everyItem = icon(
            Material.CLOCK,
            "<aqua>Every X Terms: <white>$everyX</white></aqua>",
            listOf(
                "<gray>Left click:</gray> <white>+1</white>",
                "<gray>Right click:</gray> <white>-1</white>",
                "<gray>Shift left:</gray> <white>+5</white>",
                "<gray>Shift right:</gray> <white>-5</white>"
            )
        )
        inv.setItem(22, everyItem)
        setConfirm(22, everyItem) { p, click ->
            val delta = when (click) {
                ClickType.SHIFT_LEFT -> 5
                ClickType.SHIFT_RIGHT -> -5
                ClickType.RIGHT -> -1
                else -> 1
            }
            val next = (everyX + delta).coerceAtLeast(1)
            plugin.adminActions.updateSettingsConfig(p, "term.bonus_term.every_x_terms", next)
            plugin.gui.open(p, AdminBonusTermMenu(plugin))
        }

        // perks_per_bonus_term adjust
        val perksItem = icon(
            Material.DIAMOND,
            "<light_purple>Bonus Perks: <white>$perksPerBonus</white></light_purple>",
            listOf(
                "<gray>Left click:</gray> <white>+1</white>",
                "<gray>Right click:</gray> <white>-1</white>",
                "<gray>Shift left:</gray> <white>+5</white>",
                "<gray>Shift right:</gray> <white>-5</white>",
                "",
                "<dark_gray>Normal perks per term:</dark_gray> <white>$normalPerks</white>"
            )
        )
        inv.setItem(24, perksItem)
        setConfirm(24, perksItem) { p, click ->
            val delta = when (click) {
                ClickType.SHIFT_LEFT -> 5
                ClickType.SHIFT_RIGHT -> -5
                ClickType.RIGHT -> -1
                else -> 1
            }
            val next = (perksPerBonus + delta).coerceAtLeast(1)
            plugin.adminActions.updateSettingsConfig(p, "term.bonus_term.perks_per_bonus_term", next)
            plugin.gui.open(p, AdminBonusTermMenu(plugin))
        }

        // Back
        inv.setItem(45, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(45, inv.getItem(45)!!) { p, _ -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }
}
