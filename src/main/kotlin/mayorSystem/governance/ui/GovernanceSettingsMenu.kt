package mayorSystem.governance.ui

import mayorSystem.MayorPlugin
import mayorSystem.config.MayorStepdownPolicy
import mayorSystem.config.TiePolicy
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class GovernanceSettingsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Governance</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings

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
        inv.setItem(13, mayorStepdownItem)
        setConfirm(13, mayorStepdownItem) { p, _ ->
            val next = s.mayorStepdownPolicy.next()
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "election.mayor_stepdown",
                        next.name,
                        "admin.settings.mayor_stepdown_set",
                        mapOf("value" to next.name)
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, GovernanceSettingsMenu(plugin))
            }
        }

        val policyLore = listOf(
            "<gray>Left:</gray> <white>next</white>",
            "<gray>Right:</gray> <white>previous</white>",
            "",
            "<gray>SEEDED_RANDOM:</gray> stable random",
            "<gray>INCUMBENT:</gray> if tied, incumbent wins",
            "<gray>EARLIEST_APPLICATION:</gray> earliest apply wins",
            "<gray>ALPHABETICAL:</gray> A-Z"
        )
        val policyItem = icon(
            Material.TARGET,
            "<yellow>Tie policy:</yellow> <white>${s.tiePolicy.name}</white>",
            policyLore
        )
        inv.setItem(15, policyItem)
        setConfirm(15, policyItem) { p, click ->
            val next: TiePolicy = if (click.isRightClick) s.tiePolicy.prev() else s.tiePolicy.next()
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "election.tie_policy",
                        next.name,
                        "admin.settings.tie_policy_set",
                        mapOf("value" to next.name)
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, GovernanceSettingsMenu(plugin))
            }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private val ClickType.isRightClick get() = this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
}

