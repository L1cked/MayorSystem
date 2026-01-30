package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory

class AdminSettingsApplyMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>⚙ Settings: Apply Requirements</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings

        // Apply playtime minutes
        val playtimeItem = icon(
            Material.CLOCK,
            "<yellow>Min Playtime (minutes):</yellow> <white>${s.applyPlaytimeMinutes}</white>",
            listOf("<gray>Left/right: ±60</gray>", "<gray>Shift: ±300</gray>")
        )
        inv.setItem(22, playtimeItem)
        setConfirm(22, playtimeItem) { p, click ->
            val delta = when {
                click.isShiftClick && click.isLeftClick -> 300
                click.isShiftClick && click.isRightClick -> -300
                click.isLeftClick -> 60
                click.isRightClick -> -60
                else -> 0
            }
            val next = (s.applyPlaytimeMinutes + delta).coerceAtLeast(0)
            plugin.adminActions.updateSettingsConfig(p, "apply.playtime_minutes", next)
            plugin.gui.open(p, AdminSettingsApplyMenu(plugin))
        }

        // Apply cost
        val costItem = icon(
            Material.EMERALD,
            "<yellow>Apply Cost:</yellow> <white>${s.applyCost}</white>",
            listOf("<gray>Left/right: ±100</gray>", "<gray>Shift: ±1000</gray>")
        )
        inv.setItem(31, costItem)
        setConfirm(31, costItem) { p, click ->
            val delta = when {
                click.isShiftClick && click.isLeftClick -> 1000.0
                click.isShiftClick && click.isRightClick -> -1000.0
                click.isLeftClick -> 100.0
                click.isRightClick -> -100.0
                else -> 0.0
            }
            val next = (s.applyCost + delta).coerceAtLeast(0.0)
            plugin.adminActions.updateSettingsConfig(p, "apply.cost", next)
            plugin.gui.open(p, AdminSettingsApplyMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    // ClickType helpers
    private val ClickType.isLeftClick get() = this == ClickType.LEFT || this == ClickType.SHIFT_LEFT
    private val ClickType.isRightClick get() = this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
    private val ClickType.isShiftClick get() = this == ClickType.SHIFT_LEFT || this == ClickType.SHIFT_RIGHT
}
