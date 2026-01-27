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
        // Intentionally no border/filler.

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

        // Bonus terms
        val bonusEnabled = plugin.config.getBoolean("term.bonus_term.enabled", false)
        val everyX = plugin.config.getInt("term.bonus_term.every_x_terms", 4).coerceAtLeast(1)
        val perksPerBonus = plugin.config.getInt(
            "term.bonus_term.perks_per_bonus_term",
            plugin.settings.perksPerTerm
        ).coerceAtLeast(1)

        val bonusItem = icon(
            if (bonusEnabled) Material.NETHER_STAR else Material.GRAY_DYE,
            "<yellow>Bonus Terms</yellow>",
            listOf(
                "<gray>Enabled:</gray> <white>$bonusEnabled</white>",
                "<gray>Every X terms:</gray> <white>$everyX</white>",
                "<gray>Bonus perks:</gray> <white>$perksPerBonus</white>",
                "",
                "<gray>Left click:</gray> <white>Toggle</white>",
                "<gray>Right click:</gray> <white>Configure</white>"
            )
        )
        inv.setItem(31, bonusItem)
        setConfirm(31, bonusItem) { p, click ->
            if (click.isRightClick) {
                plugin.gui.open(p, AdminBonusTermMenu(plugin))
                return@setConfirm
            }
            plugin.adminActions.updateConfig(p, "term.bonus_term.enabled", !bonusEnabled)
            plugin.gui.open(p, AdminSettingsTermMenu(plugin))
        }

        // Election open broadcast
        val bcEnabled = plugin.config.getBoolean("election.broadcast.enabled", true)
        val bcModeRaw = plugin.config.getString("election.broadcast.mode", "TITLE") ?: "TITLE"
        val bcMode = if (bcModeRaw.equals("CHAT", true)) "CHAT" else "TITLE"

        val broadcastItem = icon(
            if (bcEnabled) Material.BELL else Material.GRAY_DYE,
            "<yellow>Election Open Broadcast</yellow>",
            listOf(
                "<gray>Enabled:</gray> <white>$bcEnabled</white>",
                "<gray>Mode:</gray> <white>$bcMode</white>",
                "",
                "<gray>Left click:</gray> <white>Toggle</white>",
                "<gray>Right click:</gray> <white>Switch CHAT/TITLE</white>"
            )
        )
        inv.setItem(33, broadcastItem)
        setConfirm(33, broadcastItem) { p, click ->
            if (click.isRightClick) {
                val next = if (bcMode == "TITLE") "CHAT" else "TITLE"
                plugin.adminActions.updateConfig(p, "election.broadcast.mode", next)
            } else if (click.isLeftClick) {
                plugin.adminActions.updateConfig(p, "election.broadcast.enabled", !bcEnabled)
            }
            plugin.gui.open(p, AdminSettingsTermMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(43, back)
        set(43, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    // ClickType helpers
    private val ClickType.isLeftClick get() = this == ClickType.LEFT || this == ClickType.SHIFT_LEFT
    private val ClickType.isRightClick get() = this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
    private val ClickType.isShiftClick get() = this == ClickType.SHIFT_LEFT || this == ClickType.SHIFT_RIGHT
}
