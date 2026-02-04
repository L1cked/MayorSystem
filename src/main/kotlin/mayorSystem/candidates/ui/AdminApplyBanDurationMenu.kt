package mayorSystem.candidates.ui

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Choose a duration for a temporary apply ban.
 *
 * Kept simple and "admin-proof": presets only.
 */
class AdminApplyBanDurationMenu(
    plugin: MayorPlugin,
    private val targetUuid: UUID,
    private val targetName: String,
    private val back: Menu
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<red>⏳ Temp Ban Duration</red>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val presets = listOf(
            1L to "1 day",
            3L to "3 days",
            7L to "7 days",
            14L to "14 days",
            30L to "30 days",
            90L to "90 days"
        )

        var slot = 10
        for ((days, label) in presets) {
            val item = icon(
                Material.CLOCK,
                "<gold>$label</gold>",
                listOf(
                    "<gray>Ban:</gray> <white>$targetName</white>",
                    "<gray>Blocks applying for:</gray> <white>$label</white>",
                    "",
                    "<yellow>Click to apply</yellow>"
                )
            )
            inv.setItem(slot, item)
            setConfirm(slot, item) { admin, _ ->
                plugin.scope.launch(plugin.mainDispatcher) {
                    val until = OffsetDateTime.now().plus(days, ChronoUnit.DAYS)
                    plugin.adminActions.setApplyBanTemp(admin, targetUuid, targetName, until)
                    admin.sendMessage(mm.deserialize("<green>$tempBanMessage</green>"))
                    plugin.gui.open(admin, back)
                }
            }
            slot++
            if (slot == 17) slot = 19
        }

        inv.setItem(18, icon(Material.ARROW, "<gray>⬅ Back</gray>"))
        set(18, inv.getItem(18)!!) { admin, _ -> plugin.gui.open(admin, back) }
    }

    private val tempBanMessage: String
        get() = "Temp-ban applied to $targetName (apply blocked)."
}

