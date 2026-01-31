package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.config.MayorStepdownPolicy
import mayorSystem.config.TiePolicy
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory

class AdminElectionSettingsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>⚙ Election Settings</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings

        // Allow vote changes
        val voteChangeItem = icon(
            if (s.allowVoteChange) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>Allow vote changes:</yellow> <white>${s.allowVoteChange}</white>",
            listOf(
                "<gray>If enabled, players can change their vote</gray>",
                "<gray>any time until the election closes.</gray>",
                "",
                "<dark_gray>Click to toggle.</dark_gray>"
            )
        )
        inv.setItem(10, voteChangeItem)
        setConfirm(10, voteChangeItem) { p, _ ->
            plugin.adminActions.updateSettingsConfig("election.allow_vote_change", !s.allowVoteChange)
            plugin.gui.open(p, AdminElectionSettingsMenu(plugin))
        }

        val reapplyItem = icon(
            if (s.stepdownAllowReapply) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>Re-apply after step down:</yellow> <white>${s.stepdownAllowReapply}</white>",
            listOf(
                "<gray>If enabled, players who step down</gray>",
                "<gray>can re-apply in the same term.</gray>",
                "",
                "<dark_gray>Click to toggle.</dark_gray>"
            )
        )
        inv.setItem(14, reapplyItem)
        setConfirm(14, reapplyItem) { p, _ ->
            plugin.adminActions.updateSettingsConfig("election.stepdown.allow_reapply", !s.stepdownAllowReapply)
            plugin.gui.open(p, AdminElectionSettingsMenu(plugin))
        }

        val mayorStepdownLabel = when (s.mayorStepdownPolicy) {
            MayorStepdownPolicy.OFF -> "Off"
            MayorStepdownPolicy.NO_MAYOR -> "No Mayor"
            MayorStepdownPolicy.KEEP_MAYOR -> "Keep Mayor"
        }
        val mayorStepdownItem = icon(
            Material.BELL,
            "<yellow>Mayor step down:</yellow> <white>$mayorStepdownLabel</white>",
            listOf(
                "<gray>Allows the current mayor to step down</gray>",
                "<gray>and immediately open elections.</gray>",
                "",
                "<gray>OFF:</gray> <dark_gray>disabled</dark_gray>",
                "<gray>NO_MAYOR:</gray> <dark_gray>clear mayor & perks</dark_gray>",
                "<gray>KEEP_MAYOR:</gray> <dark_gray>keep until new term</dark_gray>",
                "",
                "<dark_gray>Click to cycle.</dark_gray>"
            )
        )
        inv.setItem(12, mayorStepdownItem)
        setConfirm(12, mayorStepdownItem) { p, _ ->
            val next = s.mayorStepdownPolicy.next()
            plugin.adminActions.updateSettingsConfig("election.mayor_stepdown", next.name)
            plugin.gui.open(p, AdminElectionSettingsMenu(plugin))
        }

        // Tie policy
        val policyLore = listOf(
            "<gray>Left:</gray> <white>next</white>",
            "<gray>Right:</gray> <white>previous</white>",
            "",
            "<gray>SEEDED_RANDOM:</gray> stable random",
            "<gray>INCUMBENT:</gray> if tied, incumbent wins",
            "<gray>EARLIEST_APPLICATION:</gray> earliest apply wins",
            "<gray>ALPHABETICAL:</gray> A→Z"
        )
        val policyItem = icon(
            Material.TARGET,
            "<yellow>Tie policy:</yellow> <white>${s.tiePolicy.name}</white>",
            policyLore
        )
        inv.setItem(16, policyItem)
        setConfirm(16, policyItem) { p, click ->
            val next: TiePolicy = if (click.isRightClick) s.tiePolicy.prev() else s.tiePolicy.next()
            plugin.adminActions.updateSettingsConfig("election.tie_policy", next.name)
            plugin.gui.open(p, AdminElectionSettingsMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p, _ -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private val ClickType.isRightClick get() = this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
}
