package mayorSystem.system.ui

import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.regex.Pattern
import kotlinx.coroutines.launch

class AdminSettingsMayorGroupMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>Settings: Mayor Group</gradient>")
    override val rows: Int = 3

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings

        val summary = icon(
            Material.NAME_TAG,
            "<yellow>Mayor LuckPerms Group</yellow>",
            listOf(
                "<gray>Status:</gray> <white>${if (s.usernameGroupEnabled) "Enabled" else "Disabled"}</white>",
                "<gray>Group:</gray> <white>${mmSafe(s.usernameGroup)}</white>"
            )
        )
        inv.setItem(4, summary)

        val enabledItem = icon(
            if (s.usernameGroupEnabled) Material.LIME_DYE else Material.GRAY_DYE,
            "<yellow>Mayor LuckPerms Group:</yellow> <white>${s.usernameGroupEnabled}</white>",
            listOf(
                "<gray>When enabled, the elected player gets</gray>",
                "<gray>a persistent LuckPerms group membership.</gray>",
                "",
                "<dark_gray>Click to toggle.</dark_gray>",
                "<dark_gray>/%title_command% admin settings mayor_group_enabled <true|false></dark_gray>"
            )
        )
        inv.setItem(11, enabledItem)
        setConfirm(11, enabledItem) { p, _ ->
            val next = !s.usernameGroupEnabled
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "title.username_group_enabled",
                        next,
                        "admin.settings.mayor_group_enabled_set",
                        mapOf("value" to next.toString())
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminSettingsMayorGroupMenu(plugin))
            }
        }

        val groupName = s.usernameGroup
        val groupItem = icon(
            Material.NAME_TAG,
            "<yellow>LuckPerms Group:</yellow> <white>$groupName</white>",
            listOf(
                "<gray>Configure this group in LuckPerms with</gray>",
                "<gray>the permissions/meta you want for the current %title_name_lower%.</gray>",
                "",
                "<dark_gray>Click to edit.</dark_gray>",
                "<dark_gray>Allowed chars: letters, numbers, _, -, .</dark_gray>",
                "<dark_gray>/%title_command% admin settings mayor_group <name></dark_gray>"
            )
        )
        inv.setItem(13, groupItem)
        setConfirm(13, groupItem) { p, _ ->
            plugin.gui.openAnvilPrompt(
                p,
                mm.deserialize(themed("<yellow>Set LuckPerms Group</yellow>")),
                groupName
            ) { who, input ->
                val value = input?.trim().orEmpty()
                if (value.isBlank() || !GROUP_NAME_PATTERN.matcher(value).matches()) {
                    plugin.messages.msg(who, "admin.settings.mayor_group_invalid")
                    plugin.gui.open(who, AdminSettingsMayorGroupMenu(plugin))
                    return@openAnvilPrompt
                }

                plugin.scope.launch(plugin.mainDispatcher) {
                    dispatchResult(
                        who,
                        plugin.adminActions.updateSettingsConfig(
                            who,
                            "title.username_group",
                            value,
                            "admin.settings.mayor_group_set",
                            mapOf("value" to value)
                        ),
                        denyOnNonSuccess = true
                    )
                    plugin.gui.open(who, AdminSettingsMayorGroupMenu(plugin))
                }
            }
        }

        val syncItem = icon(
            Material.CLOCK,
            "<yellow>Sync Group Now</yellow>",
            listOf(
                "<gray>Re-apply group membership based on</gray>",
                "<gray>current elected %title_name_lower% state.</gray>",
                "",
                "<dark_gray>Click to sync.</dark_gray>"
            )
        )
        inv.setItem(15, syncItem)
        setConfirm(15, syncItem) { p, _ ->
            plugin.mayorUsernamePrefix.syncAllOnline()
            plugin.messages.msg(p, "admin.settings.mayor_group_synced")
            plugin.gui.open(p, AdminSettingsMayorGroupMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(18, back)
        set(18, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private companion object {
        val GROUP_NAME_PATTERN: Pattern = Pattern.compile("^[A-Za-z0-9_.-]+$")
    }
}
