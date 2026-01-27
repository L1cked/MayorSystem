package mayorSystem.ui.menus

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.UUID

/**
 * Choose ban type for a player.
 */
class AdminApplyBanTypeMenu(
    plugin: MayorPlugin,
    private val targetUuid: UUID,
    private val targetName: String
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gold>🚫 Apply Ban</gold>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        // Target
        inv.setItem(
            13,
            playerHead(
                targetUuid,
                "<yellow>$targetName</yellow>",
                listOf(
                    "<gray>Ban this player from applying to elections.</gray>",
                    "<dark_gray>UUID:</dark_gray> <gray>${targetUuid}</gray>"
                )
            )
        )

        // Perma ban
        inv.setItem(
            11,
            icon(
                Material.BARRIER,
                "<red>Permanent Ban</red>",
                listOf(
                    "<gray>Blocks applying forever.</gray>",
                    "<dark_gray>Can be removed later.</dark_gray>"
                )
            )
        )
        setConfirm(11, inv.getItem(11)!!) { admin ->
            plugin.adminActions.setApplyBanPermanent(admin, targetUuid, targetName)
            admin.sendMessage("§c[target] §f$targetName §cwas permanently banned from applying.")
            plugin.gui.open(admin, AdminApplyBanTypeMenu(plugin, targetUuid, targetName))
        }

        // Temp ban
        inv.setItem(
            15,
            icon(
                Material.CLOCK,
                "<gold>Temporary Ban</gold>",
                listOf(
                    "<gray>Choose a duration.</gray>"
                )
            )
        )
        set(15, inv.getItem(15)!!) { admin ->
            // Provide a back menu so the duration menu can return here.
            plugin.gui.open(admin, AdminApplyBanDurationMenu(plugin, targetUuid, targetName, this))
        }

        // Unban
        inv.setItem(
            22,
            icon(
                Material.MILK_BUCKET,
                "<green>Unban</green>",
                listOf(
                    "<gray>Remove any apply ban.</gray>"
                )
            )
        )
        setConfirm(22, inv.getItem(22)!!) { admin ->
            plugin.adminActions.clearApplyBan(admin, targetUuid)
            admin.sendMessage("§a[target] §f$targetName §awas unbanned from applying.")
            plugin.gui.open(admin, AdminApplyBanTypeMenu(plugin, targetUuid, targetName))
        }

        // Current state
        val ban = plugin.store.activeApplyBan(targetUuid)
        inv.setItem(
            4,
            icon(
                if (ban == null) Material.LIME_DYE else Material.RED_DYE,
                if (ban == null) "<green>Not banned</green>" else "<red>Banned</red>",
                if (ban == null) listOf("<gray>This player can apply normally.</gray>") else {
                    val until = ban.until
                    val lines = mutableListOf<String>()
                    if (ban.permanent) lines.add("<red>Permanent</red>")
                    else if (until != null) lines.add("<gold>Until:</gold> <white>${until}</white>")
                    lines
                }
            )
        )

        // Back
        inv.setItem(18, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(18, inv.getItem(18)!!) { admin ->
            plugin.gui.open(admin, AdminApplyBanSearchMenu(plugin))
        }
    }
}
