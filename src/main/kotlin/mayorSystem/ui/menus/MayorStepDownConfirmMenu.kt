package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.config.MayorStepdownPolicy
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class MayorStepDownConfirmMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val policy: MayorStepdownPolicy
) : Menu(plugin) {

    override val title: Component = gc("menus.mayor_step_down_confirm.title")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val actionsBlocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        val scheduleBlocked = blockedReason(mayorSystem.config.SystemGateOption.SCHEDULE)
        if (actionsBlocked != null || scheduleBlocked != null) {
            val reason = actionsBlocked ?: scheduleBlocked ?: g("menus.mayor_step_down_confirm.unavailable.fallback")
            inv.setItem(13, icon(Material.BARRIER, g("menus.mayor_step_down_confirm.unavailable.name"), listOf(reason)))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(18, back)
            set(18, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        val currentMayor = plugin.store.winner(term)
        if (currentMayor == null || currentMayor != player.uniqueId) {
            inv.setItem(13, icon(Material.BARRIER, g("menus.mayor_step_down_confirm.only_mayor.name")))
            val back = icon(Material.ARROW, g("menus.common.back.name"))
            inv.setItem(18, back)
            set(18, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        val electionTerm = term + 1
        val effectLines = when (policy) {
            MayorStepdownPolicy.NO_MAYOR -> listOf(
                g("menus.mayor_step_down_confirm.effects.no_mayor.1"),
                g("menus.mayor_step_down_confirm.effects.no_mayor.2")
            )
            MayorStepdownPolicy.KEEP_MAYOR -> listOf(
                g("menus.mayor_step_down_confirm.effects.keep_mayor.1"),
                g("menus.mayor_step_down_confirm.effects.keep_mayor.2")
            )
            MayorStepdownPolicy.OFF -> listOf(g("menus.mayor_step_down_confirm.effects.off"))
        }

        val lore = buildList {
            add(g("menus.mayor_step_down_confirm.profile.lore.current_term", mapOf("term" to (term + 1).toString())))
            add(g("menus.mayor_step_down_confirm.profile.lore.election_term", mapOf("term" to electionTerm.toString())))
            add("")
            addAll(effectLines)
        }

        val head = selfHead(player, g("menus.mayor_step_down_confirm.profile.name", mapOf("player" to player.name)), lore)
        inv.setItem(13, head)

        val cancel = icon(Material.RED_DYE, g("menus.mayor_step_down_confirm.cancel.name"), listOf(g("menus.mayor_step_down_confirm.cancel.lore")))
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }

        val confirm = icon(Material.LIME_DYE, g("menus.mayor_step_down_confirm.confirm.name"), listOf(g("menus.mayor_step_down_confirm.confirm.lore")))
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ ->
            plugin.scope.launch(plugin.mainDispatcher) {
                val completed = plugin.actionCoordinator.trySerialized("mayor-stepdown:${p.uniqueId}:$term") {
                    if (!p.hasPermission(Perms.CANDIDATE)) {
                        denyMsg(p, "errors.no_permission")
                        plugin.gui.open(p, MainMenu(plugin))
                        return@trySerialized
                    }
                    val ok = plugin.termService.forceMayorStepdownNow(p.uniqueId, policy)
                    if (ok) {
                        plugin.messages.msg(p, "public.mayor_stepdown_accepted", mapOf("term" to electionTerm.toString()))
                    } else {
                        denyMsg(p, "public.stepdown_unavailable")
                    }
                    plugin.gui.open(p, MainMenu(plugin))
                }
                if (completed == null) {
                    denyMsg(p, "errors.action_in_progress")
                    plugin.gui.open(p, MainMenu(plugin))
                }
            }
        }
    }
}
