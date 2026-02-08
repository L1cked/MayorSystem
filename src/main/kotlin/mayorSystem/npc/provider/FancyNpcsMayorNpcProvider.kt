package mayorSystem.npc.provider

import mayorSystem.MayorPlugin
import mayorSystem.npc.MayorNpcIdentity
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
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
    // We follow the docs by waiting until NpcManager#isLoaded() becomes true.
    private var waitTaskId: Int = -1
    private var warnedNotLoaded: Boolean = false
    private var waitStartedAtMs: Long = 0L
    private val pending = mutableListOf<() -> Unit>()

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
    }

    override fun onDisable() {
        cancelWaitTasks()
        cancelHardRefreshTask()
        runCatching { HandlerList.unregisterAll(this) }
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

            // FancyNpcs has had different overloads here too (removeNpc(Npc) vs removeNpc(String/UUID)).
            // Try the NPC instance first, then fall back to stored ids.
            val storedUuid = plugin.config.getString("npc.mayor.fancynpcs.npc_uuid")
            val uuid = storedUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val storedId = plugin.config.getString("npc.mayor.fancynpcs.npc_id")
            val storedInt = if (plugin.config.contains("npc.mayor.fancynpcs.npc_int")) plugin.config.getInt("npc.mayor.fancynpcs.npc_int") else null
            val storedLong = if (plugin.config.contains("npc.mayor.fancynpcs.npc_long")) plugin.config.getLong("npc.mayor.fancynpcs.npc_long") else null

            val args = buildList<Any> {
                add(npc)
                add(npcName)
                if (!storedId.isNullOrBlank() && storedId != npcName) add(storedId)
                if (uuid != null) add(uuid)
                if (storedInt != null) add(storedInt)
                if (storedLong != null) add(storedLong)
            }

            val removeMethods = manager.javaClass.methods.filter { it.name == "removeNpc" && it.parameterCount == 1 }
            for (arg in args) {
                val argClass = arg.javaClass
                for (m in removeMethods) {
                    val param = m.parameterTypes[0]
                    if (!paramAccepts(param, argClass)) continue
                    try {
                        m.invoke(manager, coerceArg(param, arg))
                        return@runCatching
                    } catch (_: IllegalArgumentException) {
                        continue
                    } catch (_: Throwable) {
                        continue
                    }
                }
            }
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
                    val name = "<yellow>${escapeMiniMessage(identity.displayNamePlain)}</yellow>"
                    val display = if (title.isBlank()) name else "$title $name"
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
        hardRefreshTaskId = plugin.server.scheduler.scheduleSyncDelayedTask(plugin, Runnable {
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
        // - don't register NPCs too early
        // - wait until NPCs are loaded (NpcManager#isLoaded / NpcsLoadedEvent)
        val manager = npcManager()
        val isLoadedMethod = manager?.javaClass?.methods?.firstOrNull { it.name == "isLoaded" && it.parameterCount == 0 }
        val isLoaded = runCatching { (isLoadedMethod?.invoke(manager) as? Boolean) ?: false }
            .getOrDefault(false)

        // Some FancyNpcs builds don't expose isLoaded(). If the manager exists, assume it's ready.
        val readyNow = manager != null && (isLoadedMethod == null || isLoaded)

        if (readyNow) {
            plugin.server.scheduler.runTask(plugin, Runnable { task() })
            return
        }

        pending += task

        if (waitTaskId != -1) return

        // poll until loaded (safe, no direct dependency on the event class)
        warnedNotLoaded = false
        waitStartedAtMs = System.currentTimeMillis()
        waitTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            val mgr = npcManager() ?: return@Runnable
            val loadedNow = runCatching {
                val m = mgr.javaClass.methods.firstOrNull { it.name == "isLoaded" && it.parameterCount == 0 }
                if (m == null) true else (m.invoke(mgr) as? Boolean) ?: false
            }.getOrDefault(false)

            if (!loadedNow) {
                // Do NOT stop polling. FancyNpcs can finish loading well after startup,
                // and we still want the NPC to appear without requiring a manual command.
                if (!warnedNotLoaded && System.currentTimeMillis() - waitStartedAtMs >= 30_000L) {
                    warnedNotLoaded = true
                    plugin.logger.warning("[MayorNPC] FancyNpcs NPCs not loaded after 30s; will keep waiting...")
                }
                return@Runnable
            }

            // ready!
            cancelWaitTasks(keepPending = false)
            val tasks = pending.toList()
            pending.clear()
            tasks.forEach { it() }
        }, 20L, 20L)
    }

    private fun cancelWaitTasks(keepPending: Boolean = false) {
        if (waitTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(waitTaskId) }
            waitTaskId = -1
        }
        if (!keepPending) pending.clear()
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

        // FancyNpcs ships multiple API variants and multiple overloads.
        // We must NOT rely on reflection order (it is undefined), and we must be robust
        // against overloads that accept UUID/Int/etc.

        val storedUuid = plugin.config.getString("npc.mayor.fancynpcs.npc_uuid")
        val storedId = plugin.config.getString("npc.mayor.fancynpcs.npc_id")
        val storedInt = if (plugin.config.contains("npc.mayor.fancynpcs.npc_int")) plugin.config.getInt("npc.mayor.fancynpcs.npc_int") else null
        val storedLong = if (plugin.config.contains("npc.mayor.fancynpcs.npc_long")) plugin.config.getLong("npc.mayor.fancynpcs.npc_long") else null

        val uuid = storedUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        // Prefer our configured name first.
        val args: List<Any> = buildList {
            add(npcName)
            if (!storedId.isNullOrBlank() && storedId != npcName) add(storedId)
            if (uuid != null) add(uuid)
            if (storedInt != null) add(storedInt)
            if (storedLong != null) add(storedLong)
        }

        // Try common method names across versions.
        val methodNames = listOf("getNpc", "getNPC", "getNpcByName", "getNpcFromName", "npc")
        for (name in methodNames) {
            val methods = manager.javaClass.methods.filter { it.name == name && it.parameterCount == 1 }
            if (methods.isEmpty()) continue

            for (arg in args) {
                val argClass = arg.javaClass
                for (m in methods) {
                    val param = m.parameterTypes[0]
                    if (!paramAccepts(param, argClass)) continue

                    val result = try {
                        m.invoke(manager, coerceArg(param, arg))
                    } catch (_: IllegalArgumentException) {
                        // Wrong overload/bridge method; try next.
                        continue
                    } catch (_: Throwable) {
                        continue
                    }

                    val unwrapped = unwrapOptional(result)
                    if (unwrapped != null) return unwrapped
                }
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
        val methods = npc.javaClass.methods

        var changed = false

        val idMethod = methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf("getUniqueId", "getUUID", "getUuid", "getId")
        }
        val rawId = idMethod?.let { runCatching { it.invoke(npc) }.getOrNull() }

        when (rawId) {
            is UUID -> {
                plugin.config.set("npc.mayor.fancynpcs.npc_uuid", rawId.toString())
                changed = true
            }
            is String -> {
                val asUuid = runCatching { UUID.fromString(rawId) }.getOrNull()
                if (asUuid != null) {
                    plugin.config.set("npc.mayor.fancynpcs.npc_uuid", asUuid.toString())
                    changed = true
                } else {
                    plugin.config.set("npc.mayor.fancynpcs.npc_id", rawId)
                    changed = true
                }
            }
            is Int -> {
                plugin.config.set("npc.mayor.fancynpcs.npc_int", rawId)
                changed = true
            }
            is Long -> {
                plugin.config.set("npc.mayor.fancynpcs.npc_long", rawId)
                changed = true
            }
            is Number -> {
                // Unknown numeric width; store as long.
                plugin.config.set("npc.mayor.fancynpcs.npc_long", rawId.toLong())
                changed = true
            }
        }

        // Some variants also expose a String "name" distinct from our configured npcName.
        val nameMethod = methods.firstOrNull { it.parameterCount == 0 && it.returnType == String::class.java && it.name in setOf("getName", "getNpcName") }
        val rawName = nameMethod?.let { runCatching { it.invoke(npc) as? String }.getOrNull() }
        if (!rawName.isNullOrBlank() && rawName != npcName) {
            plugin.config.set("npc.mayor.fancynpcs.npc_id", rawName)
            changed = true
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

    private fun npcTitleMini(): String {
        val raw = plugin.messages.get("npc.title")?.trim()
        return if (raw.isNullOrBlank()) "<gold>Mayor</gold>" else raw
    }

    private fun npcNoMayorMini(): String {
        val raw = plugin.messages.get("npc.no_mayor")?.trim()
        return if (raw.isNullOrBlank()) "<gray>No mayor</gray>" else raw
    }
}

