package mayorSystem.npc

import mayorSystem.MayorPlugin
import mayorSystem.npc.provider.MayorNpcProvider
import mayorSystem.npc.provider.MayorNpcProviderFactory
import mayorSystem.ui.menus.MayorProfileMenu
import mayorSystem.ui.menus.MainMenu
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.inventory.EquipmentSlot
import java.time.Instant
import java.util.UUID

/**
 * Mayor NPC orchestration:
 * - persists location + enabled flag in config
 * - selects an NPC provider (Citizens/FancyNpcs) on startup
 * - keeps the NPC updated when the mayor changes
 */
class MayorNpcService(private val plugin: MayorPlugin) : Listener {

    private var provider: MayorNpcProvider? = null
    private var lastMayorUuid: UUID? = null

    private var ticketWorld: String? = null
    private var ticketChunkX: Int? = null
    private var ticketChunkZ: Int? = null

    private var startupRestoreTaskId: Int = -1
    private val recentClicks: MutableMap<ClickKey, Long> = java.util.concurrent.ConcurrentHashMap()

    fun onEnable() {
        ensureNpcDefaults()
        reloadProvider()

        // Ensure the NPC chunk is loaded (and optionally kept loaded) before we try to restore.
        ensureNpcChunkLoadedAndTicket()

        // Restore if enabled
        provider?.restoreFromConfig()

        // One-shot update: we only refresh when the mayor actually changes or on reload/spawn.
        forceUpdateMayor()

        scheduleStartupRestore()
    }

    fun onReload() {
        ensureNpcDefaults()
        reloadProvider()
        ensureNpcChunkLoadedAndTicket()
        provider?.restoreFromConfig()
        forceUpdateMayor()
        scheduleStartupRestore()
    }

    fun spawnHere(actor: Player) {
        val p = provider
        if (p == null || p.id == "disabled") {
            actor.sendMessage(Component.text(
                "No supported NPC plugin found. Install Citizens or FancyNpcs first (then retry /mayor admin npc spawn).",
                NamedTextColor.RED
            ))
            return
        }

        val loc = actor.location
        persistLocation(loc)
        plugin.config.set("npc.mayor.creator_uuid", actor.uniqueId.toString())
        plugin.config.set("npc.mayor.enabled", true)
        plugin.saveConfig()

        ensureNpcChunkLoadedAndTicket(loc)

        p.spawnOrMove(loc, actorName = actor.name)
        forceUpdateMayor()

        actor.sendMessage(Component.text("Mayor NPC spawned using '${provider?.id}'.", NamedTextColor.GREEN))
    }

    fun remove(actor: Player) {
        provider?.remove()
        removeChunkTicket()
        plugin.config.set("npc.mayor.enabled", false)
        plugin.saveConfig()
        actor.sendMessage(Component.text("Mayor NPC removed.", NamedTextColor.YELLOW))
    }

    fun forceUpdate(actor: Player) {
        forceUpdateMayor()
        actor.sendMessage(Component.text("Mayor NPC update requested.", NamedTextColor.GRAY))
    }

    fun forceUpdateMayor() {
        lastMayorUuid = null
        tick()
    }

    /**
     * Force an update using a specific term index instead of relying on schedule compute().
     *
     * Why this exists:
     * - Some admin actions finalize an election and only shift the term schedule *after* selecting the winner.
     * - If we call [forceUpdateMayor] during that window, compute() can still return the previous term,
     *   which makes the NPC show the *old* mayor until the next tick / manual refresh.
     */
    fun forceUpdateMayorForTerm(termIndex: Int) {
        val enabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        if (!enabled) return

        // Same safety as tick(): ensure chunk is loaded before touching the provider.
        ensureNpcChunkLoadedAndTicket()

        if (termIndex < 0) {
            provider?.updateMayor(null)
            lastMayorUuid = null
            return
        }

        val mayorUuid = plugin.store.winner(termIndex)
        if (mayorUuid == null) {
            provider?.updateMayor(null)
            lastMayorUuid = null
            return
        }

        val offline = Bukkit.getOfflinePlayer(mayorUuid)
        val lastKnownName = plugin.store.winnerName(termIndex)
        val display = displayNameFor(offline, lastKnownName)
        val displayPlain = plainNameFor(offline, lastKnownName)

        val identity = MayorNpcIdentity(
            uuid = mayorUuid,
            lastKnownName = lastKnownName,
            displayName = display,
            displayNamePlain = displayPlain,
            titlePlain = "Mayor"
        )
        provider?.updateMayor(identity)
        lastMayorUuid = mayorUuid
    }

    fun openMayorCard(player: Player) {
        val now = Instant.now()
        val (currentTerm, _) = plugin.termService.compute(now)
        if (currentTerm < 0) {
            player.sendMessage(Component.text("No active term yet.", NamedTextColor.GRAY))
            return
        }
        val mayorUuid = plugin.store.winner(currentTerm)
        if (mayorUuid == null) {
            player.sendMessage(Component.text("No mayor has been elected yet.", NamedTextColor.GRAY))
            return
        }
        val mayorName = plugin.store.winnerName(currentTerm) ?: Bukkit.getOfflinePlayer(mayorUuid).name
        val menu = MayorProfileMenu(
            plugin = plugin,
            term = currentTerm,
            mayor = mayorUuid,
            mayorName = mayorName,
            backTo = { MainMenu(plugin) }
        )
        plugin.gui.open(player, menu)
    }

