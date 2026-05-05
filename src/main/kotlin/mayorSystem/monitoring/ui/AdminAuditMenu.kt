package mayorSystem.monitoring.ui

import mayorSystem.MayorPlugin
import mayorSystem.monitoring.AuditEvent
import mayorSystem.ui.Menu
import mayorSystem.monitoring.ui.AdminMonitoringMenu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.time.format.DateTimeFormatter
import java.util.Locale

class AdminAuditMenu(
    plugin: MayorPlugin,
    private val page: Int = 0,
    private val termFilter: Int? = null,
    private val actorFilter: String? = null,
    private val actionFilter: String? = null
) : Menu(plugin) {

    override val title: Component = mm.deserialize("<gradient:#7f00ff:#e100ff>📜 Audit Log</gradient>")
    override val rows: Int = 6

    override fun draw(player: Player, inv: Inventory) {
        border(inv)

        val all = plugin.audit.recent()
        val filtered = all.asSequence()
            .filter { termFilter == null || it.term == termFilter }
            .filter { actorFilter.isNullOrBlank() || it.actorName.contains(actorFilter, ignoreCase = true) }
            .filter { actionFilter.isNullOrBlank() || it.action.contains(actionFilter, ignoreCase = true) }
            .toList()

        inv.setItem(
            4,
            icon(
                Material.PAPER,
                "<white>Events:</white> <gold>${filtered.size}</gold>",
                listOf(
                    "<gray>Term filter:</gray> <white>${termFilter?.let { "#${it + 1}" } ?: "<none>"}</white>",
                    "<gray>Actor filter:</gray> <white>${mmSafe(actorFilter ?: "<none>")}</white>",
                    "<gray>Action filter:</gray> <white>${mmSafe(actionFilter ?: "<none>")}</white>",
                    "",
                    "<dark_gray>Newest at bottom (scroll by pages).</dark_gray>"
                )
            )
        )

        val slots = contentSlots(inv.size)
        // Keep stable ordering: oldest -> newest, page from the end.
        val perPage = slots.size
        val totalPages = ((filtered.size + perPage - 1) / perPage).coerceAtLeast(1)
        val safePage = page.coerceIn(0, totalPages - 1)

        val startFromEnd = (filtered.size - (safePage + 1) * perPage).coerceAtLeast(0)
        val endExclusive = (startFromEnd + perPage).coerceAtMost(filtered.size)
        val pageEvents = filtered.subList(startFromEnd, endExclusive)

        var i = 0
        for (e in pageEvents) {
            if (i >= slots.size) break
            val slot = slots[i]
            i++
            val item = eventItem(e)
            inv.setItem(slot, item)
            // Viewing details is navigation / info, not a confirm action.
            set(slot, item) { p, _ ->
                val lines = describeEvent(e)
                plugin.messages.msg(p, "admin.monitoring.audit_event", mapOf("action" to mmSafe(e.action)))
                lines.forEach { p.sendMessage(mm.deserialize(it)) }
            }
        }

        // Paging + filter controls
        val prev = icon(Material.ARROW, "<gray>⬅ Prev</gray>")
        inv.setItem(46, prev)
        set(46, prev) { p, _ ->
            if (safePage >= totalPages - 1) {
                denyClick()
            } else {
                plugin.gui.open(p, AdminAuditMenu(plugin, safePage + 1, termFilter, actorFilter, actionFilter))
            }
        }

        val next = icon(Material.ARROW, "<gray>Next ➡</gray>")
        inv.setItem(53, next)
        set(53, next) { p, _ ->
            if (safePage <= 0) {
                denyClick()
            } else {
                plugin.gui.open(p, AdminAuditMenu(plugin, safePage - 1, termFilter, actorFilter, actionFilter))
            }
        }

        val filterTerm = icon(Material.CLOCK, "<white>Filter term</white>", listOf("<gray>Click to set/clear term filter.</gray>"))
        inv.setItem(47, filterTerm)
        set(47, filterTerm) { p, _ ->
            plugin.gui.openAnvilPrompt(
                p,
                mm.deserialize("<white>Filter term (number)</white>"),
                termFilter?.let { (it + 1).toString() } ?: "") { who, text ->
                val parsed = text?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let { it - 1 }
                val nextPage = if (text == null) safePage else 0
                plugin.gui.open(who, AdminAuditMenu(plugin, nextPage, if (text == null) termFilter else parsed, actorFilter, actionFilter))
            }
        }

        val filterActor = icon(Material.PLAYER_HEAD, "<white>Filter actor</white>", listOf("<gray>Partial name match.</gray>"))
        inv.setItem(48, filterActor)
        set(48, filterActor) { p, _ ->
            plugin.gui.openAnvilPrompt(
                p,
                mm.deserialize("<white>Filter actor</white>"),
                actorFilter ?: "") { who, text ->
                val nextVal = text?.trim()?.takeIf { it.isNotBlank() }
                val nextPage = if (text == null) safePage else 0
                plugin.gui.open(who, AdminAuditMenu(plugin, nextPage, termFilter, if (text == null) actorFilter else nextVal, actionFilter))
            }
        }

        val filterAction = icon(Material.NAME_TAG, "<white>Filter action</white>", listOf("<gray>Partial match (ex: REQUEST, CONFIG).</gray>"))
        inv.setItem(49, filterAction)
        set(49, filterAction) { p, _ ->
            plugin.gui.openAnvilPrompt(
                p,
                mm.deserialize("<white>Filter action</white>"),
                actionFilter ?: "") { who, text ->
                val nextVal = text?.trim()?.takeIf { it.isNotBlank() }
                val nextPage = if (text == null) safePage else 0
                plugin.gui.open(who, AdminAuditMenu(plugin, nextPage, termFilter, actorFilter, if (text == null) actionFilter else nextVal))
            }
        }

        val clear = icon(Material.BARRIER, "<red>Clear filters</red>")
        inv.setItem(50, clear)
        set(50, clear) { p, _ -> plugin.gui.open(p, AdminAuditMenu(plugin, 0, null, null, null)) }

        val export = icon(Material.WRITABLE_BOOK, "<gold>Export</gold>", listOf("<gray>Writes a JSONL export file.</gray>"))
        inv.setItem(51, export)
        setConfirm(51, export) { p, _ ->
            val f = plugin.audit.export(filtered)
            plugin.messages.msg(p, "admin.monitoring.exported", mapOf("file" to mmSafe(f.name)))
        }

        val back = icon(Material.ARROW, "<gray>⬅ Back</gray>")
        inv.setItem(45, back)
        set(45, back) { p, _ -> plugin.gui.open(p, AdminMonitoringMenu(plugin)) }

        val refresh = icon(Material.SPYGLASS, "<gray>Refresh</gray>")
        inv.setItem(52, refresh)
        set(52, refresh) { p, _ -> plugin.gui.open(p, AdminAuditMenu(plugin, safePage, termFilter, actorFilter, actionFilter)) }
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

    private fun eventItem(e: AuditEvent): org.bukkit.inventory.ItemStack {
        val mat = when {
            e.action.contains("CONFIG", ignoreCase = true) -> Material.PAPER
            e.action.contains("REQUEST", ignoreCase = true) -> Material.WRITABLE_BOOK
            e.action.contains("CANDIDATE", ignoreCase = true) -> Material.NAME_TAG
            e.action.contains("ELECTION", ignoreCase = true) -> Material.COMPARATOR
            e.action.contains("BAN", ignoreCase = true) -> Material.BARRIER
            else -> Material.BOOK
        }

        val ts = TS_FMT.format(e.timestamp)
        val name = "<white>${mmSafe(e.action)}</white>"
        val lore = mutableListOf<String>()
        lore += "<gray>Time:</gray> <white>$ts</white>"
        lore += "<gray>Actor:</gray> <white>${mmSafe(e.actorName)}</white>"
        if (e.term != null) lore += "<gray>Term:</gray> <white>#${e.term + 1}</white>"
        if (!e.target.isNullOrBlank()) lore += "<gray>Target:</gray> <white>${mmSafe(e.target)}</white>"
        if (e.details.isNotEmpty()) {
            lore += ""
            for ((k, v) in e.details.entries.take(6)) {
                lore += "<dark_gray>$k:</dark_gray> <gray>${mmSafe(v)}</gray>"
            }
            if (e.details.size > 6) lore += "<dark_gray>... +${e.details.size - 6} more</dark_gray>"
        }
        lore += ""
        lore += "<gray>Click:</gray> <white>details in chat</white>"

        return icon(mat, name, lore)
    }

    private fun describeEvent(e: AuditEvent): List<String> {
        val lines = mutableListOf<String>()
        lines += "<gray>Time:</gray> <white>${TS_FMT.format(e.timestamp)}</white>"
        lines += "<gray>Actor:</gray> <white>${mmSafe(e.actorName)}</white>"
        if (e.actorUuid != null) lines += "<gray>Actor UUID:</gray> <white>${e.actorUuid}</white>"
        if (e.term != null) lines += "<gray>Term:</gray> <white>#${e.term + 1}</white>"
        if (!e.target.isNullOrBlank()) lines += "<gray>Target:</gray> <white>${mmSafe(e.target)}</white>"

        if (e.details.isNotEmpty()) {
            lines += "<gray>Details:</gray>"
            for ((k, v) in e.details) {
                lines += "<dark_gray>-</dark_gray> <white>${mmSafe(k)}</white><dark_gray>:</dark_gray> <gray>${mmSafe(v)}</gray>"
            }
        }

        return lines
    }

    private companion object {
        private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}

