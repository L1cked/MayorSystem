package mayorSystem.npc.provider

import mayorSystem.MayorPlugin
import mayorSystem.npc.MayorNpcIdentity
import mayorSystem.util.loggedTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventException
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.EventExecutor
import java.lang.reflect.Method
import java.util.UUID
import java.util.function.Consumer

class FancyNpcsMayorNpcProvider : MayorNpcProvider, Listener {

    override val id: String = "fancynpcs"

    private lateinit var plugin: MayorPlugin
    private var npcName: String = "mayorsystem_mayor_npc"
    private var lastIdentityUuid: UUID? = null

    // Some FancyNpcs versions don't fully refresh the client render after skin/profile updates.
    // We schedule a best-effort viewer refresh after changing the mayor identity so the NPC doesn't
    // appear as a villager until a chunk reload.
    private var hardRefreshTaskId: Int = -1
    private var hardRefreshKey: String? = null

    // FancyNpcs loads NPCs a bit after boot. Registering too early can fail.
    // The official docs recommend waiting ~10 seconds OR reacting to NpcsLoadedEvent.
    // We follow the docs by waiting for NpcsLoadedEvent, with a 10s fallback delay.
    private var npcsLoaded: Boolean = false
    private var loadedFallbackTaskId: Int = -1
    private var loadedListener: Listener? = null
    private val pending = mutableListOf<() -> Unit>()
    private val mini = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    override fun isAvailable(plugin: MayorPlugin): Boolean {
        val p = plugin.server.pluginManager.getPlugin("FancyNpcs") ?: plugin.server.pluginManager.getPlugin("FancyNPCs")
        if (p == null || !p.isEnabled) return false
        return runCatching { Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin"); true }.getOrDefault(false)
    }

    override fun onEnable(plugin: MayorPlugin) {
        this.plugin = plugin
        npcName = plugin.config.getString("npc.mayor.fancynpcs.npc_name") ?: npcName
        plugin.config.set("npc.mayor.fancynpcs.npc_name", npcName)
        plugin.saveConfig()

        // Ensure the NPC becomes visible for players joining AFTER server start.
        // Some FancyNpcs versions do not auto-spawn packet NPCs for late joiners unless explicitly re-sent.
        plugin.server.pluginManager.registerEvents(this, plugin)

        registerLoadedEvent()
    }

    override fun onDisable() {
        cancelLoadedFallback()
        cancelHardRefreshTask()
        runCatching { HandlerList.unregisterAll(this) }
        loadedListener?.let { runCatching { HandlerList.unregisterAll(it) } }
        loadedListener = null
        npcsLoaded = false
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        // Only relevant if the Mayor NPC is enabled.
        if (!plugin.config.getBoolean("npc.mayor.enabled", false)) return

        // Delay slightly so the client has completed initial chunk/world sync.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            runWhenLoaded {
                val npc = getNpc() ?: return@runWhenLoaded

                // Best-effort: spawn this NPC to the joining player (if such a method exists).
                val spawnedToPlayer = tryInvoke1(npc, listOf("spawnForPlayer", "showForPlayer", "spawn", "show"), e.player)
                if (!spawnedToPlayer) {
                    // Fallback: spawn for all (safe; join events are infrequent).
                    tryInvoke0(npc, listOf("spawnForAll", "showForAll"))
                }

                // Re-apply/update the current mayor identity now that we have an active viewer.
                // This fixes cases where skin/profile updates were sent during startup when no players were online.
                plugin.mayorNpc.forceUpdateMayor()

                // Some FancyNpcs builds still don't re-apply the *skin* for a late-joining viewer unless the NPC
                // is re-sent after the update. Prefer per-player methods; fall back to a short hard refresh.
                val removedForPlayer = tryInvoke1(npc, listOf("removeForPlayer", "despawnForPlayer", "hideForPlayer"), e.player)
                val spawnedForPlayer = tryInvoke1(npc, listOf("spawnForPlayer", "showForPlayer"), e.player)
                if (!(removedForPlayer || spawnedForPlayer)) {
                    // Last resort: refresh for all (join events are infrequent; this avoids requiring manual commands).
                    tryInvoke0(npc, listOf("removeForAll", "despawnForAll", "hideForAll"))
                    tryInvoke0(npc, listOf("spawnForAll", "showForAll"))
                    tryInvoke0(npc, listOf("updateForAll"))
                }
            }
        }, 20L)
    }

    override fun spawnOrMove(loc: Location, actorName: String?) {
        runWhenLoaded {
            val npc = getNpc()
            if (npc != null) {
                // Move + update
                runCatching {
                    val data = npc.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterCount == 0 }?.invoke(npc) ?: return@runCatching
                    data.javaClass.methods.firstOrNull { it.name == "setLocation" && it.parameterCount == 1 }?.invoke(data, loc)

                    // docs: modify data then updateForAll()
                    npc.javaClass.methods.firstOrNull { it.name == "updateForAll" && it.parameterCount == 0 }?.invoke(npc)

                    // Some versions require a respawn after moving (best-effort).
                    npc.javaClass.methods.firstOrNull { it.name == "removeForAll" && it.parameterCount == 0 }?.invoke(npc)
                    npc.javaClass.methods.firstOrNull { it.name == "spawnForAll" && it.parameterCount == 0 }?.invoke(npc)
                }
                // Ensure the NPC immediately reflects the current mayor after a spawn/move.
                plugin.mayorNpc.forceUpdateMayor()
                return@runWhenLoaded
            }

            // Create new NPC
            val created = createNpc(loc, actorName) ?: return@runWhenLoaded
            registerAndSpawn(created)
            // Ensure the NPC immediately reflects the current mayor after a spawn.
            plugin.mayorNpc.forceUpdateMayor()
        }
    }

    override fun remove() {
        val npc = getNpc() ?: return
        runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "removeForAll" && it.parameterCount == 0 }?.invoke(npc)
            val manager = npcManager() ?: return@runCatching
            // Docs: removeNpc(Npc)
            manager.javaClass.methods.firstOrNull { it.name == "removeNpc" && it.parameterCount == 1 }?.invoke(manager, npc)
        }
    }

    override fun updateMayor(identity: MayorNpcIdentity?) {
        // FancyNpcs may not be loaded yet when we receive updates (especially right after startup).
        // Queue the update so we don't get stuck showing "No mayor" until a manual refresh.
        runWhenLoaded {
            val npc = getNpc()
            if (npc == null) {
                // If the NPC isn't created yet but is enabled, attempt to spawn at the stored location.
                if (plugin.config.getBoolean("npc.mayor.enabled", false)) {
                    val loc = readLocationFromConfig()
                    if (loc != null) spawnOrMove(loc, actorName = null)
                }
                return@runWhenLoaded
            }
            val prevIdentityUuid = lastIdentityUuid
            val identityChanged = prevIdentityUuid != identity?.uuid
            lastIdentityUuid = identity?.uuid

            runCatching {
                val data = npc.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterCount == 0 }?.invoke(npc) ?: return@runCatching

                val fallbackSkin = plugin.config.getString("npc.mayor.default_skin")?.takeIf { it.isNotBlank() } ?: "Steve"

                if (identity == null) {
                    // Default appearance when there is no elected mayor.
                    data.javaClass.methods.firstOrNull { it.name == "setSkin" && it.parameterCount == 1 }?.invoke(data, fallbackSkin)
                        ?: data.javaClass.methods.firstOrNull { it.name == "setSkin" && it.parameterCount == 2 }?.let { m ->
                            m.invoke(data, fallbackSkin, null)
                        }

                    val noMayor = npcNoMayorMini()
                    data.javaClass.methods.firstOrNull { it.name == "setDisplayName" && it.parameterCount == 1 }
                        ?.invoke(data, noMayor)
                } else {
                    // Skin: prefer username for widest FancyNpcs compatibility.
                    val skinKey = identity.lastKnownName?.takeIf { it.isNotBlank() }
                        ?: Bukkit.getOfflinePlayer(identity.uuid).name
                        ?: identity.uuid.toString()

                    data.javaClass.methods.firstOrNull { it.name == "setSkin" && it.parameterCount == 1 }?.invoke(data, skinKey)
                        ?: data.javaClass.methods.firstOrNull { it.name == "setSkin" && it.parameterCount == 2 }?.let { m ->
                            // variant version: call with default by passing null (best-effort)
                            m.invoke(data, skinKey, null)
                        }

                    val title = identity.titleMini.trimEnd()
                    val titlePlain = miniToPlain(title)
                    val alreadyHasTitle = hasLeadingPrefix(identity.displayNamePlain, titlePlain)
                    val name = componentToMini(identity.displayName)
                        .ifBlank { "<yellow>${escapeMiniMessage(identity.displayNamePlain)}</yellow>" }
                    val display = if (title.isBlank() || alreadyHasTitle) name else "$title $name"
                    data.javaClass.methods.firstOrNull { it.name == "setDisplayName" && it.parameterCount == 1 }?.invoke(data, display)
                }

                npc.javaClass.methods.firstOrNull { it.name == "updateForAll" && it.parameterCount == 0 }?.invoke(npc)
            }

            if (identityChanged) {
                scheduleViewerRefresh(npc, identity)
            }
        }
    }


    private fun scheduleViewerRefresh(npc: Any, identity: MayorNpcIdentity?) {
        val key = identity?.uuid?.toString() ?: "__no_mayor__"
        if (hardRefreshTaskId != -1 && hardRefreshKey == key) return

        if (hardRefreshTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(hardRefreshTaskId) }
            hardRefreshTaskId = -1
        }
        hardRefreshKey = key

        // Delay a bit so any async skin/profile fetch in FancyNpcs has time to complete.
        hardRefreshTaskId = plugin.server.scheduler.scheduleSyncDelayedTask(plugin, plugin.loggedTask("fancynpcs viewer refresh") {
            hardRefreshTaskId = -1

            // 1) Soft refresh variants
            tryInvoke0(npc, listOf("updateForAll", "refreshForAll", "respawnForAll"))

            // 2) Hard refresh (most reliable): despawn + spawn
            val removed = tryInvoke0(npc, listOf("removeForAll", "despawnForAll", "hideForAll"))
            val spawned = tryInvoke0(npc, listOf("spawnForAll", "showForAll"))
            if (removed || spawned) {
                tryInvoke0(npc, listOf("updateForAll"))
            }
        }, 20L)
    }

    private fun tryInvoke0(target: Any, names: List<String>): Boolean {
        for (name in names) {
            val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
            val ok = runCatching { m.invoke(target); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun cancelHardRefreshTask() {
        if (hardRefreshTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(hardRefreshTaskId) }
            hardRefreshTaskId = -1
            hardRefreshKey = null
        }
    }


    override fun isMayorNpc(entity: Entity): Boolean {
        // FancyNpcs are packet-based; Bukkit will not give us a real entity to match.
        return false
    }

    override fun restoreFromConfig() {
        val enabled = plugin.config.getBoolean("npc.mayor.enabled", false)
        if (!enabled) return
        val loc = readLocationFromConfig() ?: return
        spawnOrMove(loc, actorName = null)
    }

    private fun runWhenLoaded(task: () -> Unit) {
        // Follow FancyNpcs guidance:
        // - wait for NpcsLoadedEvent OR
        // - wait ~10 seconds before registering NPCs
        if (npcsLoaded) {
            plugin.server.scheduler.runTask(plugin, plugin.loggedTask("fancynpcs loaded callback") { task() })
            return
        }

        pending += task

        scheduleLoadedFallback()
    }

    private fun scheduleLoadedFallback() {
        if (loadedFallbackTaskId != -1) return
        loadedFallbackTaskId = plugin.server.scheduler.scheduleSyncDelayedTask(plugin, plugin.loggedTask("fancynpcs loaded fallback") {
            loadedFallbackTaskId = -1
            markLoaded()
        }, 200L)
    }

    private fun cancelLoadedFallback() {
        if (loadedFallbackTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(loadedFallbackTaskId) }
            loadedFallbackTaskId = -1
        }
    }

    private fun markLoaded() {
        if (npcsLoaded) return
        npcsLoaded = true
        cancelLoadedFallback()
        val tasks = pending.toList()
        pending.clear()
        tasks.forEach { it() }
    }

    private fun registerLoadedEvent() {
        if (loadedListener != null) return
        val eventClass = runCatching {
            @Suppress("UNCHECKED_CAST")
            Class.forName("de.oliver.fancynpcs.api.events.NpcsLoadedEvent") as Class<out Event>
        }.getOrNull() ?: return

        val listener = object : Listener {}
        val executor = EventExecutor { _, _ ->
            plugin.server.scheduler.runTask(plugin, plugin.loggedTask("fancynpcs loaded event") { markLoaded() })
        }

        try {
            plugin.server.pluginManager.registerEvent(eventClass, listener, EventPriority.NORMAL, executor, plugin, true)
            loadedListener = listener
        } catch (_: EventException) {
            // Ignore; fallback delay will still apply.
        } catch (_: Throwable) {
            // Ignore; fallback delay will still apply.
        }
    }

    private fun tryInvoke1(target: Any, names: List<String>, arg: Any): Boolean {
        val argClass = arg.javaClass
        for (name in names) {
            val methods = target.javaClass.methods.filter { it.name == name && it.parameterCount == 1 }
            if (methods.isEmpty()) continue
            for (m in methods) {
                val param = m.parameterTypes[0]
                if (!paramAccepts(param, argClass)) continue
                val ok = runCatching { m.invoke(target, coerceArg(param, arg)); true }.getOrDefault(false)
                if (ok) return true
            }
        }
        return false
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

    private fun fancyPlugin(): Any? {
        val cls = Class.forName("de.oliver.fancynpcs.api.FancyNpcsPlugin")
        return cls.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }?.invoke(null)
    }

    private fun npcManager(): Any? {
        val p = fancyPlugin() ?: return null
        return p.javaClass.methods.firstOrNull { it.name == "getNpcManager" && it.parameterCount == 0 }?.invoke(p)
    }

    private fun getNpc(): Any? {
        val manager = npcManager() ?: return null

        val storedId = plugin.config.getString("npc.mayor.fancynpcs.npc_id")
        val creatorUuid = plugin.config.getString("npc.mayor.creator_uuid")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        val byName = manager.javaClass.methods.firstOrNull {
            it.name == "getNpc" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        val byId = manager.javaClass.methods.firstOrNull {
            it.name == "getNpcById" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        val byNameCreator = manager.javaClass.methods.firstOrNull {
            it.name == "getNpc" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == UUID::class.java
        }

        val byNameResult = byName?.let { runCatching { it.invoke(manager, npcName) }.getOrNull() }
        if (byNameResult != null) {
            val unwrapped = unwrapOptional(byNameResult)
            if (unwrapped != null) return unwrapped
        }

        if (!storedId.isNullOrBlank() && storedId != npcName) {
            val byIdResult = byId?.let { runCatching { it.invoke(manager, storedId) }.getOrNull() }
            if (byIdResult != null) {
                val unwrapped = unwrapOptional(byIdResult)
                if (unwrapped != null) return unwrapped
            }
        }

        if (creatorUuid != null) {
            val byNameCreatorResult = byNameCreator?.let { runCatching { it.invoke(manager, npcName, creatorUuid) }.getOrNull() }
            if (byNameCreatorResult != null) {
                val unwrapped = unwrapOptional(byNameCreatorResult)
                if (unwrapped != null) return unwrapped
            }
        }

        return null
    }

    private fun paramAccepts(param: Class<*>, arg: Class<*>): Boolean {
        if (param.isAssignableFrom(arg)) return true

        // Handle primitive parameters (int/long/etc.) when we have boxed args.
        if (!param.isPrimitive) return false
        val boxed = when (param) {
	            Int::class.javaPrimitiveType -> Int::class.javaObjectType
	            Long::class.javaPrimitiveType -> Long::class.javaObjectType
	            Double::class.javaPrimitiveType -> Double::class.javaObjectType
	            Float::class.javaPrimitiveType -> Float::class.javaObjectType
	            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
	            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
	            Short::class.javaPrimitiveType -> Short::class.javaObjectType
	            Char::class.javaPrimitiveType -> Char::class.javaObjectType
	            else -> null
	        } ?: return false

        return boxed.isAssignableFrom(arg)
    }

    private fun coerceArg(param: Class<*>, arg: Any): Any {
        // If the method expects a primitive, make sure we pass the correct boxed type.
        if (!param.isPrimitive) return arg
        return when (param) {
            java.lang.Integer.TYPE -> (arg as Number).toInt()
            java.lang.Long.TYPE -> (arg as Number).toLong()
            java.lang.Double.TYPE -> (arg as Number).toDouble()
            java.lang.Float.TYPE -> (arg as Number).toFloat()
            else -> arg
        }
    }

    private fun unwrapOptional(value: Any?): Any? {
        if (value == null) return null
        // Avoid linking Optional directly; use reflection so this stays shading-safe.
        if (value.javaClass.name != "java.util.Optional") return value
        return runCatching {
            val isPresent = value.javaClass.methods.firstOrNull { it.name == "isPresent" && it.parameterCount == 0 }?.invoke(value) as? Boolean
            if (isPresent != true) return@runCatching null
            value.javaClass.methods.firstOrNull { it.name == "get" && it.parameterCount == 0 }?.invoke(value)
        }.getOrNull()
    }

    private fun createNpc(loc: Location, actorName: String?): Any? {
        val creator = actorName?.let { Bukkit.getPlayerExact(it)?.uniqueId }
            ?: run {
                val stored = plugin.config.getString("npc.mayor.creator_uuid")
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (stored != null) stored else UUID.nameUUIDFromBytes("mayorsystem-npc".toByteArray())
            }

        if (!plugin.config.contains("npc.mayor.creator_uuid")) {
            plugin.config.set("npc.mayor.creator_uuid", creator.toString())
            plugin.saveConfig()
        }

        val dataCls = Class.forName("de.oliver.fancynpcs.api.NpcData")
        val ctor = dataCls.constructors.firstOrNull { it.parameterCount == 3 } ?: return null
        val data = ctor.newInstance(npcName, creator, loc)

        // Basic defaults: no tablist, turn to player, click opens mayor card.
        runCatching {
            dataCls.methods.firstOrNull { it.name == "setShowInTab" && it.parameterCount == 1 }?.invoke(data, false)
            dataCls.methods.firstOrNull { it.name == "setTurnToPlayer" && it.parameterCount == 1 }?.invoke(data, true)
            dataCls.methods.firstOrNull { it.name == "setTurnToPlayerDistance" && it.parameterCount == 1 }?.invoke(data, 8)
            dataCls.methods.firstOrNull { it.name == "setCollidable" && it.parameterCount == 1 }?.invoke(data, false)
            dataCls.methods.firstOrNull { it.name == "setInteractionCooldown" && it.parameterCount == 1 }?.invoke(data, 0.5f)

            // Best-effort: ensure this NPC is a PLAYER-type NPC (API varies by FancyNpcs version).
            runCatching {
                val typeMethod = dataCls.methods.firstOrNull {
                    it.parameterCount == 1 && it.name in setOf("setNpcType", "setType", "setNpcType", "setEntityKind")
                }
                if (typeMethod != null) {
                    val param = typeMethod.parameterTypes[0]
                    if (param.isEnum) {
                        val playerConst = param.enumConstants.firstOrNull { (it as Enum<*>).name.equals("PLAYER", ignoreCase = true) }
                        if (playerConst != null) typeMethod.invoke(data, playerConst)
                    }
                }
            }

            // Default display / skin (will be updated by updateMayor tick)
            dataCls.methods.firstOrNull { it.name == "setDisplayName" && it.parameterCount == 1 }
                ?.invoke(data, npcTitleMini())
            val fallbackSkin = plugin.config.getString("npc.mayor.default_skin")?.takeIf { it.isNotBlank() } ?: "Steve"
            dataCls.methods.firstOrNull { it.name == "setSkin" && it.parameterCount == 1 }?.invoke(data, fallbackSkin)

            val onClick = Consumer<Player> { p -> plugin.mayorNpc.openMayorCard(p) }
            dataCls.methods.firstOrNull { it.name == "setOnClick" && it.parameterCount == 1 }?.invoke(data, onClick)
        }

        // Build NPC from adapter
        val fancy = fancyPlugin() ?: return null
        val adapter = fancy.javaClass.methods.firstOrNull { it.name == "getNpcAdapter" && it.parameterCount == 0 }?.invoke(fancy) ?: return null

        // FancyNpcs returns a lambda-backed Function in some versions.
        // Invoking the "apply" method via reflection on the lambda class can throw IllegalAccessException
        // because the lambda class itself is not public. Prefer calling through the public Function interface.
        val npc = runCatching {
            @Suppress("UNCHECKED_CAST")
            (adapter as? java.util.function.Function<Any?, Any?>)?.apply(data)
        }.getOrNull() ?: runCatching {
            val apply = java.util.function.Function::class.java.getMethod("apply", Any::class.java)
            apply.invoke(adapter, data)
        }.getOrNull() ?: return null

        // Do not persist in FancyNpcs files; we re-create from our config.
        runCatching {
            npc.javaClass.methods.firstOrNull { it.name == "setSaveToFile" && it.parameterCount == 1 }?.invoke(npc, false)
        }

        return npc
    }

    private fun registerAndSpawn(npc: Any) {
        runCatching {
            val manager = npcManager() ?: return@runCatching
            // Pick the overload that matches the NPC instance type.
            findSingleArgMethod(manager.javaClass, "registerNpc", npc)?.invoke(manager, npc)

            // Store ids (best-effort) to support FancyNpcs versions that only provide UUID/Int/etc lookup.
            rememberNpcLookupKeys(npc)

            npc.javaClass.methods.firstOrNull { it.name == "create" && it.parameterCount == 0 }?.invoke(npc)
            npc.javaClass.methods.firstOrNull { it.name == "spawnForAll" && it.parameterCount == 0 }?.invoke(npc)
        }
    }

    private fun rememberNpcLookupKeys(npc: Any) {
        var changed = false
        val data = npc.javaClass.methods.firstOrNull { it.name == "getData" && it.parameterCount == 0 }?.invoke(npc)
        if (data != null) {
            val idMethod = data.javaClass.methods.firstOrNull { it.name == "getId" && it.parameterCount == 0 }
            val rawId = idMethod?.let { runCatching { it.invoke(data) }.getOrNull() }
            val id = rawId as? String
            if (!id.isNullOrBlank()) {
                plugin.config.set("npc.mayor.fancynpcs.npc_id", id)
                changed = true
            }

            val nameMethod = data.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
            val rawName = nameMethod?.let { runCatching { it.invoke(data) }.getOrNull() } as? String
            if (!rawName.isNullOrBlank() && rawName != npcName) {
                plugin.config.set("npc.mayor.fancynpcs.npc_id", rawName)
                changed = true
            }
        }

        if (changed) plugin.saveConfig()
    }

    private fun findSingleArgMethod(owner: Class<*>, name: String, arg: Any): Method? {
        val argClass = arg.javaClass
        return owner.methods
            .asSequence()
            .filter { it.name == name && it.parameterCount == 1 }
            .firstOrNull { paramAccepts(it.parameterTypes[0], argClass) }
    }

    private fun escapeMiniMessage(s: String): String {
        // Escape < and > so ranks like "<Admin>" don't nuke formatting.
        return s.replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun miniToPlain(raw: String): String {
        if (raw.isBlank()) return ""
        val component = runCatching { mini.deserialize(raw) }.getOrElse { Component.text(raw) }
        return plain.serialize(component).trim()
    }

    private fun componentToMini(component: Component): String {
        return runCatching { mini.serialize(component) }.getOrDefault("")
    }

    private fun hasLeadingPrefix(text: String, prefix: String): Boolean {
        val normalizedText = normalizeForPrefixCompare(text)
        val normalizedPrefix = normalizeForPrefixCompare(prefix)
        if (normalizedText.isBlank() || normalizedPrefix.isBlank()) return false
        return normalizedText == normalizedPrefix || normalizedText.startsWith("$normalizedPrefix ")
    }

    private fun normalizeForPrefixCompare(raw: String): String {
        return raw.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun npcTitleMini(): String {
        val raw = plugin.messages.get("npc.title")?.trim()
        return if (raw.isNullOrBlank()) "<gold>${plugin.settings.titleName}</gold>" else raw
    }

    private fun npcNoMayorMini(): String {
        val raw = plugin.messages.get("npc.no_mayor")?.trim()
        return if (raw.isNullOrBlank()) "<gray>No ${plugin.settings.titleNameLower()}</gray>" else raw
    }
}

