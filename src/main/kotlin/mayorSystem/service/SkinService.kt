package mayorSystem.service

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import mayorSystem.MayorPlugin
import mayorSystem.ui.Menu
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SkinService(private val plugin: MayorPlugin) {

    data class SkinRecord(
        val value: String,
        val signature: String?,
        val fetchedAt: Long
    )

    private val gson = Gson()
    private val cacheFile = File(plugin.dataFolder, "skins-cache.json")
    private val cache = ConcurrentHashMap<UUID, SkinRecord>()
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()
    private val refreshByUuid = ConcurrentHashMap<UUID, MutableSet<RefreshKey>>()
    private val reopenQueued = ConcurrentHashMap.newKeySet<RefreshKey>()

    private val maxAgeMs = Duration.ofDays(7).toMillis()

    private val saveDelayTicks = plugin.config.getInt("skins.cache_save_delay_ticks", 60).coerceAtLeast(1)
    private val saveScheduled = AtomicBoolean(false)
    private val savePending = AtomicBoolean(false)

    data class RefreshKey(val viewer: UUID, val menu: Menu)

    init {
        load()
    }

    fun get(uuid: UUID): SkinRecord? = cache[uuid]

    fun isFresh(record: SkinRecord): Boolean =
        System.currentTimeMillis() - record.fetchedAt <= maxAgeMs

    fun request(uuid: UUID, name: String?, viewer: UUID?, menu: Menu?) {
        if (viewer != null && menu != null) {
            val key = RefreshKey(viewer, menu)
            refreshByUuid.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }.add(key)
        }
        if (cache[uuid]?.let { isFresh(it) } == true) return
        if (!inFlight.add(uuid)) return

        plugin.scope.launch {
            try {
                val skin = fetchSkin(uuid) ?: return@launch
                cache[uuid] = skin
                save()
                scheduleReopen(uuid)
            } finally {
                inFlight.remove(uuid)
            }
        }
    }

    private fun scheduleReopen(uuid: UUID) {
        val keys = refreshByUuid.remove(uuid) ?: return
        for (key in keys) {
            if (!reopenQueued.add(key)) continue
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                reopenQueued.remove(key)
                plugin.gui.reopenIfViewing(key.viewer, key.menu)
            }, 2L)
        }
    }

    fun applyToProfile(profile: Any, uuid: UUID): Boolean {
        val record = cache[uuid] ?: return false
        return applyTextureProperty(profile, record.value, record.signature)
    }

    private fun fetchSkin(uuid: UUID): SkinRecord? {
        val uuidNoDashes = uuid.toString().replace("-", "")
        val url = URL("https://sessionserver.mojang.com/session/minecraft/profile/$uuidNoDashes?unsigned=false")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
        }
        return runCatching {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val obj = JsonParser.parseString(body).asJsonObject
            val props = obj.getAsJsonArray("properties") ?: return null
            for (p in props) {
                val name = p.asJsonObject.get("name")?.asString ?: continue
                if (name != "textures") continue
                val value = p.asJsonObject.get("value")?.asString ?: continue
                val signature = p.asJsonObject.get("signature")?.asString
                return SkinRecord(value = value, signature = signature, fetchedAt = System.currentTimeMillis())
            }
            null
        }.getOrNull()
    }

    private fun load() {
        if (!cacheFile.exists()) return
        val raw = runCatching { cacheFile.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return
        val type = object : TypeToken<Map<String, SkinRecord>>() {}.type
        val parsed: Map<String, SkinRecord> = runCatching<Map<String, SkinRecord>> {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(raw, type) as Map<String, SkinRecord>
        }.getOrElse { emptyMap() }
        parsed.forEach { (k, v) ->
            runCatching { UUID.fromString(k) }.getOrNull()?.let { cache[it] = v }
        }
    }

    private fun save() {
        scheduleSave()
    }

    fun flush() {
        val snapshot = snapshotCache()
        writeSnapshot(snapshot)
    }

    private fun scheduleSave() {
        if (!saveScheduled.compareAndSet(false, true)) {
            savePending.set(true)
            return
        }
        plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable {
            try {
                val snapshot = snapshotCache()
                writeSnapshot(snapshot)
            } finally {
                saveScheduled.set(false)
                if (savePending.getAndSet(false)) {
                    scheduleSave()
                }
            }
        }, saveDelayTicks.toLong())
    }

    private fun snapshotCache(): String =
        gson.toJson(cache.mapKeys { it.key.toString() })

    private fun writeSnapshot(snapshot: String) {
        if (!cacheFile.parentFile.exists()) cacheFile.parentFile.mkdirs()
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        Files.writeString(tmp.toPath(), snapshot, StandardCharsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Throwable) {
            Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun applyTextureProperty(profile: Any, value: String, signature: String?): Boolean {
        val propClass = resolvePropertyClass() ?: return false
        val prop = createProperty(propClass, value, signature) ?: return false

        // Try setter/addProperty
        val setter = profile.javaClass.methods.firstOrNull {
            (it.name == "setProperty" || it.name == "addProperty") &&
                it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(propClass)
        }
        if (setter != null) {
            setter.invoke(profile, prop)
            return true
        }

        // Fallback: getProperties().add(property)
        val getter = profile.javaClass.methods.firstOrNull { it.name == "getProperties" && it.parameterCount == 0 }
        val props = getter?.invoke(profile)
        if (props is MutableCollection<*>) {
            @Suppress("UNCHECKED_CAST")
            (props as MutableCollection<Any>).add(prop)
            return true
        }

        return false
    }

    private fun resolvePropertyClass(): Class<*>? {
        val candidates = listOf(
            "org.bukkit.profile.PlayerProfileProperty",
            "org.bukkit.profile.PlayerProfile\$Property",
            "com.destroystokyo.paper.profile.ProfileProperty"
        )
        for (name in candidates) {
            runCatching { return Class.forName(name) }.getOrNull()
        }
        return null
    }

    private fun createProperty(propClass: Class<*>, value: String, signature: String?): Any? {
        val name = "textures"
        // Try (String name, String value, String signature)
        val ctor3 = propClass.constructors.firstOrNull { it.parameterCount == 3 }
        if (ctor3 != null) {
            return runCatching { ctor3.newInstance(name, value, signature) }.getOrNull()
        }
        // Try (String name, String value)
        val ctor2 = propClass.constructors.firstOrNull { it.parameterCount == 2 }
        if (ctor2 != null) {
            return runCatching { ctor2.newInstance(name, value) }.getOrNull()
        }
        return null
    }
}