    // ---------------------------------------------------------------------
    // Click handling (ArmorStand + Citizens)
    // FancyNpcs uses its own onClick consumer (packet NPC; no Bukkit entity).
    // ---------------------------------------------------------------------

    @EventHandler
    fun onInteractAtEntity(e: PlayerInteractAtEntityEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        handleEntityClick(e.player, e.rightClicked)?.let { handled ->
            if (handled) e.isCancelled = true
        }
    }

    @EventHandler
    fun onInteractEntity(e: PlayerInteractEntityEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        handleEntityClick(e.player, e.rightClicked)?.let { handled ->
            if (handled) e.isCancelled = true
        }
    }

    @EventHandler
    fun onDamage(e: EntityDamageByEntityEvent) {
        val p = provider ?: return
        val entity = e.entity
        if (p.isMayorNpc(entity)) {
            e.isCancelled = true
        }
    }

    @EventHandler
    fun onPluginEnable(e: PluginEnableEvent) {
        val name = e.plugin.name.lowercase()
        if (name != "fancynpcs" && name != "fancynpc" && name != "citizens") return

        // If we started before the dependency, re-select and restore now.
        val current = provider
        if (current == null || current.id == "disabled") {
            reloadProvider()
            ensureNpcChunkLoadedAndTicket()
            provider?.restoreFromConfig()
            forceUpdateMayor()
            scheduleStartupRestore()
        }
    }

    private fun handleEntityClick(player: Player, clicked: Entity): Boolean? {
        val p = provider ?: return null
        if (!p.isMayorNpc(clicked)) return false
        if (!shouldProcessClick(player.uniqueId, clicked.uniqueId)) return true
        openMayorCard(player)
        return true
    }

    private fun shouldProcessClick(playerId: UUID, entityId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val key = ClickKey(playerId, entityId)
        val last = recentClicks[key]
        if (last != null && now - last < CLICK_DEDUP_MS) return false
        recentClicks[key] = now
        return true
    }

    // ---------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------

    private fun reloadProvider() {
        provider?.onDisable()

        val selected = MayorNpcProviderFactory.select(plugin)
        selected.onEnable(plugin)
        provider = selected

        plugin.config.set("npc.mayor.backend", selected.id)
        plugin.saveConfig()

        plugin.logger.info("[MayorNPC] Using provider: ${selected.id}")
    }

