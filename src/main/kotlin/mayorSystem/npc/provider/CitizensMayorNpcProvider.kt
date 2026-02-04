package mayorSystem.npc.provider

import mayorSystem.MayorPlugin
import mayorSystem.npc.MayorNpcIdentity
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import java.util.UUID

class CitizensMayorNpcProvider : MayorNpcProvider {

    override val id: String = "citizens"

    private lateinit var plugin: MayorPlugin
    private var npcId: Int? = null
    private var npcUuid: UUID? = null
    private var entityUuid: UUID? = null

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

        // Validate stored NPC id; if it's stale, clear it so we can re-resolve.
        if (npcId != null && getNpc() == null) {
            npcId = null
            plugin.config.set("npc.mayor.citizens.npc_id", null)
            plugin.saveConfig()
        }

        if (npcId == null) {
            npcUuid?.let { resolveNpcFromUuid(it) }
        }

        if (npcId == null) {
            entityUuid?.let { resolveNpcFromUuid(it) }
        }

        if (npcId == null) {
            resolveMostRecentMayorNpc()
        }

        cleanupNonCurrentMayorNpcs()
    }

    override fun onDisable() {
        // no-op
    }

    override fun spawnOrMove(loc: Location, actorName: String?) {
        val npc = getOrCreateNpc(actorName ?: "Mayor") ?: return
        markNpc(npc)
        rememberNpc(npc, null)

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
        ent?.let {
            entityUuid = it.uniqueId
            plugin.config.set("npc.mayor.citizens.entity_uuid", it.uniqueId.toString())
            plugin.saveConfig()
        }
    }

    override fun remove() {
        val npc = getNpc() ?: return
        runCatching {
            // despawn/destroy
            npc.javaClass.methods.firstOrNull { it.name == "destroy" && it.parameterCount == 0 }?.invoke(npc)
                ?: npc.javaClass.methods.firstOrNull { it.name == "despawn" && it.parameterCount == 1 }?.invoke(npc, "PLUGIN")
        }

        // Try unregister from registry (avoid ghost ids)
        runCatching {
            val reg = npcRegistry() ?: return@runCatching
            reg.javaClass.methods.firstOrNull { it.name == "deregister" && it.parameterCount == 1 }?.invoke(reg, npc)
        }

        npcId = null
        npcUuid = null
        entityUuid = null
        plugin.config.set("npc.mayor.citizens.npc_id", null)
        plugin.config.set("npc.mayor.citizens.npc_uuid", null)
        plugin.config.set("npc.mayor.citizens.entity_uuid", null)
        plugin.saveConfig()
    }

    override fun updateMayor(identity: MayorNpcIdentity?) {
        val npc = getNpc() ?: return
        markNpc(npc)

        // Name
        val name = if (identity == null) "No mayor" else "${identity.titlePlain} ${identity.displayNamePlain}"
        runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "setName" && it.parameterCount == 1 }?.invoke(npc, name)
        }

        // Skin (only for player NPCs)
        val fallbackSkin = plugin.config.getString("npc.mayor.default_skin")?.takeIf { it.isNotBlank() } ?: "Steve"
        if (identity != null) {
            setSkinName(npc, identity.uuid.toString(), identity.lastKnownName ?: identity.displayNamePlain)
        } else {
            // Reset to a predictable default skin when there is no elected mayor.
            setSkinName(npc, "default", fallbackSkin)
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
        val loc = readLocationFromConfig() ?: return
        spawnOrMove(loc, actorName = "Mayor")
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

    private fun getNpc(): Any? {
        val id = npcId ?: return null
        return runCatching {
            val reg = npcRegistry() ?: return@runCatching null
            // method name differs across versions: getById or getNPC
            reg.javaClass.methods.firstOrNull { it.name == "getById" && it.parameterCount == 1 }?.invoke(reg, id)
                ?: reg.javaClass.methods.firstOrNull { it.name == "getNPC" && it.parameterCount == 1 }?.invoke(reg, id)
        }.getOrNull()
    }

    private fun getOrCreateNpc(baseName: String): Any? {
        val existing = getNpc()
        if (existing != null) return existing
        npcUuid?.let { resolveNpcFromUuid(it) }?.let { return it }
        entityUuid?.let { resolveNpcFromUuid(it) }?.let { return it }
        resolveMostRecentMayorNpc()?.let { return it }

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

    private fun removeNpcInstance(npc: Any) {
        runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "destroy" && it.parameterCount == 0 }?.invoke(npc)
                ?: npc.javaClass.methods.firstOrNull { it.name == "despawn" && it.parameterCount == 1 }?.invoke(npc, "PLUGIN")
        }
        runCatching {
            val reg = npcRegistry() ?: return@runCatching
            reg.javaClass.methods.firstOrNull { it.name == "deregister" && it.parameterCount == 1 }?.invoke(reg, npc)
        }
    }

    private companion object {
        private const val MAYOR_NPC_MARKER_KEY: String = "mayorsystem_mayor_npc"
    }

    private fun resolveNpcFromUuid(uuid: UUID): Any? {
        val reg = npcRegistry() ?: return null
        val m = reg.javaClass.methods.firstOrNull {
            it.name == "getNPC" && it.parameterCount == 1 && it.parameterTypes[0] == UUID::class.java
        } ?: return null
        val npc = runCatching { m.invoke(reg, uuid) }.getOrNull() ?: return null
        rememberNpc(npc, null)
        return npc
    }

    private fun resolveMostRecentMayorNpc(): Any? {
        val reg = npcRegistry() ?: return null
        val iterator = reg.javaClass.methods.firstOrNull { it.name == "iterator" && it.parameterCount == 0 }
            ?.invoke(reg) as? Iterator<*> ?: return null

        var bestNpc: Any? = null
        var bestId: Int? = null
        while (iterator.hasNext()) {
            val npc = iterator.next() ?: continue
            if (!isMarkedNpc(npc) && !looksLikeMayorNpc(npc)) continue
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
        val id = npc.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterCount == 0 }?.invoke(npc) as? Int
        if (id != null) {
            npcId = id
            plugin.config.set("npc.mayor.citizens.npc_id", id)
            plugin.saveConfig()
        }

        val uuidMethod = npc.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf("getUniqueId", "getUUID", "getUuid")
        }
        val rawUuid = uuidMethod?.let { runCatching { it.invoke(npc) }.getOrNull() }
        val uuid = when (rawUuid) {
            is UUID -> rawUuid
            is String -> runCatching { UUID.fromString(rawUuid) }.getOrNull()
            else -> null
        }
        if (uuid != null) {
            npcUuid = uuid
            plugin.config.set("npc.mayor.citizens.npc_uuid", uuid.toString())
            plugin.saveConfig()
        }

        val ent = entityOverride ?: runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "getEntity" && it.parameterCount == 0 }?.invoke(npc) as? Entity
        }.getOrNull()

        ent?.let {
            entityUuid = it.uniqueId
            plugin.config.set("npc.mayor.citizens.entity_uuid", it.uniqueId.toString())
            plugin.saveConfig()
        }
    }

    private fun cleanupNonCurrentMayorNpcs() {
        val keepId = npcId ?: return
        val reg = npcRegistry() ?: return
        val iterator = reg.javaClass.methods.firstOrNull { it.name == "iterator" && it.parameterCount == 0 }
            ?.invoke(reg) as? Iterator<*> ?: return

        val toRemove = mutableListOf<Any>()
        while (iterator.hasNext()) {
            val npc = iterator.next() ?: continue
            val id = npcIdOf(npc) ?: continue
            if (id == keepId) continue
            if (isMarkedNpc(npc) || looksLikeMayorNpc(npc)) {
                toRemove += npc
            }
        }

        for (npc in toRemove) {
            removeNpcInstance(npc)
        }
    }

    private fun markNpc(npc: Any) {
        val data = npcData(npc) ?: return
        runCatching {
            val m = data.javaClass.methods.firstOrNull { it.name == "set" && it.parameterCount == 2 } ?: return@runCatching
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
        return name.contains("Mayor", ignoreCase = true) || name.contains("No mayor", ignoreCase = true)
    }

    private fun npcData(npc: Any): Any? =
        npc.javaClass.methods.firstOrNull { it.name == "data" && it.parameterCount == 0 }?.invoke(npc)

    private fun setSkinName(npc: Any, cacheKey: String, skinName: String) {
        runCatching {
            val skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait")
            val getOrAddTrait = npc.javaClass.methods.firstOrNull { it.name == "getOrAddTrait" && it.parameterCount == 1 }
                ?: return@runCatching

            val trait = getOrAddTrait.invoke(npc, skinTraitClass) ?: return@runCatching

            // Prefer setSkinName(String)
            val m1 = trait.javaClass.methods.firstOrNull { it.name == "setSkinName" && it.parameterCount == 1 }
            if (m1 != null) {
                m1.invoke(trait, skinName)
                return@runCatching
            }

            // As fallback, try data keys (older)
            val data = npc.javaClass.methods.firstOrNull { it.name == "data" && it.parameterCount == 0 }?.invoke(npc) ?: return@runCatching
            val npcClass = Class.forName("net.citizensnpcs.api.npc.NPC")
            val metaField = npcClass.fields.firstOrNull { it.name == "PLAYER_SKIN_UUID_METADATA" }
            val latestField = npcClass.fields.firstOrNull { it.name == "PLAYER_SKIN_USE_LATEST" }
            val setMethod = data.javaClass.methods.firstOrNull { it.name == "set" && it.parameterCount == 2 } ?: return@runCatching
            if (metaField != null) setMethod.invoke(data, metaField.get(null), cacheKey)
            if (latestField != null) setMethod.invoke(data, latestField.get(null), false)
        }
    }
}

