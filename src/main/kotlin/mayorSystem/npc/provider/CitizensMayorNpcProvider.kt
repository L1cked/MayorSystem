package mayorSystem.npc.provider

import mayorSystem.MayorPlugin
import mayorSystem.npc.MayorNpcDisplayNames
import mayorSystem.npc.MayorNpcIdentity
import mayorSystem.util.loggedTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import java.util.UUID

class CitizensMayorNpcProvider : MayorNpcProvider {

    override val id: String = "citizens"

    private lateinit var plugin: MayorPlugin
    private var npcId: Int? = null
    private var npcUuid: UUID? = null
    private var entityUuid: UUID? = null
    private var lastRefreshKey: String? = null
    private val pending: MutableList<() -> Unit> = mutableListOf()
    private var waitTaskId: Int = -1
    private var warnedNotLoaded: Boolean = false
    private var waitStartedAtMs: Long = 0L
    private var startupCleanupTaskId: Int = -1
    private var startupCleanupAttempts: Int = 0
    @Volatile private var citizensLoaded: Boolean = false
    private val citizensLifecycleListeners: MutableList<Listener> = mutableListOf()
    private val loggedLegacyCleanupCandidates: MutableSet<Int> = mutableSetOf()
    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacySection()

    override fun isAvailable(plugin: MayorPlugin): Boolean {
        val p = plugin.server.pluginManager.getPlugin("Citizens")
        if (p == null || !p.isEnabled) return false
        return runCatching { Class.forName("net.citizensnpcs.api.CitizensAPI"); true }.getOrDefault(false)
    }

    override fun onEnable(plugin: MayorPlugin) {
        this.plugin = plugin
        npcId = plugin.config.getInt("npc.mayor.citizens.npc_id").takeIf { it > 0 }
        npcUuid = plugin.config.getString("npc.mayor.citizens.npc_uuid")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val raw = plugin.config.getString("npc.mayor.citizens.entity_uuid")
        entityUuid = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        lastRefreshKey = null
        citizensLoaded = false
        registerCitizensLifecycleListeners()

        if (!plugin.config.getBoolean("npc.mayor.enabled", false)) return

        runWhenRegistryLoaded {
            val loc = readLocationFromConfig()
            val resolved = resolveExistingMayorNpc(loc)
            if (resolved != null) {
                markNpc(resolved)
                rememberNpc(resolved, null)
                cleanupDuplicateMayorNpcs(resolved, loc, includeLegacy = true)
            }
        }

        scheduleStartupCleanup()
    }

    override fun onDisable() {
        cancelWaitTasks(keepPending = false)
        cancelStartupCleanup()
        unregisterCitizensLifecycleListeners()
    }

    override fun spawnOrMove(loc: Location, actorName: String?) {
        runWhenRegistryLoaded {
            spawnOrMoveInternal(loc, actorName)
        }
    }

    private fun spawnOrMoveInternal(loc: Location, actorName: String?) {
        val fallbackNpc = if (getNpc() == null) {
            resolveExistingMayorNpc(loc)
        } else {
            null
        }
        val npc = getNpc()
            ?: fallbackNpc
            ?: getOrCreateNpc(actorName ?: npcTitleLegacy())
            ?: return
        markNpc(npc)
        rememberNpc(npc, null)
        lastRefreshKey = null

        // Spawn/teleport
        runCatching {
            val isSpawned = npc.javaClass.methods.firstOrNull { it.name == "isSpawned" && it.parameterCount == 0 }?.invoke(npc) as? Boolean ?: false
            if (!isSpawned) {
                npc.javaClass.methods.firstOrNull { it.name == "spawn" && it.parameterCount == 1 }?.invoke(npc, loc)
            } else {
                val ent = npc.javaClass.methods.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }?.invoke(npc) as? Entity
                ent?.teleport(loc)
            }
        }

