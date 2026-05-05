package mayorSystem.messaging.ui

import mayorSystem.MayorPlugin
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class AdminBroadcastSettingsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff512f:#f09819>Election Broadcasts</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val bcEnabled = plugin.config.getBoolean("election.broadcast.enabled", true)
        val bcMode = normalizeMode(plugin.config.getString("election.broadcast.mode", "TITLE"), defaultMode = "TITLE")
        val voteMode = normalizeMode(plugin.config.getString("election.broadcast.vote.mode", "CHAT"), defaultMode = "CHAT")
        val applyMode = normalizeMode(plugin.config.getString("election.broadcast.apply.mode", "DISABLED"), defaultMode = "DISABLED")

        val lifecycleItem = icon(
            if (bcEnabled) Material.BELL else Material.GRAY_DYE,
            "<yellow>Election Lifecycle Broadcasts</yellow>",
            listOf(
                "<gray>Broadcasts:</gray> <white>Election Open + New Mayor + No Candidates</white>",
                "<gray>Enabled:</gray> <white>$bcEnabled</white>",
                "<gray>Mode:</gray> <white>$bcMode</white>",
                "",
                "<gray>Left click:</gray> <white>Toggle</white>",
                "<gray>Right click:</gray> <white>Switch CHAT/TITLE/BOTH</white>"
            )
        )
        inv.setItem(11, lifecycleItem)
        setConfirm(11, lifecycleItem) { p, click ->
            plugin.scope.launch(plugin.mainDispatcher) {
                val result = if (click.isRightClick) {
                    val next = when (bcMode) {
                        "TITLE" -> "CHAT"
                        "CHAT" -> "BOTH"
                        else -> "TITLE"
                    }
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "election.broadcast.mode",
                        next,
                        "admin.settings.reloaded"
                    )
                } else if (click.isLeftClick) {
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "election.broadcast.enabled",
                        !bcEnabled,
                        "admin.settings.reloaded"
                    )
                } else {
                    null
                }
                if (result != null) {
                    dispatchResult(p, result, denyOnNonSuccess = true)
                }
                plugin.gui.open(p, AdminBroadcastSettingsMenu(plugin))
            }
        }

        val voteItem = icon(
            Material.PAPER,
            "<yellow>Vote Activity Broadcast</yellow>",
            listOf(
                "<gray>Event:</gray> <white>Player votes</white>",
                "<gray>Mode:</gray> <white>$voteMode</white>",
                "",
                "<gray>Click:</gray> <white>Cycle DISABLED/CHAT/TITLE/BOTH</white>"
            )
        )
        inv.setItem(13, voteItem)
        setConfirm(13, voteItem) { p, _ ->
            plugin.scope.launch(plugin.mainDispatcher) {
                val next = nextMode(voteMode)
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "election.broadcast.vote.mode",
                        next,
                        "admin.settings.reloaded"
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminBroadcastSettingsMenu(plugin))
            }
        }

        val applyItem = icon(
            Material.WRITABLE_BOOK,
            "<yellow>Apply Activity Broadcast</yellow>",
            listOf(
                "<gray>Event:</gray> <white>Player applies</white>",
                "<gray>Mode:</gray> <white>$applyMode</white>",
                "",
                "<gray>Click:</gray> <white>Cycle DISABLED/CHAT/TITLE/BOTH</white>"
            )
        )
        inv.setItem(15, applyItem)
        setConfirm(15, applyItem) { p, _ ->
            plugin.scope.launch(plugin.mainDispatcher) {
                val next = nextMode(applyMode)
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "election.broadcast.apply.mode",
                        next,
                        "admin.settings.reloaded"
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminBroadcastSettingsMenu(plugin))
            }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private fun normalizeMode(raw: String?, defaultMode: String): String {
        return when (raw?.uppercase()) {
            "DISABLED", "NONE", "OFF" -> "DISABLED"
            "CHAT" -> "CHAT"
            "BOTH" -> "BOTH"
            "TITLE" -> "TITLE"
            else -> defaultMode
        }
    }

    private fun nextMode(current: String): String {
        return when (current) {
            "DISABLED" -> "CHAT"
            "CHAT" -> "TITLE"
            "TITLE" -> "BOTH"
            else -> "DISABLED"
        }
    }

    private val ClickType.isLeftClick get() = this == ClickType.LEFT || this == ClickType.SHIFT_LEFT
    private val ClickType.isRightClick get() = this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
}

