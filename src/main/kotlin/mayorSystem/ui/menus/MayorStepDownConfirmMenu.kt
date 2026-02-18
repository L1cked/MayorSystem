package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.config.MayorStepdownPolicy
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Confirmation menu for mayors stepping down mid-term.
 */
class MayorStepDownConfirmMenu(
    plugin: MayorPlugin,
    private val term: Int,
    private val policy: MayorStepdownPolicy
) : Menu(plugin) {

    override val title: Component = mm.deserialize(themed("<red>Confirm %title_name% Step Down</red>"))
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val actionsBlocked = blockedReason(mayorSystem.config.SystemGateOption.ACTIONS)
        val scheduleBlocked = blockedReason(mayorSystem.config.SystemGateOption.SCHEDULE)
        if (actionsBlocked != null || scheduleBlocked != null) {
            val reason = actionsBlocked ?: scheduleBlocked ?: "<red>Step down unavailable</red>"
            inv.setItem(13, icon(Material.BARRIER, "<red>Step down unavailable</red>", listOf(reason)))
            val back = icon(Material.ARROW, "<gray>â¬… Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        val currentMayor = plugin.store.winner(term)
        if (currentMayor == null || currentMayor != player.uniqueId) {
            inv.setItem(13, icon(Material.BARRIER, "<red>Only the mayor can do this</red>"))
            val back = icon(Material.ARROW, "<gray>â¬… Back</gray>")
            inv.setItem(18, back)
            set(18, back) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }
            return
        }

        val electionTerm = term + 1
        val effectLines = when (policy) {
            MayorStepdownPolicy.NO_MAYOR -> listOf(
                "<gray>Election opens immediately.</gray>",
                "<red>%title_name% cleared until next term.</red>"
            )
            MayorStepdownPolicy.KEEP_MAYOR -> listOf(
                "<gray>Election opens immediately.</gray>",
                "<gray>%title_name% stays until new term starts.</gray>"
            )
            MayorStepdownPolicy.OFF -> listOf("<red>%title_name% step down is disabled.</red>")
        }

        val lore = buildList {
            add("<gray>Current term:</gray> <white>#${term + 1}</white>")
            add("<gray>Election term:</gray> <white>#$electionTerm</white>")
            add("")
            addAll(effectLines)
        }

        val head = selfHead(player, "<gold>${player.name}</gold>", lore)
        inv.setItem(13, head)

        val cancel = icon(Material.RED_DYE, "<red>Cancel</red>", listOf("<gray>Keep the current %title_name_lower%.</gray>"))
        inv.setItem(11, cancel)
        setDeny(11, cancel) { p, _ -> plugin.gui.open(p, MainMenu(plugin)) }

        val confirm = icon(Material.LIME_DYE, "<green>Confirm</green>", listOf("<gray>Begin a new election now.</gray>"))
        inv.setItem(15, confirm)
        setConfirm(15, confirm) { p, _ ->
            plugin.scope.launch(plugin.mainDispatcher) {
                if (!p.hasPermission(Perms.CANDIDATE)) {
                    denyMsg(p, "errors.no_permission")
                    plugin.gui.open(p, MainMenu(plugin))
                    return@launch
                }
                val ok = plugin.termService.forceMayorStepdownNow(p.uniqueId, policy)
                if (ok) {
                    p.sendMessage("Step down accepted. Election opened for term #$electionTerm.")
                } else {
                    denyMsg(p, "public.stepdown_unavailable")
                }
                plugin.gui.open(p, MainMenu(plugin))
            }
        }
    }
}






