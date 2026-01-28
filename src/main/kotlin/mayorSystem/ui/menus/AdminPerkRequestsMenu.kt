package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.data.RequestStatus
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import mayorSystem.ui.UiClickSound
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import java.time.Instant

class AdminPerkRequestsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gold>📚 Perk Requests</gold>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val hasPerm = player.hasPermission(Perms.ADMIN_PERKS_REQUESTS)
                || player.hasPermission(Perms.LEGACY_ADMIN_PERKS)
                || player.hasPermission(Perms.LEGACY_ADMIN_UMBRELLA)
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
            set(45, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
            return
        }

        val term = plugin.termService.compute(Instant.now()).second
        val pending = plugin.store.listRequests(term, RequestStatus.PENDING)

        inv.setItem(4, icon(Material.KNOWLEDGE_BOOK, "<gold>How this works</gold>", listOf(
            "<gray>Left click:</gray> <green>Approve</green>",
            "<gray>Right click:</gray> <red>Deny</red>",
            "<dark_gray>Commands:</dark_gray> <gray>/mayor admin perks requests approve|deny (id)</gray>"
        )))

        var slot = 10
        pending.forEach { req ->
            if (slot >= inv.size - 10) return@forEach

            val candName = plugin.server.getOfflinePlayer(req.candidate).name ?: req.candidate.toString()
            val item = icon(
                Material.WRITABLE_BOOK,
                "<yellow>#${req.id}</yellow> <white>${req.title}</white>",
                listOf(
                    "<gray>By:</gray> <white>$candName</white>",
                    "<gray>${req.description}</gray>",
                    "",
                    "<green>Left-click:</green> approve",
                    "<red>Right-click:</red> deny"
                )
            )

            inv.setItem(slot, item)
            setConfirm(slot, item) { p, click ->
                when (click) {
                    ClickType.RIGHT, ClickType.SHIFT_RIGHT -> {
                        // Denying a request should sound like a deny.
                        overrideClickSound(UiClickSound.DENY)
                        plugin.adminActions.setRequestStatus(p, term, req.id, RequestStatus.DENIED)
                        p.sendMessage("Denied request #${req.id}.")
                    }
                    else -> {
                        plugin.adminActions.setRequestStatus(p, term, req.id, RequestStatus.APPROVED)
                        p.sendMessage("Approved request #${req.id}.")
                    }
                }
                plugin.gui.open(p, AdminPerkRequestsMenu(plugin))
            }

            slot++
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p -> plugin.gui.open(p, AdminMenu(plugin)) }
    }
}
