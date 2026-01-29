package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import java.time.Duration

class AdminSettingsTermMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>⚙ Settings: Term & Schedule</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings

        // Term length
        val termItem = icon(
            Material.CLOCK,
            "<yellow>Term Length:</yellow> <white>${s.termLength}</white>",
            listOf("<gray>Left/right: ±1 day</gray>", "<gray>Shift: ±7 days</gray>")
        )
        inv.setItem(20, termItem)
        setConfirm(20, termItem) { p, click ->
            val delta = when {
                click.isShiftClick && click.isLeftClick -> Duration.ofDays(7)
                click.isShiftClick && click.isRightClick -> Duration.ofDays(7).negated()
                click.isLeftClick -> Duration.ofDays(1)
                click.isRightClick -> Duration.ofDays(1).negated()
                else -> Duration.ZERO
            }
            val next = (s.termLength + delta).coerceAtLeast(Duration.ofDays(1))
            plugin.adminActions.updateConfig(p, "term.length", next.toString())
            plugin.gui.open(p, AdminSettingsTermMenu(plugin))
        }

        // Vote window
        val voteItem = icon(
            Material.PAPER,
            "<yellow>Vote Window:</yellow> <white>${s.voteWindow}</white>",
            listOf("<gray>Left/right: ±6 hours</gray>", "<gray>Shift: ±1 day</gray>")
        )
        inv.setItem(22, voteItem)
        setConfirm(22, voteItem) { p, click ->
            val delta = when {
                click.isShiftClick && click.isLeftClick -> Duration.ofDays(1)
                click.isShiftClick && click.isRightClick -> Duration.ofDays(1).negated()
                click.isLeftClick -> Duration.ofHours(6)
                click.isRightClick -> Duration.ofHours(6).negated()
                else -> Duration.ZERO
            }
            val next = (s.voteWindow + delta).coerceAtLeast(Duration.ofHours(1))
            plugin.adminActions.updateConfig(p, "term.vote_window", next.toString())
            plugin.gui.open(p, AdminSettingsTermMenu(plugin))
        }

        // First term start
        val startItem = icon(
            Material.COMPASS,
            "<yellow>First Term Start:</yellow>",
            listOf(
                "<white>${s.firstTermStart}</white>",
                "<gray>Left/right: ±1 hour</gray>",
                "<gray>Shift: ±1 day</gray>"
            )
        )
        inv.setItem(24, startItem)
        setConfirm(24, startItem) { p, click ->
            val delta = when {
                click.isShiftClick && click.isLeftClick -> Duration.ofDays(1)
                click.isShiftClick && click.isRightClick -> Duration.ofDays(1).negated()
                click.isLeftClick -> Duration.ofHours(1)
                click.isRightClick -> Duration.ofHours(1).negated()
                else -> Duration.ZERO
            }
            val next = s.firstTermStart.plusSeconds(delta.seconds)
            plugin.adminActions.updateConfig(p, "term.first_term_start", next.toString())
            plugin.gui.open(p, AdminSettingsTermMenu(plugin))
        }

        // Perks per term
        val perksItem = icon(
            Material.DIAMOND,
            "<yellow>Perks per Term:</yellow> <white>${s.perksPerTerm}</white>",
            listOf("<gray>Left/right: ±1</gray>", "<gray>Shift: ±5</gray>")
        )
        inv.setItem(29, perksItem)
        setConfirm(29, perksItem) { p, click ->
            val delta = when {
                click.isShiftClick && click.isLeftClick -> 5
                click.isShiftClick && click.isRightClick -> -5
                click.isLeftClick -> 1
                click.isRightClick -> -1
                else -> 0
            }
            val next = (s.perksPerTerm + delta).coerceAtLeast(0)
            plugin.adminActions.updateConfig(p, "term.perks_per_term", next)
            plugin.gui.open(p, AdminSettingsTermMenu(plugin))
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
