package mayorSystem.system.ui

import mayorSystem.MayorPlugin
import mayorSystem.security.Perms
import mayorSystem.ui.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import kotlinx.coroutines.launch

class AdminSettingsGeneralMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#ff0000:#ff7a00>System Settings</gradient>")
    override val rows: Int = 4

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val s = plugin.settings
        val enabledItem = icon(
            if (s.enabled) Material.LIME_DYE else Material.RED_DYE,
            "<yellow>Plugin Enabled:</yellow> <white>${s.enabled}</white>",
            listOf("<gray>Toggle the entire system.</gray>") +
                optionsLore(
                    title = "Disable affects:",
                    options = s.enableOptions,
                    command = "/%title_command% admin settings enable_options <option>"
                )
        )
        inv.setItem(13, enabledItem)
        setConfirm(13, enabledItem) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_SETTINGS_EDIT)) return@setConfirm
            val next = !s.enabled
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "enabled",
                        next,
                        "admin.settings.enabled_set",
                        mapOf("value" to next.toString())
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminSettingsGeneralMenu(plugin))
            }
        }

        val pauseItem = icon(
            if (s.pauseEnabled) Material.YELLOW_DYE else Material.GRAY_DYE,
            "<yellow>Pause Schedule:</yellow> <white>${s.pauseEnabled}</white>",
            listOf("<gray>Freeze elections/term timers.</gray>") +
                optionsLore(
                    title = "Pause affects:",
                    options = s.pauseOptions,
                    command = "/%title_command% admin settings pause_options <option>"
                )
        )
        inv.setItem(11, pauseItem)
        setConfirm(11, pauseItem) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_SETTINGS_EDIT)) return@setConfirm
            val next = !s.pauseEnabled
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "pause.enabled",
                        next,
                        "admin.settings.pause_enabled_set",
                        mapOf("value" to next.toString())
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminSettingsGeneralMenu(plugin))
            }
        }

        val publicItem = icon(
            if (s.publicEnabled) Material.LIME_DYE else Material.GRAY_DYE,
            "<yellow>Public Access:</yellow> <white>${s.publicEnabled}</white>",
            listOf("<gray>Toggle player access (staff unaffected).</gray>")
        )
        inv.setItem(15, publicItem)
        setConfirm(15, publicItem) { p, _ ->
            if (!requireAnyPerm(p, listOf(Perms.ADMIN_SETTINGS_EDIT, Perms.ADMIN_SYSTEM_TOGGLE))) return@setConfirm
            if (!s.enabled) {
                plugin.messages.msg(p, "admin.system.master_off")
                return@setConfirm
            }
            val next = !s.publicEnabled
            plugin.scope.launch(plugin.mainDispatcher) {
                dispatchResult(
                    p,
                    plugin.adminActions.updateSettingsConfig(
                        p,
                        "public_enabled",
                        next,
                        "admin.settings.public_enabled_set",
                        mapOf("value" to next.toString())
                    ),
                    denyOnNonSuccess = true
                )
                plugin.gui.open(p, AdminSettingsGeneralMenu(plugin))
            }
        }

        val pauseOptionsItem = icon(
            Material.COMPARATOR,
            "<yellow>Pause Options</yellow>",
            listOf("<gray>Configure what pause affects.</gray>") +
                optionsLore(
                    title = "Current:",
                    options = s.pauseOptions,
                    command = "Click to edit"
                )
        )
        inv.setItem(20, pauseOptionsItem)
        set(20, pauseOptionsItem) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_SETTINGS_EDIT)) return@set
            plugin.gui.open(p, AdminSettingsPauseOptionsMenu(plugin))
        }

        val enableOptionsItem = icon(
            Material.COMPARATOR,
            "<yellow>Enable Options</yellow>",
            listOf("<gray>Configure what disable affects.</gray>") +
                optionsLore(
                    title = "Current:",
                    options = s.enableOptions,
                    command = "Click to edit"
                )
        )
        inv.setItem(22, enableOptionsItem)
        set(22, enableOptionsItem) { p, _ ->
            if (!requirePerm(p, Perms.ADMIN_SETTINGS_EDIT)) return@set
            plugin.gui.open(p, AdminSettingsEnableOptionsMenu(plugin))
        }

        val back = icon(Material.ARROW, "<gray><- Back</gray>")
        inv.setItem(27, back)
        set(27, back) { p -> plugin.gui.open(p, AdminSettingsMenu(plugin)) }
    }

    private fun optionsLore(
        title: String,
        options: Set<mayorSystem.config.SystemGateOption>,
        command: String
    ): List<String> {
        val lines = mutableListOf<String>()
        lines += ""
        lines += "<gray>$title</gray>"
        if (options.isEmpty()) {
            lines += "<dark_gray>(none)</dark_gray>"
        } else {
            options.sortedBy { it.name }.forEach { opt ->
                lines += "<dark_gray>-</dark_gray> <white>${opt.name}</white> <gray>${opt.description}</gray>"
            }
        }
        lines += "<dark_gray>$command</dark_gray>"
        return lines
    }
}