    private fun scheduleStartupRestore() {
        if (provider?.id != "fancynpcs") return
        if (!plugin.config.getBoolean("npc.mayor.enabled", false)) return
        if (startupRestoreTaskId != -1) return

        var attempts = 0
        startupRestoreTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (!plugin.isEnabled) {
                cancelStartupRestore()
                return@Runnable
            }
            attempts++
            provider?.restoreFromConfig()
            forceUpdateMayor()
            if (attempts >= 5) {
                cancelStartupRestore()
            }
        }, 60L, 60L)
    }

    private fun cancelStartupRestore() {
        if (startupRestoreTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(startupRestoreTaskId) }
            startupRestoreTaskId = -1
        }
    }

    private fun tick() {
        val enabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        if (!enabled) return

        // If the NPC is configured, ensure its chunk is available before attempting an update.
        // This prevents accidental duplicate spawns when the chunk isn't loaded.
        ensureNpcChunkLoadedAndTicket()

        val now = Instant.now()
        val (currentTerm, _) = plugin.termService.compute(now)
        if (currentTerm < 0) {
            provider?.updateMayor(null)
            return
        }

        val mayorUuid = plugin.store.winner(currentTerm)
        if (mayorUuid == null) {
            provider?.updateMayor(null)
            lastMayorUuid = null
            return
        }

        if (mayorUuid == lastMayorUuid) {
            // Still update occasionally: prefixes / display names can change.
        }

        val offline = Bukkit.getOfflinePlayer(mayorUuid)
        val lastKnownName = plugin.store.winnerName(currentTerm)
        val display = displayNameFor(offline, lastKnownName)
        val displayPlain = plainNameFor(offline, lastKnownName)

        val identity = MayorNpcIdentity(
            uuid = mayorUuid,
            lastKnownName = lastKnownName,
            displayName = display,
            displayNamePlain = displayPlain,
            titlePlain = "Mayor"
        )
        provider?.updateMayor(identity)
        lastMayorUuid = mayorUuid
    }

    private fun ensureNpcDefaults() {
        // Defaults are written here so servers upgrading keep a valid config without manual edits.
        val key = "npc.mayor.default_skin"
        val existing = plugin.config.getString(key)
        if (existing.isNullOrBlank()) {
            plugin.config.set(key, "Steve")
            plugin.saveConfig()
        }
    }

    private fun persistLocation(loc: Location) {
        val world = loc.world ?: return
        plugin.config.set("npc.mayor.world", world.name)
        plugin.config.set("npc.mayor.x", loc.x)
        plugin.config.set("npc.mayor.y", loc.y)
        plugin.config.set("npc.mayor.z", loc.z)
        plugin.config.set("npc.mayor.yaw", loc.yaw.toDouble())
        plugin.config.set("npc.mayor.pitch", loc.pitch.toDouble())
    }

    private fun ensureNpcChunkLoadedAndTicket(locOverride: Location? = null) {
        val enabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        if (!enabled) return

        val loc = locOverride ?: readLocationFromConfig() ?: return
        val world = loc.world ?: return

        // Load chunk so entities/NPCs are actually accessible.
        val chunk = world.getChunkAt(loc)

        // Optionally keep chunk loaded so the Mayor NPC is always present.
        val keepLoaded = plugin.config.getBoolean("npc.mayor.keep_chunk_loaded", true)
        if (keepLoaded) {
            addPluginChunkTicket(chunk)
            ticketWorld = world.name
            ticketChunkX = chunk.x
            ticketChunkZ = chunk.z
        }
    }

    private fun removeChunkTicket() {
        val worldName = ticketWorld ?: return
        val cx = ticketChunkX ?: return
        val cz = ticketChunkZ ?: return
        val world = Bukkit.getWorld(worldName) ?: return
        val chunk = world.getChunkAt(cx, cz)
        runCatching {
            val m = chunk.javaClass.methods.firstOrNull { it.name == "removePluginChunkTicket" && it.parameterCount == 1 }
            m?.invoke(chunk, plugin)
        }
        ticketWorld = null
        ticketChunkX = null
        ticketChunkZ = null
    }

    private fun addPluginChunkTicket(chunk: Any) {
        // Paper provides Chunk#addPluginChunkTicket(Plugin). We use reflection for compatibility.
        runCatching {
            val m = chunk.javaClass.methods.firstOrNull { it.name == "addPluginChunkTicket" && it.parameterCount == 1 }
            m?.invoke(chunk, plugin)
        }
    }

    private fun readLocationFromConfig(): Location? {
        val worldName = plugin.config.getString("npc.mayor.world") ?: return null
        val world = Bukkit.getWorld(worldName) ?: return null
        val x = plugin.config.getDouble("npc.mayor.x")
        val y = plugin.config.getDouble("npc.mayor.y")
        val z = plugin.config.getDouble("npc.mayor.z")
        val yaw = plugin.config.getDouble("npc.mayor.yaw").toFloat()
        val pitch = plugin.config.getDouble("npc.mayor.pitch").toFloat()
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun displayNameFor(offline: OfflinePlayer, lastKnownName: String?): Component {
        // If online, displayName() often already includes rank prefixes (LuckPerms/Vault chat formatting).
        val online = Bukkit.getPlayer(offline.uniqueId)
        if (online != null) return online.displayName()

        val baseName = lastKnownName ?: offline.name ?: "Unknown"
        val prefix = vaultPrefix(Bukkit.getWorlds().firstOrNull(), offline)?.takeIf { it.isNotBlank() } ?: ""
        val combined = (prefix + baseName).trim()
        return Component.text(combined, NamedTextColor.YELLOW)
    }

    private fun plainNameFor(offline: OfflinePlayer, lastKnownName: String?): String {
        val online = Bukkit.getPlayer(offline.uniqueId)
        if (online != null) return PlainTextComponentSerializer.plainText().serialize(online.displayName())


        val baseName = lastKnownName ?: offline.name ?: "Unknown"
        val prefix = vaultPrefix(Bukkit.getWorlds().firstOrNull(), offline)?.takeIf { it.isNotBlank() } ?: ""
        return (prefix + baseName).trim()
    }

    private fun vaultPrefix(world: World?, offline: OfflinePlayer): String? {
        // Optional: if Vault + Chat provider is present, use prefix for offline players.
        return runCatching {
            val vault = plugin.server.pluginManager.getPlugin("Vault") ?: return@runCatching null
            if (!vault.isEnabled) return@runCatching null

            val chatClass = Class.forName("net.milkbowl.vault.chat.Chat")
            @Suppress("UNCHECKED_CAST")
            val reg = plugin.server.servicesManager.getRegistration(chatClass as Class<Any>) ?: return@runCatching null
	            // RegisteredServiceProvider#provider is non-null in Bukkit's API.
	            val provider = reg.provider

            val m = provider.javaClass.methods.firstOrNull { it.name == "getPlayerPrefix" && it.parameterCount == 2 }
                ?: return@runCatching null

            val prefix = m.invoke(provider, world, offline) as? String ?: return@runCatching null
            stripColorCodes(prefix)
        }.getOrNull()
    }

    private fun stripColorCodes(s: String): String {
        // Remove legacy color codes: &a, §a, etc.
        return s
            .replace(Regex("(?i)[&§][0-9A-FK-OR]"), "")
            .trim()
    }
    private companion object {
        private const val CLICK_DEDUP_MS: Long = 150L
    }

    private data class ClickKey(val playerId: UUID, val entityId: UUID)
}
