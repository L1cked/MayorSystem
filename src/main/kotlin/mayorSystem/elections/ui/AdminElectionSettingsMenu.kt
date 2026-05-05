package mayorSystem.elections.ui

import mayorSystem.MayorPlugin
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class AdminElectionSettingsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Election Rules</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings

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
        inv.setItem(11, voteChangeItem)
        setConfirm(11, voteChangeItem) { p, _ ->
            val next = !s.allowVoteChange
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "election.allow_vote_change",
                        next,
                        "admin.settings.allow_vote_change_set",
                        mapOf("value" to next.toString())
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminElectionSettingsMenu(plugin))
            }
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
        inv.setItem(15, reapplyItem)
        setConfirm(15, reapplyItem) { p, _ ->
            val next = !s.stepdownAllowReapply
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "election.stepdown.allow_reapply",
                        next,
                        "admin.settings.stepdown_reapply_set",
                        mapOf("value" to next.toString())
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminElectionSettingsMenu(plugin))
            }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p, _ -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }
}

