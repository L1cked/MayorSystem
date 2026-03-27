package mayorSystem.perks.ui

import mayorSystem.MayorPlugin
import mayorSystem.config.CustomRequestCondition
import mayorSystem.system.ui.AdminSettingsMenu
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class AdminSettingsCustomRequestsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Settings: Custom Requests</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings
        val condition = s.customRequestCondition
        val reqLimit = s.customRequestsLimitPerTerm

        val condReadable = when (condition) {
            CustomRequestCondition.DISABLED -> "<red>DISABLED</red> <gray>(requests blocked)</gray>"
            CustomRequestCondition.NONE -> "<green>NONE</green> <gray>(any candidate)</gray>"
            CustomRequestCondition.ELECTED_ONCE -> "<gold>ELECTED_ONCE</gold> <gray>(must have been mayor once)</gray>"
            CustomRequestCondition.APPLY_REQUIREMENTS -> "<aqua>APPLY_REQUIREMENTS</aqua> <gray>(must meet apply requirements)</gray>"
        }

        val customReqItem = icon(
            Material.COMPARATOR,
            "<yellow>Custom Perk Requests</yellow>",
            listOf(
                "<gray>Condition:</gray> $condReadable",
                "<gray>Limit per term:</gray> <white>$reqLimit</white>",
                "",
                "<gray>Left click:</gray> <white>Next condition</white>",
                "<gray>Right click:</gray> <white>Previous condition</white>",
                "<gray>Shift-left/right:</gray> <white>Adjust limit</white>"
            )
        )
        inv.setItem(13, customReqItem)
        setConfirm(13, customReqItem) { p, click ->
            plugin.scope.launch(plugin.mainDispatcher) {
                val result = if (click.isShiftClick) {
                    val delta = if (click.isLeftClick) 1 else -1
                    val nextLimit = (reqLimit + delta).coerceAtLeast(0)
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "custom_requests.limit_per_term",
                        nextLimit,
                        "admin.settings.custom_limit_set",
                        mapOf("value" to nextLimit.toString())
                    )
                } else {
                    val next = if (click.isRightClick) condition.prev() else condition.next()
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "custom_requests.request_condition",
                        next.name,
                        "admin.settings.custom_condition_set",
                        mapOf("value" to next.name)
                    )
                }
                dispatchResult(p, result, denyOnNonSuccess = true)
                plugin.gui.open(p, AdminSettingsCustomRequestsMenu(plugin))
            }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    // ClickType helpers
    private val ClickType.isLeftClick get() = this == ClickType.LEFT || this == ClickType.SHIFT_LEFT
    private val ClickType.isRightClick get() = this == ClickType.RIGHT || this == ClickType.SHIFT_RIGHT
    private val ClickType.isShiftClick get() = this == ClickType.SHIFT_LEFT || this == ClickType.SHIFT_RIGHT
}

