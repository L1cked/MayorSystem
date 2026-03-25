package mayorSystem.monitoring.ui

import mayorSystem.MayorPlugin
import mayorSystem.monitoring.HealthCheck
import mayorSystem.monitoring.HealthSeverity
import mayorSystem.ui.Menu
import mayorSystem.monitoring.ui.AdminMonitoringMenu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory

class AdminHealthMenu(plugin: MayorPlugin) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#1db954:#52d681>🩺 Health Check</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val checks = plugin.health.run()
        val counts = checks.groupingBy { it.severity }.eachCount()
        val economyProvider = plugin.economy.providerName()
        val economyLine = if (plugin.economy.isAvailable()) {
            "<gray>Economy:</gray> <white>${mmSafe(economyProvider ?: "<unknown>")}</white>"
        } else {
            "<gray>Economy:</gray> <red>Unavailable</red>"
        }

        inv.setItem(
            4,
            icon(
                Material.SPYGLASS,
                "<white>System health</white>",
                listOf(
                    "<gray>OK:</gray> <green>${counts[HealthSeverity.OK] ?: 0}</green>",
                    "<gray>Warn:</gray> <yellow>${counts[HealthSeverity.WARN] ?: 0}</yellow>",
                    "<gray>Error:</gray> <red>${counts[HealthSeverity.ERROR] ?: 0}</red>",
                    economyLine,
                    "",
                    "<dark_gray>Click entries for details.</dark_gray>"
                )
            )
        )

        val slots = contentSlots(inv.size)
        var idx = 0
        for (check in checks) {
            if (idx >= slots.size) break
            val slot = slots[idx]
            idx++

            val mat = when (check.severity) {
                HealthSeverity.OK -> Material.LIME_DYE
                HealthSeverity.WARN -> Material.YELLOW_DYE
                HealthSeverity.ERROR -> Material.RED_DYE
            }

            val name = when (check.severity) {
                HealthSeverity.OK -> "<green>OK</green> <white>${mmSafe(check.title)}</white>"
                HealthSeverity.WARN -> "<yellow>WARN</yellow> <white>${mmSafe(check.title)}</white>"
                HealthSeverity.ERROR -> "<red>ERROR</red> <white>${mmSafe(check.title)}</white>"
            }

            val lore = mutableListOf<String>()
            lore += "<dark_gray>${check.id}</dark_gray>"
            if (check.details.isNotEmpty()) {
                lore += ""
                lore += check.details.take(6).map { "<gray>${mmSafe(it)}</gray>" }
            }
	            val suggestion = check.suggestion
	            if (!suggestion.isNullOrBlank()) {
                lore += ""
                lore += "<white>Fix:</white>"
	                lore += wrapLore(suggestion, 34).map { "<gray>${mmSafe(it)}</gray>" }
            }
            lore += ""
            lore += "<gray>Click:</gray> <white>print full to console</white>"

            val item = icon(mat, name, lore)
            inv.setItem(slot, item)
            // Clicking a row is informational; keep it as a simple UI click.
            set(slot, item) { p, _ ->
                val report = buildReport(listOf(check))
                plugin.logger.info("[MayorSystem HealthCheck] \n$report")
                plugin.messages.msg(p, "admin.monitoring.health_printed_for", mapOf("id" to mmSafe(check.id)))
            }
        }

        // Buttons
        val copy = icon(Material.PAPER, "<white>Copy report</white>", listOf("<gray>Prints full report to console.</gray>"))
        inv.setItem(49, copy)
        setConfirm(49, copy) { p, _ ->
            val report = buildReport(checks)
            plugin.logger.info("[MayorSystem HealthCheck] \n$report")
            plugin.messages.msg(p, "admin.monitoring.health_printed")
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, AdminMonitoringMenu(plugin)) }

        val refresh = icon(Material.SPYGLASS, "<gray>Refresh</gray>")
        inv.setItem(53, refresh)
        set(53, refresh) { p, _ -> plugin.gui.open(p, AdminHealthMenu(plugin)) }
    }

    private fun contentSlots(size: Int): List<Int> {
        val rows = size / 9
        val slots = mutableListOf<Int>()
        for (r in 1 until rows - 1) {
            for (c in 1..7) {
                slots += r * 9 + c
            }
        }
        return slots
    }

    private fun buildReport(checks: List<HealthCheck>): String {
        val sb = StringBuilder()
        for (c in checks) {
            sb.append("[").append(c.severity.name).append("] ").append(c.id).append(" - ").append(c.title).append("\n")
            for (d in c.details) sb.append("  - ").append(d).append("\n")
            if (!c.suggestion.isNullOrBlank()) sb.append("  Fix: ").append(c.suggestion).append("\n")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

}

