package mayorSystem.system.ui

import mayorSystem.MayorPlugin
import mayorSystem.config.SystemGateOption
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminSettingsEnableOptionsMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Settings: Enable Options</gradient>")
    override val rows: Int = 4

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        inv.setItem(
            4,
            icon(
                Material.LEVER,
                "<gold>Enable Options</gold>",
                listOf("<gray>Choose what the disable toggle affects.</gray>")
            )
        )

        val options = SystemGateOption.values()
        val slots = intArrayOf(10, 11, 12, 13, 14)
        for (i in options.indices) {
            if (i >= slots.size) break
            val opt = options[i]
            val enabled = plugin.settings.enableOptions.contains(opt)
            val status = if (enabled) "<green>Included</green>" else "<gray>Excluded</gray>"
            val item = icon(
                materialFor(opt, enabled),
                if (enabled) "<green>${opt.label}</green>" else "<gray>${opt.label}</gray>",
                listOf(
                    "<gray>${opt.description}</gray>",
                    "",
                    "<gray>When disabled:</gray> $status",
                    "<dark_gray>Click to toggle.</dark_gray>"
                )
            )
            inv.setItem(slots[i], item)
            setConfirm(slots[i], item) { p, _ ->
                val next = plugin.settings.enableOptions.toMutableSet()
                if (next.contains(opt)) next.remove(opt) else next.add(opt)
                val list = next.map { it.name }.sorted()
                plugin.adminActions.updateSettingsConfig(p, "enable_options", list)
                plugin.gui.open(p, AdminSettingsEnableOptionsMenu(plugin))
            }
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(27, back)
        set(27, back) { p -> plugin.gui.open(p, AdminSettingsGeneralMenu(plugin)) }
    }

    private fun materialFor(option: SystemGateOption, enabled: Boolean): Material {
        val base = when (option) {
            SystemGateOption.SCHEDULE -> Material.CLOCK
            SystemGateOption.ACTIONS -> Material.IRON_SWORD
            SystemGateOption.PERKS -> Material.POTION
            SystemGateOption.MAYOR_NPC -> Material.ARMOR_STAND
            SystemGateOption.BROADCASTS -> Material.NOTE_BLOCK
        }
        return if (enabled) base else Material.GRAY_DYE
    }
}