        // Store entity uuid (best effort)
        val ent = runCatching { npc.javaClass.methods.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }?.invoke(npc) as? Entity }.getOrNull()
        ent?.let { rememberNpc(npc, it) }

        cleanupDuplicateMayorNpcs(npc, loc, includeLegacy = true)
    }

    override fun remove() {
        runWhenRegistryLoaded {
            val removed = removeMayorNpcInstances(readLocationFromConfig(), includeLegacy = true)
            clearStoredNpcIdentity()
            saveRegistry()
            lastRefreshKey = null
            if (removed > 0) {
                plugin.logger.info("[MayorNPC] Removed $removed Citizens mayor NPC instance(s).")
            }
        }
    }

    override fun updateMayor(identity: MayorNpcIdentity?) {
        // Citizens may not be fully loaded yet during early startup. Queue the update so
        // we don't get stuck showing the old mayor until a manual refresh.
        runWhenRegistryLoaded {
            var npc = getNpc()
            if (npc == null) {
                // If the NPC isn't created yet but is enabled, attempt to spawn at the stored location.
                if (plugin.config.getBoolean("npc.mayor.enabled", false)) {
                    val loc = readLocationFromConfig()
                    if (loc != null) {
                        spawnOrMoveInternal(loc, actorName = null)
                        npc = getNpc()
                    }
                }
                if (npc == null) return@runWhenRegistryLoaded
            }

            markNpc(npc)

            val fallbackSkin = plugin.config.getString("npc.mayor.default_skin")?.takeIf { it.isNotBlank() } ?: "Steve"
            val skinName = resolveSkinIdentityName(identity, fallbackSkin)
            val refreshKey = listOf(
                identity?.uuid?.toString() ?: "no_mayor",
                skinName,
                identity?.skinTextureValue?.hashCode()?.toString().orEmpty(),
                identity?.skinTextureSignature?.hashCode()?.toString().orEmpty()
            ).joinToString("|")
            val shouldRefresh = refreshKey != lastRefreshKey

            // Name
            val name = if (identity == null) {
                npcNoMayorLegacy()
            } else {
                MayorNpcDisplayNames.legacy(identity, legacy)
            }
            runCatching {
                npc.javaClass.methods.firstOrNull { it.name == "setName" && it.parameterCount == 1 }?.invoke(npc, name)
            }

            // Skin (only for player NPCs)
            if (shouldRefresh) {
                val directApplied = if (identity != null) {
                    setSkinTexture(npc, skinName, identity.skinTextureValue, identity.skinTextureSignature)
                } else {
                    false
                }
                if (!directApplied) {
                    setSkinName(npc, skinName)
                }
                lastRefreshKey = refreshKey
            }
        }
    }

    override fun isMayorNpc(entity: Entity): Boolean {
        if (entityUuid != null && entity.uniqueId == entityUuid) return true

        val npc = getNpc() ?: return false
        val ent = runCatching { npc.javaClass.methods.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }?.invoke(npc) as? Entity }.getOrNull()
        return ent?.uniqueId == entity.uniqueId
    }

    override fun restoreFromConfig() {
        val enabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        if (!enabled) return
        runWhenRegistryLoaded {
            val loc = readLocationFromConfig()
            val resolved = resolveExistingMayorNpc(loc)
            if (resolved != null) {
                markNpc(resolved)
                rememberNpc(resolved, null)
                if (loc != null) {
                    spawnOrMoveInternal(loc, actorName = null)
                } else {
                    cleanupDuplicateMayorNpcs(resolved, loc, includeLegacy = true)
                    plugin.mayorNpc.forceUpdateMayor()
                }
            }
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

    private fun npcRegistry(): Any? {
        val api = Class.forName("net.citizensnpcs.api.CitizensAPI")
        return api.methods.firstOrNull { it.name == "getNPCRegistry" && it.parameterCount == 0 }?.invoke(null)
    }

    private fun isCitizensRegistryReady(): Boolean {
        val citizens = plugin.server.pluginManager.getPlugin("Citizens") ?: return false
        if (!citizens.isEnabled) return false
        if (npcRegistry() == null) return false
        if (citizensLoaded) return true
        return runCatching {
            val field = citizens.javaClass.getDeclaredField("enabled")
            field.isAccessible = true
            field.getBoolean(citizens)
        }.getOrDefault(false)
    }

    private fun registerCitizensLifecycleListeners() {
        registerCitizensLifecycleListener("net.citizensnpcs.api.event.CitizensEnableEvent")
        registerCitizensLifecycleListener("net.citizensnpcs.api.event.CitizensReloadEvent")
    }

    private fun registerCitizensLifecycleListener(className: String) {
        val eventClass = runCatching {
            Class.forName(className).asSubclass(Event::class.java)
        }.getOrNull() ?: return
        val listener = object : Listener {}
        val executor = EventExecutor { _, _ ->
            citizensLoaded = true
            flushPendingCitizensTasks()
        }
        runCatching {
            plugin.server.pluginManager.registerEvent(
                eventClass,
                listener,
                EventPriority.MONITOR,
                executor,
                plugin,
                true
            )
            citizensLifecycleListeners += listener
        }
    }

    private fun unregisterCitizensLifecycleListeners() {
        for (listener in citizensLifecycleListeners) {
            runCatching { HandlerList.unregisterAll(listener) }
        }
        citizensLifecycleListeners.clear()
        citizensLoaded = false
    }

    private fun getNpc(): Any? {
        val id = npcId ?: return null
        return runCatching {
            val reg = npcRegistry() ?: return@runCatching null
            reg.javaClass.methods.firstOrNull { it.name == "getById" && it.parameterCount == 1 }?.invoke(reg, id)
        }.getOrNull()
    }

    private fun getNpcById(id: Int): Any? =
        runCatching {
            val reg = npcRegistry() ?: return@runCatching null
            reg.javaClass.methods.firstOrNull { it.name == "getById" && it.parameterCount == 1 }?.invoke(reg, id)
        }.getOrNull()

    private fun getOrCreateNpc(baseName: String): Any? {
        val existing = getNpc()
        if (existing != null) return existing
        npcUuid?.let { resolveNpcFromUuid(it) }?.let { return it }

        return runCatching {
            val reg = npcRegistry() ?: return@runCatching null
            val create = reg.javaClass.methods.firstOrNull { it.name == "createNPC" && it.parameterCount == 2 }
                ?: return@runCatching null
            val npc = create.invoke(reg, EntityType.PLAYER, baseName)
            rememberNpc(npc, null)
            npc
        }.getOrNull()
    }

    private fun npcIdOf(npc: Any): Int? =
        npc.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterCount == 0 }?.invoke(npc) as? Int

    private fun npcUuidOf(npc: Any): UUID? {
        val uuidMethod = npc.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf("getUniqueId", "getUUID", "getUuid")
        } ?: return null
        return when (val raw = runCatching { uuidMethod.invoke(npc) }.getOrNull()) {
            is UUID -> raw
            is String -> runCatching { UUID.fromString(raw) }.getOrNull()
            else -> null
        }
    }

    private fun hasStoredNpcIdentity(): Boolean =
        npcId != null || npcUuid != null || entityUuid != null

    private fun isKnownPluginNpc(npc: Any): Boolean =
        isMarkedNpc(npc) ||
            npcIdOf(npc)?.let { it == npcId } == true ||
            npcUuidOf(npc)?.let { it == npcUuid } == true

    private fun removeNpcInstance(npc: Any) {
        runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "destroy" && it.parameterCount == 0 }?.invoke(npc)
                ?: despawnNpc(npc)
        }
        runCatching {
            val reg = npcRegistry() ?: return@runCatching
            reg.javaClass.methods.firstOrNull { it.name == "deregister" && it.parameterCount == 1 }?.invoke(reg, npc)
        }
    }

    private fun despawnNpc(npc: Any) {
        val despawn = npc.javaClass.methods.firstOrNull { it.name == "despawn" && it.parameterCount == 1 } ?: return
        val reasonType = despawn.parameterTypes[0]
        val pluginReason = reasonType.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == "PLUGIN" }
            ?: reasonType.enumConstants?.firstOrNull()
            ?: return
        despawn.invoke(npc, pluginReason)
    }

    private fun saveRegistry() {
        runCatching {
            val reg = npcRegistry() ?: return@runCatching
            reg.javaClass.methods
                ?.firstOrNull { it.name == "saveToStore" && it.parameterCount == 0 }
                ?.invoke(reg)
        }
    }

    private fun clearStoredNpcIdentity() {
        npcId = null
        npcUuid = null
        entityUuid = null
        plugin.config.set("npc.mayor.citizens.npc_id", null)
        plugin.config.set("npc.mayor.citizens.npc_uuid", null)
        plugin.config.set("npc.mayor.citizens.entity_uuid", null)
        plugin.config.set("npc.mayor.citizens.known_ids", emptyList<Int>())
        plugin.saveConfig()
    }

    private fun resolveNpcFromUuid(uuid: UUID): Any? {
        val reg = npcRegistry() ?: return null

        val primary = reg.javaClass.methods.firstOrNull {
            it.name == "getByUniqueId" && it.parameterCount == 1 && it.parameterTypes[0] == UUID::class.java
        }
        val secondary = reg.javaClass.methods.firstOrNull {
            it.name == "getByUniqueIdGlobal" && it.parameterCount == 1 && it.parameterTypes[0] == UUID::class.java
        }

        val npc = runCatching {
            primary?.invoke(reg, uuid) ?: secondary?.invoke(reg, uuid)
        }.getOrNull() ?: return null

        rememberNpc(npc, null)
        return npc
    }

    private fun resolveMostRecentMayorNpc(loc: Location? = null): Any? {
        val reg = npcRegistry() ?: return null
        val iterator = reg.javaClass.methods.firstOrNull { it.name == "iterator" && it.parameterCount == 0 }
            ?.invoke(reg) as? Iterator<*> ?: return null

        var bestNpc: Any? = null
        var bestId: Int? = null
        while (iterator.hasNext()) {
            val npc = iterator.next() ?: continue
            if (!isMarkedNpc(npc) && !isStrictLegacyMayorNpc(npc)) continue
            if (loc != null && !isNpcNearLocation(npc, loc, 1.0)) continue
            val id = npcIdOf(npc) ?: continue
            if (bestId == null || id > bestId) {
                bestId = id
                bestNpc = npc
            }
        }

        if (bestNpc != null) {
            rememberNpc(bestNpc, null)
        }
        return bestNpc
    }

    private fun rememberNpc(npc: Any, entityOverride: Entity?) {
        var changed = false

        val id = npc.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterCount == 0 }?.invoke(npc) as? Int
        if (id != null) {
            val currentKnownIds = knownIds()
            val liveKnownIds = (currentKnownIds + id)
                .filter { knownId -> knownId == id || getNpcById(knownId) != null }
                .sorted()
            if (liveKnownIds.toSet() != currentKnownIds) {
                plugin.config.set("npc.mayor.citizens.known_ids", liveKnownIds)
                changed = true
            }
            if (npcId != id) {
                npcId = id
                plugin.config.set("npc.mayor.citizens.npc_id", id)
                changed = true
            }
        }

        val uuid = npcUuidOf(npc)
        if (uuid != null && npcUuid != uuid) {
            npcUuid = uuid
            plugin.config.set("npc.mayor.citizens.npc_uuid", uuid.toString())
            changed = true
        }

        val ent = entityOverride ?: runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }?.invoke(npc) as? Entity
        }.getOrNull()

        ent?.takeIf { entityUuid != it.uniqueId }?.let {
            entityUuid = it.uniqueId
            plugin.config.set("npc.mayor.citizens.entity_uuid", it.uniqueId.toString())
            changed = true
        }

        if (changed) plugin.saveConfig()
    }

    private fun cleanupDuplicateMayorNpcs(keep: Any, loc: Location?) {
        cleanupDuplicateMayorNpcs(keep, loc, includeLegacy = false)
    }

    private fun cleanupDuplicateMayorNpcs(keep: Any, loc: Location?, includeLegacy: Boolean) {
        val keepId = npcIdOf(keep) ?: return
        val reg = npcRegistry() ?: return
        val iterator = reg.javaClass.methods.firstOrNull { it.name == "iterator" && it.parameterCount == 0 }
            ?.invoke(reg) as? Iterator<*> ?: return

        val toRemove = mutableListOf<Any>()
        while (iterator.hasNext()) {
            val npc = iterator.next() ?: continue
            val id = npcIdOf(npc) ?: continue
            if (id == keepId) continue
            if (loc != null) {
                if (!isNpcNearLocation(npc, loc, 1.0)) continue
            } else if (!isMarkedNpc(npc)) {
                continue
            }
            if (isKnownPluginNpc(npc) || isMarkedNpc(npc)) {
                toRemove += npc
            } else if (includeLegacy && isStrictLegacyMayorNpc(npc)) {
                toRemove += npc
            } else if (includeLegacy && loc != null && isNpcNearLocation(npc, loc, LEGACY_NEARBY_CLEANUP_RADIUS) && looksLikeMayorNpc(npc)) {
                toRemove += npc
            } else if (loc != null && looksLikeMayorNpc(npc) && loggedLegacyCleanupCandidates.add(id)) {
                plugin.logger.warning(
                    "[MayorNPC] Possible legacy Citizens mayor NPC id=$id near configured location is not marked or persisted; leaving it in place."
                )
            }
        }

        for (npc in toRemove) {
            removeNpcInstance(npc)
        }
        if (toRemove.isNotEmpty()) {
            saveRegistry()
            plugin.logger.info("[MayorNPC] Removed ${toRemove.size} duplicate Citizens mayor NPC instance(s).")
        }
    }

    private fun removeMayorNpcInstances(loc: Location?, includeLegacy: Boolean): Int {
        val reg = npcRegistry() ?: return 0
        val iterator = reg.javaClass.methods.firstOrNull { it.name == "iterator" && it.parameterCount == 0 }
            ?.invoke(reg) as? Iterator<*> ?: return 0

        val toRemove = mutableListOf<Any>()
        while (iterator.hasNext()) {
            val npc = iterator.next() ?: continue
            val known = isKnownPluginNpc(npc) || npcIdOf(npc)?.let { it in knownIds() } == true || isMarkedNpc(npc)
            val strictLegacy = includeLegacy && isStrictLegacyMayorNpc(npc)
            val nearbyLegacy = includeLegacy && loc != null && isNpcNearLocation(npc, loc, LEGACY_NEARBY_CLEANUP_RADIUS) && looksLikeMayorNpc(npc)
            if (known || strictLegacy || nearbyLegacy) {
                toRemove += npc
            }
        }

        for (npc in toRemove.distinctBy { npcIdOf(it) ?: -1 }) {
            removeNpcInstance(npc)
        }
        return toRemove.size
    }

    private fun markNpc(npc: Any) {
        val data = npcData(npc) ?: return
        runCatching {
            val m = data.javaClass.methods.firstOrNull { it.name == "setPersistent" && it.parameterCount == 2 }
                ?: data.javaClass.methods.firstOrNull { it.name == "set" && it.parameterCount == 2 }
                ?: return@runCatching
            m.invoke(data, MAYOR_NPC_MARKER_KEY, true)
        }
    }

    private fun isMarkedNpc(npc: Any): Boolean {
        val data = npcData(npc) ?: return false
        val value = runCatching {
            val m = data.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 1 } ?: return@runCatching null
            m.invoke(data, MAYOR_NPC_MARKER_KEY)
        }.getOrNull()
        return value == true
    }

    private fun looksLikeMayorNpc(npc: Any): Boolean {
        val name = runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }?.invoke(npc) as? String
        }.getOrNull()?.trim().orEmpty()
        if (name.isBlank()) return false
        val title = npcTitleLegacy()
        val noMayor = npcNoMayorLegacy()
        val configuredTitle = plugin.settings.titleName
        val configuredNoTitle = "No ${plugin.settings.titleNameLower()}"
        return name.contains(title, ignoreCase = true)
            || name.contains(noMayor, ignoreCase = true)
            || name.contains(configuredTitle, ignoreCase = true)
            || name.contains(configuredNoTitle, ignoreCase = true)
            || name.contains("Mayor", ignoreCase = true)
            || name.contains("No mayor", ignoreCase = true)
    }

    private fun isStrictLegacyMayorNpc(npc: Any): Boolean {
        val name = runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }?.invoke(npc) as? String
        }.getOrNull()?.trim().orEmpty()
        if (name.isBlank()) return false

        val plain = stripLegacyCodes(name)
        val title = stripLegacyCodes(npcTitleLegacy()).trim()
        val bracketedTitle = title.takeIf { it.isNotBlank() }?.let { "[$it]" }.orEmpty()
        val noMayor = stripLegacyCodes(npcNoMayorLegacy()).trim()
        return (title.isNotBlank() && (plain == title || plain.startsWith("$title "))) ||
            (bracketedTitle.isNotBlank() && (plain == bracketedTitle || plain.startsWith("$bracketedTitle "))) ||
            (noMayor.isNotBlank() && plain == noMayor)
    }

    private fun stripLegacyCodes(raw: String): String =
        raw
            .replace(Regex("(?i)(?:(?:\\u00C2)?\\u00A7|&)[0-9A-FK-ORX]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun npcData(npc: Any): Any? =
        npc.javaClass.methods.firstOrNull { it.name == "data" && it.parameterCount == 0 }?.invoke(npc)

    private fun resolveExistingMayorNpc(loc: Location?): Any? {
        val marked = resolveMarkedMayorNpc(loc)
        if (marked != null) return marked

        getNpc()?.let { return it }

        npcUuid?.let { resolveNpcFromUuid(it) }?.let { return it }

        for (id in knownIds().sortedDescending()) {
            getNpcById(id)?.let {
                rememberNpc(it, null)
                return it
            }
        }

        if (loc != null && !hasStoredNpcIdentity()) {
            resolveNpcNearLocation(loc)?.let { return it }
            resolveNpcFromNearbyEntities(loc)?.let { return it }
            resolveMostRecentMayorNpc(loc)?.let { return it }
        }

        if (hasStoredNpcIdentity()) {
            resolveMostRecentMayorNpc(null)?.let { return it }
        }

        return null
    }

    private fun resolveMarkedMayorNpc(loc: Location?): Any? {
        val reg = npcRegistry() ?: return null
        val iterator = reg.javaClass.methods.firstOrNull { it.name == "iterator" && it.parameterCount == 0 }
            ?.invoke(reg) as? Iterator<*> ?: return null

        var bestNpc: Any? = null
        var bestId: Int? = null
        while (iterator.hasNext()) {
            val npc = iterator.next() ?: continue
            if (!isMarkedNpc(npc)) continue
            if (loc != null && !isNpcNearLocation(npc, loc, 1.0)) continue
            val id = npcIdOf(npc) ?: continue
            if (bestId == null || id > bestId) {
                bestId = id
                bestNpc = npc
            }
        }
        if (bestNpc != null) {
            rememberNpc(bestNpc, null)
        }
        return bestNpc
    }

    private fun resolveNpcFromNearbyEntities(loc: Location): Any? {
        val world = loc.world ?: return null
        val reg = npcRegistry() ?: return null
        val method = reg.javaClass.methods.firstOrNull {
            it.name == "getNPC" && it.parameterCount == 1 &&
                org.bukkit.entity.Entity::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return null

        val nearby = runCatching { world.getNearbyEntities(loc, 0.6, 1.6, 0.6) }.getOrNull() ?: return null
        for (entity in nearby) {
            val npc = runCatching { method.invoke(reg, entity) }.getOrNull() ?: continue
            if (!isMarkedNpc(npc) && !isStrictLegacyMayorNpc(npc)) continue
            rememberNpc(npc, entity)
            return npc
        }
        return null
    }

    private fun resolveNpcNearLocation(loc: Location): Any? {
        val reg = npcRegistry() ?: return null
        val iterator = reg.javaClass.methods.firstOrNull { it.name == "iterator" && it.parameterCount == 0 }
            ?.invoke(reg) as? Iterator<*> ?: return null

        var bestNpc: Any? = null
        var bestDist = Double.MAX_VALUE
        while (iterator.hasNext()) {
            val npc = iterator.next() ?: continue
            if (!isMarkedNpc(npc) && !isStrictLegacyMayorNpc(npc)) continue
            val npcLoc = npcStoredLocation(npc) ?: npcEntityLocation(npc) ?: continue
            if (npcLoc.world?.name != loc.world?.name) continue
            val dist = npcLoc.distanceSquared(loc)
            if (dist <= 1.0 && dist < bestDist) {
                bestDist = dist
                bestNpc = npc
            }
        }

        if (bestNpc != null) {
            rememberNpc(bestNpc, null)
        }
        return bestNpc
    }

    private fun knownIds(): Set<Int> =
        plugin.config.getIntegerList("npc.mayor.citizens.known_ids").filter { it > 0 }.toSet()

    private fun npcStoredLocation(npc: Any): Location? {
        val method = npc.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf("getStoredLocation", "getLocation")
        } ?: return null
        return runCatching { method.invoke(npc) as? Location }.getOrNull()
    }

    private fun npcEntityLocation(npc: Any): Location? {
        val ent = runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }?.invoke(npc) as? Entity
        }.getOrNull() ?: return null
        return ent.location
    }

    private fun isNpcNearLocation(npc: Any, loc: Location, radius: Double): Boolean {
        val npcLoc = npcStoredLocation(npc) ?: npcEntityLocation(npc) ?: return false
        if (npcLoc.world?.name != loc.world?.name) return false
        return npcLoc.distanceSquared(loc) <= radius * radius
    }

    private fun setSkinName(npc: Any, skinName: String) {
        runCatching {
            val skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait")
            val getOrAddTrait = npc.javaClass.methods.firstOrNull { it.name == "getOrAddTrait" && it.parameterCount == 1 }
                ?: return@runCatching

            val trait = getOrAddTrait.invoke(npc, skinTraitClass) ?: return@runCatching
            val m2 = trait.javaClass.methods.firstOrNull { it.name == "setSkinName" && it.parameterCount == 2 }
            if (m2 != null) {
                m2.invoke(trait, skinName, true)
                return@runCatching
            }

            val m1 = trait.javaClass.methods.firstOrNull { it.name == "setSkinName" && it.parameterCount == 1 }
            if (m1 != null) {
                m1.invoke(trait, skinName)
                return@runCatching
            }
        }
    }

    private fun resolveSkinIdentityName(identity: MayorNpcIdentity?, fallbackSkin: String): String {
        if (identity == null) return fallbackSkin

        val onlineName = Bukkit.getPlayer(identity.uuid)?.name?.trim()?.takeIf { it.isNotBlank() }
        val preferredName = onlineName
            ?: identity.lastKnownName?.trim()?.takeIf { it.isNotBlank() }
            ?: Bukkit.getOfflinePlayer(identity.uuid).name?.trim()?.takeIf { it.isNotBlank() }
        val normalizedPreferred = if (identity.isBedrockPlayer) {
            preferredName?.let(::normalizeBedrockSkinIdentityName)
        } else {
            preferredName
        }

        if (!identity.skinTextureValue.isNullOrBlank()) {
            return normalizedPreferred ?: "bedrock-${identity.uuid.toString().replace("-", "")}"
        }

        if (identity.isBedrockPlayer) {
            return fallbackSkin
        }

        return normalizedPreferred ?: fallbackSkin
    }

    private fun normalizeBedrockSkinIdentityName(raw: String): String {
        val normalized = raw.trim().replace(' ', '_')
        if (normalized.isBlank()) return normalized
        if (normalized.startsWith(".") || normalized.startsWith("_")) return normalized
        return ".$normalized"
    }

    private fun setSkinTexture(npc: Any, skinName: String, texture: String?, signature: String?): Boolean {
        if (texture.isNullOrBlank()) return false
        return runCatching {
            val skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait")
            val getOrAddTrait = npc.javaClass.methods.firstOrNull { it.name == "getOrAddTrait" && it.parameterCount == 1 }
                ?: return@runCatching false

            val trait = getOrAddTrait.invoke(npc, skinTraitClass) ?: return@runCatching false
            trait.javaClass.methods.firstOrNull { it.name == "clearTexture" && it.parameterCount == 0 }
                ?.invoke(trait)
            trait.javaClass.methods.firstOrNull { it.name == "setShouldUpdateSkins" && it.parameterCount == 1 }
                ?.invoke(trait, false)
            trait.javaClass.methods.firstOrNull { it.name == "setFetchDefaultSkin" && it.parameterCount == 1 }
                ?.invoke(trait, false)

            val applied = if (!signature.isNullOrBlank()) {
                val persistent = trait.javaClass.methods.firstOrNull {
                    it.name == "setSkinPersistent" && it.parameterCount == 3
                }
                if (persistent != null) {
                    persistent.invoke(trait, skinName, signature, texture)
                    true
                } else {
                    false
                }
            } else {
                false
            }

            if (!applied) {
                val applyInternal = trait.javaClass.methods.firstOrNull {
                    it.name == "applyTextureInternal" && it.parameterCount == 2
                } ?: return@runCatching false
                applyInternal.invoke(trait, signature ?: "", texture)
                notifySkinChange(npc, force = false)
            }
            true
        }.getOrDefault(false)
    }

    private fun notifySkinChange(npc: Any, force: Boolean) {
        runCatching {
            val entity = npc.javaClass.methods.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }
                ?.invoke(npc)
                ?: return@runCatching
            val tracker = entity.javaClass.methods.firstOrNull { it.name == "getSkinTracker" && it.parameterCount == 0 }
                ?.invoke(entity)
                ?: return@runCatching
            tracker.javaClass.methods.firstOrNull {
                it.name == "notifySkinChange" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == java.lang.Boolean.TYPE
            }?.invoke(tracker, force)
        }
    }

    private fun npcTitleLegacy(): String = miniToLegacy(
        plugin.messages.get("npc.title")?.trim(),
        fallbackPlain = plugin.settings.titleName
    )

    private fun npcNoMayorLegacy(): String = miniToLegacy(
        plugin.messages.get("npc.no_mayor")?.trim(),
        fallbackPlain = "No ${plugin.settings.titleNameLower()}"
    )

    private fun miniToLegacy(raw: String?, fallbackPlain: String): String {
        val value = raw?.takeIf { it.isNotBlank() } ?: fallbackPlain
        val component = runCatching { mini.deserialize(value) }.getOrElse { Component.text(fallbackPlain) }
        val legacyText = legacy.serialize(component)
        return if (legacyText.isBlank()) fallbackPlain else legacyText
    }

    private fun runWhenRegistryLoaded(task: () -> Unit) {
        if (isCitizensRegistryReady()) {
            plugin.server.scheduler.runTask(plugin, plugin.loggedTask("citizens registry loaded callback") { task() })
            return
        }

        pending += task
        if (waitTaskId != -1) return

        warnedNotLoaded = false
        waitStartedAtMs = System.currentTimeMillis()
        waitTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, plugin.loggedTask("citizens registry wait") {
            if (!isCitizensRegistryReady()) {
                if (!warnedNotLoaded && System.currentTimeMillis() - waitStartedAtMs >= 30_000L) {
                    warnedNotLoaded = true
                    plugin.logger.warning("[MayorNPC] Citizens NPC registry not ready after 30s; will keep waiting...")
                }
                return@loggedTask
            }

            flushPendingCitizensTasks()
        }, 20L, 20L)
    }

    private fun flushPendingCitizensTasks() {
        if (pending.isEmpty()) {
            cancelWaitTasks(keepPending = false)
            return
        }
        val tasks = pending.toList()
        cancelWaitTasks(keepPending = false)
        pending.clear()
        tasks.forEach { it() }
    }

    private fun cancelWaitTasks(keepPending: Boolean = false) {
        if (waitTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(waitTaskId) }
            waitTaskId = -1
        }
        if (!keepPending) pending.clear()
    }

    private fun scheduleStartupCleanup() {
        if (startupCleanupTaskId != -1) return
        startupCleanupAttempts = 0
        startupCleanupTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, plugin.loggedTask("citizens startup cleanup") {
            if (!plugin.isEnabled) {
                cancelStartupCleanup()
                return@loggedTask
            }
            if (!plugin.config.getBoolean("npc.mayor.enabled", false)) {
                cancelStartupCleanup()
                return@loggedTask
            }
            startupCleanupAttempts++
            runWhenRegistryLoaded {
                val loc = readLocationFromConfig()
                val resolved = resolveExistingMayorNpc(loc)
                if (resolved != null) {
                    markNpc(resolved)
                    rememberNpc(resolved, null)
                    cleanupDuplicateMayorNpcs(resolved, loc, includeLegacy = true)
                }
            }
            if (startupCleanupAttempts >= 5) {
                cancelStartupCleanup()
            }
        }, 60L, 60L)
    }

    private fun cancelStartupCleanup() {
        if (startupCleanupTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(startupCleanupTaskId) }
            startupCleanupTaskId = -1
        }
        startupCleanupAttempts = 0
    }

    private companion object {
        private const val MAYOR_NPC_MARKER_KEY: String = "mayorsystem_mayor_npc"
        private const val LEGACY_NEARBY_CLEANUP_RADIUS: Double = 8.0
    }
}

