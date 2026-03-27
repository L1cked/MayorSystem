package mayorSystem.perks.ui

import mayorSystem.MayorPlugin
import mayorSystem.data.RequestStatus
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.perks.ui.AdminPerksMenu
import mayorSystem.ui.UiClickSound
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import java.time.Instant
import kotlinx.coroutines.launch

class AdminPerkRequestsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gold>📚 Perk Requests</gold>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val hasPerm = player.hasPermission(Perms.ADMIN_PERKS_REQUESTS)
        if (!hasPerm) {
            inv.setItem(
                22,
                icon(
                    Material.BARRIER,
                    "<red>No permission</red>",
                    listOf("<gray>You do not have permission to review perk requests.</gray>")
                )
            )
            val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
            inv.setItem(45, back)
            set(45, back) { p -> plugin.gui.open(p, AdminPerksMenu(plugin)) }
            return
        }

        val term = plugin.termService.computeNow().second
        val pending = plugin.store.listRequests(term, RequestStatus.PENDING)

        inv.setItem(4, icon(Material.KNOWLEDGE_BOOK, "<gold>How this works</gold>", listOf(
            "<gray>Left click:</gray> <green>Approve</green>",
            "<gray>Right click:</gray> <red>Deny</red>",
            "<dark_gray>Commands:</dark_gray> <gray>/%title_command% admin perks requests approve|deny (id)</gray>"
        )))

        var slot = 10
        pending.forEach { req ->
            if (slot >= inv.size - 10) return@forEach

            val candName = plugin.server.getOfflinePlayer(req.candidate).name ?: req.candidate.toString()
            val safeTitle = mmSafe(req.title)
            val safeDesc = mmSafe(req.description)
            val item = icon(
                Material.WRITABLE_BOOK,
                "<yellow>#${req.id}</yellow> <white>$safeTitle</white>",
                listOf(
                    "<gray>By:</gray> <white>$candName</white>",
                    "<gray>$safeDesc</gray>",
                    "",
                    "<green>Left-click:</green> approve",
                    "<red>Right-click:</red> deny"
                )
            )

            inv.setItem(slot, item)
            setConfirm(slot, item) { p, click ->
                plugin.scope.launch(plugin.mainDispatcher) {
                    val result = when (click) {
                        ClickType.RIGHT, ClickType.SHIFT_RIGHT -> {
                            overrideClickSound(UiClickSound.DENY)
                            plugin.adminActions.setRequestStatus(p, term, req.id, RequestStatus.DENIED)
                        }
                        else -> {
                            plugin.adminActions.setRequestStatus(p, term, req.id, RequestStatus.APPROVED)
                        }
                    }
                    dispatchResult(p, result, denyOnNonSuccess = true)
                    plugin.gui.open(p, AdminPerkRequestsMenu(plugin))
                }
            }

            slot++
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminPerksMenu(plugin)) }
    }
}

