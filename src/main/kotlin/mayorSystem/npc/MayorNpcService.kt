package mayorSystem.npc

import mayorSystem.MayorPlugin
import mayorSystem.npc.provider.MayorNpcProvider
import mayorSystem.npc.provider.MayorNpcProviderFactory
import mayorSystem.ui.menus.MayorProfileMenu
import mayorSystem.ui.menus.MainMenu
import mayorSystem.util.loggedTask
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
import java.lang.reflect.Method
import mayorSystem.config.SystemGateOption
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
    private var lastIdentityRefreshKey: String? = null
    private var showcaseActive: Boolean = true

    private var ticketWorld: String? = null
    private var ticketChunkX: Int? = null
    private var ticketChunkZ: Int? = null

    private var startupRestoreTaskId: Int = -1
    private val recentClicks: MutableMap<ClickKey, Long> = java.util.concurrent.ConcurrentHashMap()
    private val pendingLuckPermsLoads: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val resolvedProfileNames: MutableMap<UUID, String> = java.util.concurrent.ConcurrentHashMap()
    private val pendingProfileLoads: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val pendingSkinLoads: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private var cachedChatProvider: Any? = null
    private var cachedChatMethod: Method? = null
    private var cachedChatWorldArg: ChatWorldArg? = null
    private var cachedChatPlayerArg: ChatPlayerArg? = null
    private var cachedChatExpiresAt: Long = 0L
    private var cachedChatRetryAt: Long = 0L
    private val chatCacheTtlMs: Long = 5 * 60 * 1000L
    private val chatRetryTtlMs: Long = 30_000L
    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()
    private val legacyAmp = LegacyComponentSerializer.legacyAmpersand()
    private val plain = PlainTextComponentSerializer.plainText()

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

    fun onDisable() {
        cancelStartupRestore()
        provider?.onDisable()
        removeChunkTicket()
        pendingLuckPermsLoads.clear()
        pendingProfileLoads.clear()
        pendingSkinLoads.clear()
        resolvedProfileNames.clear()
    }

    fun setActive(active: Boolean) {
        if (showcaseActive == active) {
            if (active) {
                tick()
            }
            return
        }

        showcaseActive = active
        if (!active) {
            provider?.remove()
            removeChunkTicket()
            lastMayorUuid = null
            lastIdentityRefreshKey = null
            return
        }

        if (!plugin.config.getBoolean("npc.mayor.enabled", false)) return

        ensureNpcChunkLoadedAndTicket()
        provider?.restoreFromConfig()
        readLocationFromConfig()?.let { provider?.spawnOrMove(it) }
        forceUpdateMayor()
    }

    fun spawnHere(actor: Player) {
        val p = provider
        if (p == null || p.id == "disabled") {
            plugin.messages.msg(actor, "admin.npc.not_available")
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

        plugin.messages.msg(actor, "admin.npc.spawned", mapOf("provider" to (provider?.id ?: "unknown")))
        if (plugin.hasShowcase()) {
            plugin.showcase.sync()
        }
    }

    fun remove(actor: Player) {
        provider?.remove()
        removeChunkTicket()
        plugin.config.set("npc.mayor.enabled", false)
        plugin.saveConfig()
        plugin.messages.msg(actor, "admin.npc.removed")
        if (plugin.hasShowcase()) {
            plugin.showcase.sync()
        }
    }

    fun forceUpdate(actor: Player) {
        forceUpdateMayor()
        plugin.messages.msg(actor, "admin.npc.updated")
    }

    fun forceUpdateMayor() {
        if (!showcaseActive) return
        lastMayorUuid = null
        lastIdentityRefreshKey = null
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
        if (!plugin.isReady()) return
        if (!showcaseActive) return
        val enabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        if (!enabled) return

        // Same safety as tick(): ensure chunk is loaded before touching the provider.
        ensureNpcChunkLoadedAndTicket()

        if (termIndex < 0) {
            provider?.updateMayor(null)
            lastMayorUuid = null
            lastIdentityRefreshKey = NO_MAYOR_REFRESH_KEY
            return
        }

        val mayorUuid = plugin.store.winner(termIndex)
        if (mayorUuid == null) {
            provider?.updateMayor(null)
            lastMayorUuid = null
            lastIdentityRefreshKey = NO_MAYOR_REFRESH_KEY
            return
        }

        val offline = Bukkit.getOfflinePlayer(mayorUuid)
        val storedName = plugin.store.winnerName(termIndex)
        val profileName = resolveProfileName(offline, storedName)
        queueSkinRefresh(mayorUuid, profileName)
        val resolvedName = resolveMayorName(offline, profileName)
        val cachedSkin = plugin.skins.get(mayorUuid)

        val identity = MayorNpcIdentity(
            uuid = mayorUuid,
            lastKnownName = profileName,
            isBedrockPlayer = plugin.skins.isLikelyBedrockUuid(mayorUuid),
            skinTextureValue = cachedSkin?.value,
            skinTextureSignature = cachedSkin?.signature,
            skinTextureUrl = cachedSkin?.skinUrl,
            displayName = resolvedName.component,
            displayNamePlain = resolvedName.plain,
            titleLegacy = npcTitleLegacy(),
            titleMini = npcTitleMini()
        )
        val refreshKey = identityRefreshKey(identity)
        if (mayorUuid == lastMayorUuid && refreshKey == lastIdentityRefreshKey) {
            return
        }
        provider?.updateMayor(identity)
        lastMayorUuid = mayorUuid
        lastIdentityRefreshKey = refreshKey
    }

    fun openMayorCard(player: Player) {
        if (!player.hasPermission(mayorSystem.security.Perms.USE)) {
            plugin.messages.msg(player, "errors.no_permission")
            return
        }
        val now = Instant.now()
        val (currentTerm, _) = plugin.termService.computeCached(now)
        if (currentTerm < 0) {
            plugin.messages.msg(player, "npc.no_active_term")
            return
        }
        val mayorUuid = plugin.store.winner(currentTerm)
        if (mayorUuid == null) {
            plugin.messages.msg(player, "npc.no_mayor_elected")
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
        startupRestoreTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, plugin.loggedTask("mayor npc startup restore") {
            if (!plugin.isEnabled) {
                cancelStartupRestore()
                return@loggedTask
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
        if (!plugin.isReady()) return
        if (!showcaseActive) return
        val enabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        if (!enabled) return

        if (plugin.settings.isBlocked(SystemGateOption.MAYOR_NPC)) {
            if (plugin.settings.isDisabled(SystemGateOption.MAYOR_NPC)) {
                provider?.updateMayor(null)
                lastMayorUuid = null
            }
            return
        }

        // If the NPC is configured, ensure its chunk is available before attempting an update.
        // This prevents accidental duplicate spawns when the chunk isn't loaded.
        ensureNpcChunkLoadedAndTicket()

        val now = Instant.now()
        val (currentTerm, _) = plugin.termService.computeCached(now)
        if (currentTerm < 0) {
            if (lastIdentityRefreshKey == NO_MAYOR_REFRESH_KEY) return
            provider?.updateMayor(null)
            lastMayorUuid = null
            lastIdentityRefreshKey = NO_MAYOR_REFRESH_KEY
            return
        }

        val mayorUuid = plugin.store.winner(currentTerm)
        if (mayorUuid == null) {
            if (lastIdentityRefreshKey == NO_MAYOR_REFRESH_KEY) return
            provider?.updateMayor(null)
            lastMayorUuid = null
            lastIdentityRefreshKey = NO_MAYOR_REFRESH_KEY
            return
        }

        if (mayorUuid == lastMayorUuid) {
            // Still update occasionally: prefixes / display names can change.
        }

        val offline = Bukkit.getOfflinePlayer(mayorUuid)
        val storedName = plugin.store.winnerName(currentTerm)
        val profileName = resolveProfileName(offline, storedName)
        queueSkinRefresh(mayorUuid, profileName)
        val resolvedName = resolveMayorName(offline, profileName)
        val cachedSkin = plugin.skins.get(mayorUuid)

        val identity = MayorNpcIdentity(
            uuid = mayorUuid,
            lastKnownName = profileName,
            isBedrockPlayer = plugin.skins.isLikelyBedrockUuid(mayorUuid),
            skinTextureValue = cachedSkin?.value,
            skinTextureSignature = cachedSkin?.signature,
            skinTextureUrl = cachedSkin?.skinUrl,
            displayName = resolvedName.component,
            displayNamePlain = resolvedName.plain,
            titleLegacy = npcTitleLegacy(),
            titleMini = npcTitleMini()
        )
        val refreshKey = identityRefreshKey(identity)
        if (mayorUuid == lastMayorUuid && refreshKey == lastIdentityRefreshKey) {
            return
        }
        provider?.updateMayor(identity)
        lastMayorUuid = mayorUuid
        lastIdentityRefreshKey = refreshKey
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

    private fun npcTitleMini(): String {
        val raw = plugin.messages.get("npc.title")?.trim()
        return if (raw.isNullOrBlank()) "<gold>${plugin.settings.titleName}</gold>" else raw
    }

    private fun npcTitleLegacy(): String {
        val miniRaw = npcTitleMini()
        val component = runCatching { mini.deserialize(miniRaw) }.getOrElse { Component.text(plugin.settings.titleName) }
        val legacyText = legacy.serialize(component)
        return if (legacyText.isBlank()) plugin.settings.titleName else legacyText
    }

    private fun resolveMayorName(offline: OfflinePlayer, resolvedProfileName: String?): ResolvedMayorName {
        val baseName = resolvedProfileName ?: "Unknown"

        luckPermsName(offline, baseName)?.let { resolved ->
            return withTitlePrefix(resolved)
        }

        val online = Bukkit.getPlayer(offline.uniqueId)
        if (online != null) {
            val onlineDisplay = online.displayName()
            return withTitlePrefix(
                ResolvedMayorName(
                    component = onlineDisplay,
                    plain = plain.serialize(onlineDisplay).trim()
                )
            )
        }

        val vaultPrefix = vaultPrefix(Bukkit.getWorlds().firstOrNull(), offline)?.takeIf { it.isNotBlank() } ?: ""
        val parts = mutableListOf<Component>()
        if (vaultPrefix.isNotBlank()) {
            parts += Component.text(vaultPrefix)
        }
        parts += Component.text(baseName, NamedTextColor.YELLOW)
        return withTitlePrefix(
            ResolvedMayorName(
                component = joinWithSpaces(parts),
                plain = listOf(vaultPrefix, baseName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
            )
        )
    }

    private fun resolveProfileName(offline: OfflinePlayer, storedName: String?): String? {
        val normalizedStored = storedName?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedStored != null) {
            resolvedProfileNames[offline.uniqueId] = normalizedStored
            return normalizedStored
        }

        val onlineName = Bukkit.getPlayer(offline.uniqueId)?.name?.trim()?.takeIf { it.isNotBlank() }
        if (onlineName != null) {
            resolvedProfileNames[offline.uniqueId] = onlineName
            return onlineName
        }

        val offlineName = offline.name?.trim()?.takeIf { it.isNotBlank() }
        if (offlineName != null) {
            resolvedProfileNames[offline.uniqueId] = offlineName
            return offlineName
        }

        val cached = resolvedProfileNames[offline.uniqueId]?.trim()?.takeIf { it.isNotBlank() }
        if (cached != null) {
            return cached
        }

        if (plugin.skins.isLikelyBedrockUuid(offline.uniqueId)) {
            return null
        }

        queueProfileLookup(offline.uniqueId)
        return null
    }

    private fun queueSkinRefresh(uuid: UUID, lastKnownName: String?) {
        val cached = plugin.skins.get(uuid)
        if (cached != null && plugin.skins.isFresh(cached)) {
            pendingSkinLoads.remove(uuid)
            return
        }
        if (!pendingSkinLoads.add(uuid)) return
        plugin.skins.request(uuid, lastKnownName, null, null)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingSkinLoads.remove(uuid)
        }, 20L * 10L)
    }

    private fun queueProfileLookup(uuid: UUID) {
        if (!pendingProfileLoads.add(uuid)) return

        val profile = runCatching { Bukkit.createProfile(uuid) }
            .getOrElse {
                pendingProfileLoads.remove(uuid)
                return
            }

        runCatching { profile.update() }
            .onFailure {
                pendingProfileLoads.remove(uuid)
            }
            .onSuccess { future ->
                future.whenComplete { updated, _ ->
                    pendingProfileLoads.remove(uuid)
                    val resolvedName = updated?.name?.trim()?.takeIf { it.isNotBlank() } ?: return@whenComplete
                    resolvedProfileNames[uuid] = resolvedName
                    if (!plugin.isEnabled) return@whenComplete
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (!plugin.isEnabled) return@Runnable
                        forceUpdateMayor()
                    })
                }
            }
    }

    private fun withTitlePrefix(name: ResolvedMayorName): ResolvedMayorName {
        val titlePrefixMini = plugin.settings.resolvedTitlePlayerPrefix().trim()
        if (titlePrefixMini.isBlank()) return name

        val prefixComponent = runCatching { mini.deserialize(titlePrefixMini) }
            .getOrElse { Component.text(plugin.settings.titleName, NamedTextColor.GOLD) }
        val prefixPlain = plain.serialize(prefixComponent).trim()
        return ResolvedMayorName(
            component = joinWithSpaces(listOf(prefixComponent, name.component)),
            plain = listOf(prefixPlain, name.plain)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
        )
    }

    private fun identityRefreshKey(identity: MayorNpcIdentity): String {
        val displayMini = mini.serialize(identity.displayName)
        return listOf(
            identity.uuid.toString(),
            identity.lastKnownName.orEmpty(),
            identity.isBedrockPlayer.toString(),
            identity.skinTextureValue?.hashCode()?.toString().orEmpty(),
            identity.skinTextureSignature?.hashCode()?.toString().orEmpty(),
            identity.skinTextureUrl.orEmpty(),
            identity.displayNamePlain,
            displayMini,
            identity.titleLegacy,
            identity.titleMini
        ).joinToString("|")
    }

    private fun joinWithSpaces(parts: List<Component>): Component {
        if (parts.isEmpty()) return Component.empty()
        var out = parts.first()
        for (i in 1 until parts.size) {
            out = out.append(Component.space()).append(parts[i])
        }
        return out
    }

    private fun vaultPrefix(world: World?, offline: OfflinePlayer): String? {
        // Optional: if Vault + Chat provider is present, use prefix for offline players.
        val now = System.currentTimeMillis()
        if (cachedChatProvider == null || cachedChatMethod == null || now >= cachedChatExpiresAt) {
            refreshVaultChat(now)
        }
        val provider = cachedChatProvider ?: return null
        val method = cachedChatMethod ?: return null
        val worldArg = cachedChatWorldArg ?: return null
        val playerArg = cachedChatPlayerArg ?: return null
        return runCatching {
            val arg0: Any? = when (worldArg) {
                ChatWorldArg.WORLD -> world
                ChatWorldArg.STRING -> world?.name
            }
            val arg1: Any? = when (playerArg) {
                ChatPlayerArg.OFFLINE -> offline
                ChatPlayerArg.PLAYER -> if (offline is Player) offline else return@runCatching null
            }
            val prefix = method.invoke(provider, arg0, arg1) as? String ?: return@runCatching null
            stripColorCodes(prefix)
        }.getOrNull()
    }

    private fun luckPermsName(offline: OfflinePlayer, baseName: String): ResolvedMayorName? {
        val lp = runCatching { LuckPermsProvider.get() }.getOrNull() ?: return null
        val user = lp.userManager.getUser(offline.uniqueId)
        if (user == null) {
            queueLuckPermsLoad(lp, offline.uniqueId)
            return null
        }

        val meta = resolveLuckPermsMeta(lp, user, Bukkit.getPlayer(offline.uniqueId)) ?: return null
        if (meta.prefix.isBlank() && meta.suffix.isBlank()) return null

        val parts = mutableListOf<Component>()
        if (meta.prefix.isNotBlank()) {
            parts += componentFromLuckPermsText(meta.prefix)
        }
        parts += Component.text(baseName, NamedTextColor.YELLOW)
        if (meta.suffix.isNotBlank()) {
            parts += componentFromLuckPermsText(meta.suffix)
        }
        return ResolvedMayorName(
            component = joinWithSpaces(parts),
            plain = listOf(meta.prefixPlain, baseName, meta.suffixPlain)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
        )
    }

    private fun queueLuckPermsLoad(lp: LuckPerms, uuid: UUID) {
        if (!pendingLuckPermsLoads.add(uuid)) return
        runCatching { lp.userManager.loadUser(uuid) }
            .onFailure { pendingLuckPermsLoads.remove(uuid) }
            .onSuccess { future ->
                future.whenComplete { _, _ ->
                    pendingLuckPermsLoads.remove(uuid)
                    if (!plugin.isEnabled) return@whenComplete
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (!plugin.isEnabled) return@Runnable
                        forceUpdateMayor()
                    })
                }
            }
    }

    private fun resolveLuckPermsMeta(lp: LuckPerms, user: Any, player: Player?): LuckPermsMetaSnapshot? {
        val cachedData = user.javaClass.methods.firstOrNull { it.name == "getCachedData" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(user) }.getOrNull() }
            ?: return null

        val meta = cachedData.javaClass.methods.firstOrNull { it.name == "getMetaData" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(cachedData) }.getOrNull() }
            ?: run {
                val queryOptions = resolveLuckPermsQueryOptions(lp, player) ?: return null
                cachedData.javaClass.methods.firstOrNull { it.name == "getMetaData" && it.parameterCount == 1 }
                    ?.let { runCatching { it.invoke(cachedData, queryOptions) }.getOrNull() }
            }
            ?: return null

        val prefix = meta.javaClass.methods.firstOrNull { it.name == "getPrefix" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(meta) as? String }.getOrNull() }
            ?.trim()
            .orEmpty()
        val suffix = meta.javaClass.methods.firstOrNull { it.name == "getSuffix" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(meta) as? String }.getOrNull() }
            ?.trim()
            .orEmpty()
        return LuckPermsMetaSnapshot(
            prefix = prefix,
            prefixPlain = plainLuckPermsText(prefix),
            suffix = suffix,
            suffixPlain = plainLuckPermsText(suffix)
        )
    }

    private fun resolveLuckPermsQueryOptions(lp: LuckPerms, player: Player?): Any? {
        val contextManager = lp.javaClass.methods.firstOrNull { it.name == "getContextManager" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(lp) }.getOrNull() }
            ?: return null

        if (player != null) {
            val playerOptions = contextManager.javaClass.methods
                .filter { it.name == "getQueryOptions" && it.parameterCount == 1 }
                .firstOrNull { it.parameterTypes[0].isAssignableFrom(player.javaClass) }
                ?.let { runCatching { it.invoke(contextManager, player) }.getOrNull() }
                ?.let(::unwrapOptional)
            if (playerOptions != null) return playerOptions
        }

        return contextManager.javaClass.methods.firstOrNull { it.name == "getStaticQueryOptions" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(contextManager) }.getOrNull() }
    }

    private fun componentFromLuckPermsText(raw: String): Component {
        val text = raw.trim()
        if (text.isBlank()) return Component.empty()

        val component = when {
            text.contains('§') -> runCatching { legacy.deserialize(text) }.getOrNull()
            Regex("(?i)&([0-9A-FK-ORX])").containsMatchIn(text) -> runCatching { legacyAmp.deserialize(text) }.getOrNull()
            else -> null
        }
        return component ?: Component.text(text)
    }

    private fun plainLuckPermsText(raw: String): String =
        plain.serialize(componentFromLuckPermsText(raw)).trim()

    private fun unwrapOptional(value: Any?): Any? {
        if (value == null || value.javaClass.name != "java.util.Optional") return value
        return runCatching {
            val present = value.javaClass.methods.firstOrNull { it.name == "isPresent" && it.parameterCount == 0 }
                ?.invoke(value) as? Boolean
            if (present != true) return@runCatching null
            value.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }?.invoke(value)
        }.getOrNull()
    }

    private fun refreshVaultChat(now: Long) {
        if (now < cachedChatRetryAt) return
        cachedChatProvider = null
        cachedChatMethod = null
        cachedChatWorldArg = null
        cachedChatPlayerArg = null

        val vault = plugin.server.pluginManager.getPlugin("Vault")
        if (vault == null || !vault.isEnabled) {
            cachedChatRetryAt = now + chatRetryTtlMs
            return
        }

        val chatClass = runCatching { Class.forName("net.milkbowl.vault.chat.Chat") }.getOrNull() ?: run {
            cachedChatRetryAt = now + chatRetryTtlMs
            return
        }
        @Suppress("UNCHECKED_CAST")
        val reg = plugin.server.servicesManager.getRegistration(chatClass as Class<Any>) ?: run {
            cachedChatRetryAt = now + chatRetryTtlMs
            return
        }

        val provider = reg.provider
        val method = selectChatMethod(provider.javaClass)
        if (method == null) {
            cachedChatRetryAt = now + chatRetryTtlMs
            return
        }

        cachedChatProvider = provider
        cachedChatMethod = method
        cachedChatExpiresAt = now + chatCacheTtlMs
        cachedChatRetryAt = now + chatCacheTtlMs
    }

    fun invalidateChatCache() {
        cachedChatProvider = null
        cachedChatMethod = null
        cachedChatWorldArg = null
        cachedChatPlayerArg = null
        cachedChatExpiresAt = 0L
        cachedChatRetryAt = 0L
    }

    private fun selectChatMethod(providerClass: Class<*>): Method? {
        val methods = providerClass.methods.filter { it.name == "getPlayerPrefix" && it.parameterCount == 2 }
        if (methods.isEmpty()) return null

        val pick = methods.firstOrNull {
            it.parameterTypes[0] == World::class.java && OfflinePlayer::class.java.isAssignableFrom(it.parameterTypes[1])
        }?.also {
            cachedChatWorldArg = ChatWorldArg.WORLD
            cachedChatPlayerArg = ChatPlayerArg.OFFLINE
        } ?: methods.firstOrNull {
            it.parameterTypes[0] == String::class.java && OfflinePlayer::class.java.isAssignableFrom(it.parameterTypes[1])
        }?.also {
            cachedChatWorldArg = ChatWorldArg.STRING
            cachedChatPlayerArg = ChatPlayerArg.OFFLINE
        } ?: methods.firstOrNull {
            it.parameterTypes[0] == World::class.java && Player::class.java.isAssignableFrom(it.parameterTypes[1])
        }?.also {
            cachedChatWorldArg = ChatWorldArg.WORLD
            cachedChatPlayerArg = ChatPlayerArg.PLAYER
        } ?: methods.firstOrNull {
            it.parameterTypes[0] == String::class.java && Player::class.java.isAssignableFrom(it.parameterTypes[1])
        }?.also {
            cachedChatWorldArg = ChatWorldArg.STRING
            cachedChatPlayerArg = ChatPlayerArg.PLAYER
        }

        return pick
    }

    private fun stripColorCodes(s: String): String {
        // Strip color codes: &a, §a, etc.
        return s
            .replace(Regex("(?i)[&§][0-9A-FK-OR]"), "")
            .trim()
    }
    private companion object {
        private const val CLICK_DEDUP_MS: Long = 150L
        private const val NO_MAYOR_REFRESH_KEY: String = "__no_mayor__"
    }

    private enum class ChatWorldArg { WORLD, STRING }
    private enum class ChatPlayerArg { OFFLINE, PLAYER }

    private data class ClickKey(val playerId: UUID, val entityId: UUID)
    private data class ResolvedMayorName(val component: Component, val plain: String)
    private data class LuckPermsMetaSnapshot(
        val prefix: String,
        val prefixPlain: String,
        val suffix: String,
        val suffixPlain: String
    )
}

