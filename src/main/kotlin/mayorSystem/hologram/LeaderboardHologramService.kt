package mayorSystem.hologram

import mayorSystem.MayorPlugin
import mayorSystem.data.CandidateEntry
import mayorSystem.showcase.ShowcaseMode
import mayorSystem.elections.TermTimes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LeaderboardHologramService(private val plugin: MayorPlugin) : Listener {

    private val hook = DecentHologramsHook(plugin)
    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()
    private val miniTagRegex = Regex("</?[a-zA-Z0-9_:#-]+[^>]*>")
    private var active: Boolean = false
    private var updateTaskId: Int = -1

    fun onEnable() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (shouldBeActive()) {
            setActive(true)
        }
    }

    fun onDisable() {
        setActive(false)
    }

    fun onReload() {
        if (!hook.isAvailable()) {
            setActive(false)
            return
        }
        if (shouldBeActive()) {
            setActive(true)
        } else {
            setActive(false)
        }
    }

    fun isAvailable(): Boolean = hook.isAvailable()

    fun setActive(enabled: Boolean) {
        if (!hook.isAvailable()) {
            active = false
            stopUpdater()
            return
        }

        if (enabled) {
            active = true
            refreshNow()
            startUpdater()
        } else {
            active = false
            stopUpdater()
            removeHologram()
        }
    }

    fun spawnHere(actor: Player) {
        if (!hook.isAvailable()) {
            plugin.messages.msg(actor, "admin.hologram.not_available")
            return
        }

        val mode = plugin.showcase.mode()
        if (mode == ShowcaseMode.SWITCHING) {
            plugin.messages.msg(actor, "admin.hologram.switching_hint")
        }

        val baseLoc = actor.location
        val loc = baseLoc.clone().add(0.0, actor.height + 1.0, 0.0)
        if (mode == ShowcaseMode.SWITCHING) {
            persistNpcLocation(baseLoc)
        }
        persistLocation(loc)
        plugin.config.set("hologram.leaderboard.enabled", true)
        plugin.saveConfig()

        if (plugin.hasShowcase()) {
            plugin.showcase.sync()
        } else {
            setActive(true)
        }
        plugin.messages.msg(actor, "admin.hologram.spawned")
    }

    fun remove(actor: Player) {
        setActive(false)
        plugin.config.set("hologram.leaderboard.enabled", false)
        plugin.saveConfig()
        if (plugin.hasShowcase()) {
            plugin.showcase.sync()
        }
        plugin.messages.msg(actor, "admin.hologram.removed")
    }

    fun forceUpdate(actor: Player) {
        refreshNow()
        plugin.messages.msg(actor, "admin.hologram.updated")
    }

    fun refreshIfActive() {
        if (!active) return
        if (Bukkit.isPrimaryThread()) {
            refreshNow()
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable { refreshNow() })
    }

    private fun shouldBeActive(): Boolean {
        if (!plugin.config.getBoolean("hologram.leaderboard.enabled", false)) return false
        return true
    }

    private fun refreshNow() {
        if (!active) return
        if (!hook.isAvailable()) return
        if (!plugin.isReady()) return

        val name = hologramName()
        val loc = resolveLocation() ?: return
        val lines = buildLines()

        val existing = hook.get(name)
        val hologram = existing ?: hook.create(name, loc, false, lines)
        if (hologram != null) {
            hook.move(name, loc)
            hook.setLines(hologram, lines)
            hook.update(name)
        }
    }

    private fun removeHologram() {
        val name = hologramName()
        hook.remove(name)
    }

    private fun hologramName(): String {
        val raw = plugin.config.getString("hologram.leaderboard.name")?.trim()
        return if (raw.isNullOrBlank()) "mayorsystem_leaderboard" else raw
    }

    private fun resolveLocation(): Location? {
        val mode = plugin.showcase.mode()
        return if (mode == ShowcaseMode.SWITCHING) {
            val base = readNpcLocation() ?: readHologramLocation() ?: return null
            val headOffset = 2.8
            val offset = plugin.config.getDouble("hologram.leaderboard.switching_y_offset", headOffset)
                .coerceAtLeast(headOffset)
            base.clone().add(0.0, offset, 0.0)
        } else {
            readHologramLocation()
        }
    }

    private fun buildLines(): List<String> {
        val openTemplate = plugin.config.getStringList("hologram.leaderboard.lines")
            .ifEmpty { defaultLines() }
        val closedTemplate = plugin.config.getStringList("hologram.leaderboard.closed_lines")
            .ifEmpty { defaultClosedLines() }

        val now = Instant.now()
        val (_, electionTerm) = plugin.termService.computeCached(now)
        val term = if (electionTerm < 0) -1 else electionTerm
        val isOpen = term >= 0 && plugin.termService.isElectionOpen(now, term)
        val times = if (term >= 0) plugin.termService.timesFor(term) else null
        val maxEntries = plugin.config.getInt("hologram.leaderboard.max_entries", 3).coerceAtLeast(1)
        val entries = if (isOpen && term >= 0) {
            plugin.store.topCandidates(term, maxEntries, includeRemoved = false)
        } else {
            emptyList()
        }
        val template = if (isOpen) openTemplate else closedTemplate

        return template.map { line ->
            val built = applyMayorPlaceholders(line, term, entries, maxEntries, times)
            formatLine(built)
        }
    }

    private fun applyMayorPlaceholders(
        raw: String,
        term: Int,
        entries: List<Pair<CandidateEntry, Int>>,
        maxEntries: Int,
        times: TermTimes?
    ): String {
        var out = raw
        val termHuman = if (term >= 0) (term + 1).toString() else ""
        out = out.replace("%mayorsystem_leaderboard_term%", termHuman)
        out = out.replace("%mayorsystem_election_open%", formatInstant(times?.electionOpen))
        out = out.replace("%mayorsystem_election_close%", formatInstant(times?.electionClose))
        out = out.replace("%mayorsystem_term_start%", formatInstant(times?.termStart))
        out = out.replace("%mayorsystem_term_end%", formatInstant(times?.termEnd))

        for (i in 1..maxEntries) {
            val entry = entries.getOrNull(i - 1)
            val name = entry?.first?.lastKnownName ?: ""
            val votes = entry?.second?.toString() ?: ""
            val uuid = entry?.first?.uuid?.toString() ?: ""
            out = out.replace("%mayorsystem_leaderboard_${i}_name%", name)
            out = out.replace("%mayorsystem_leaderboard_${i}_votes%", votes)
            out = out.replace("%mayorsystem_leaderboard_${i}_uuid%", uuid)
        }

        return out
    }

    private fun formatLine(raw: String): String {
        if (raw.isBlank()) return " "
        val trimmed = raw.trimEnd()
        val component = if (miniTagRegex.containsMatchIn(trimmed)) {
            runCatching { mini.deserialize(trimmed) }.getOrElse { Component.text(trimmed) }
        } else {
            Component.text(trimmed)
        }
        val legacyText = legacy.serialize(component)
        return if (legacyText.isBlank()) " " else legacyText
    }

    private fun defaultLines(): List<String> = listOf(
        "<gold><bold>Mayor Election Leaderboard</bold></gold>",
        "<gray>Term #%mayorsystem_leaderboard_term%</gray>",
        "<gray>Voting closes:</gray> <white>%mayorsystem_election_close%</white>",
        "",
        "<yellow>#1</yellow> %mayorsystem_leaderboard_1_name% <dark_gray>-</dark_gray> <gold>%mayorsystem_leaderboard_1_votes%</gold>",
        "<yellow>#2</yellow> %mayorsystem_leaderboard_2_name% <dark_gray>-</dark_gray> <gold>%mayorsystem_leaderboard_2_votes%</gold>",
        "<yellow>#3</yellow> %mayorsystem_leaderboard_3_name% <dark_gray>-</dark_gray> <gold>%mayorsystem_leaderboard_3_votes%</gold>"
    )

    private fun defaultClosedLines(): List<String> = listOf(
        "<gold><bold>Mayor Elections</bold></gold>",
        "<red>Voting is closed</red>",
        "",
        "<gray>Next term:</gray> <white>#%mayorsystem_leaderboard_term%</white>",
        "<gray>Next election opens:</gray> <white>%mayorsystem_election_open%</white>",
        "<gray>Come back during the vote window.</gray>"
    )

    private fun formatInstant(instant: Instant?): String {
        if (instant == null) return ""
        val zdt = instant.atZone(ZoneId.systemDefault())
        return DATE_FMT.format(zdt)
    }

    private fun startUpdater() {
        if (updateTaskId != -1) return
        val interval = plugin.config.getLong("hologram.leaderboard.update_interval_ticks", 100L).coerceAtLeast(0L)
        if (interval <= 0L) return
        updateTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable {
            refreshNow()
        }, interval, interval)
    }

    private fun stopUpdater() {
        if (updateTaskId == -1) return
        runCatching { Bukkit.getScheduler().cancelTask(updateTaskId) }
        updateTaskId = -1
    }

    private fun persistLocation(loc: Location) {
        val world = loc.world ?: return
        plugin.config.set("hologram.leaderboard.world", world.name)
        plugin.config.set("hologram.leaderboard.x", loc.x)
        plugin.config.set("hologram.leaderboard.y", loc.y)
        plugin.config.set("hologram.leaderboard.z", loc.z)
        plugin.config.set("hologram.leaderboard.yaw", loc.yaw.toDouble())
        plugin.config.set("hologram.leaderboard.pitch", loc.pitch.toDouble())
    }

    private fun persistNpcLocation(loc: Location) {
        val world = loc.world ?: return
        plugin.config.set("npc.mayor.world", world.name)
        plugin.config.set("npc.mayor.x", loc.x)
        plugin.config.set("npc.mayor.y", loc.y)
        plugin.config.set("npc.mayor.z", loc.z)
        plugin.config.set("npc.mayor.yaw", loc.yaw.toDouble())
        plugin.config.set("npc.mayor.pitch", loc.pitch.toDouble())
    }

    private fun readNpcLocation(): Location? {
        val worldName = plugin.config.getString("npc.mayor.world") ?: return null
        val world = Bukkit.getWorld(worldName) ?: return null
        val x = plugin.config.getDouble("npc.mayor.x")
        val y = plugin.config.getDouble("npc.mayor.y")
        val z = plugin.config.getDouble("npc.mayor.z")
        val yaw = plugin.config.getDouble("npc.mayor.yaw").toFloat()
        val pitch = plugin.config.getDouble("npc.mayor.pitch").toFloat()
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun readHologramLocation(): Location? {
        val worldName = plugin.config.getString("hologram.leaderboard.world") ?: return null
        val world = Bukkit.getWorld(worldName) ?: return null
        val x = plugin.config.getDouble("hologram.leaderboard.x")
        val y = plugin.config.getDouble("hologram.leaderboard.y")
        val z = plugin.config.getDouble("hologram.leaderboard.z")
        val yaw = plugin.config.getDouble("hologram.leaderboard.yaw").toFloat()
        val pitch = plugin.config.getDouble("hologram.leaderboard.pitch").toFloat()
        return Location(world, x, y, z, yaw, pitch)
    }

    @EventHandler
    fun onPluginEnable(e: PluginEnableEvent) {
        val name = e.plugin.name.lowercase()
        if (name != "decentholograms") return
        if (plugin.hasShowcase()) {
            plugin.showcase.sync()
        }
    }

    private companion object {
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    }
}
