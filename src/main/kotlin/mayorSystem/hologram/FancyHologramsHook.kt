package mayorSystem.hologram

import mayorSystem.MayorPlugin
import org.bukkit.Location
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Optional

class FancyHologramsHook : LeaderboardHologramProvider {

    override val id: String = "fancyholograms"
    override val pluginNames: Set<String> = setOf("FancyHolograms")

    private val apiClass: Class<*>? = runCatching {
        Class.forName("de.oliver.fancyholograms.api.FancyHologramsPlugin")
    }.getOrNull()

    private val managerClass: Class<*>? = runCatching {
        Class.forName("de.oliver.fancyholograms.api.HologramManager")
    }.getOrNull()

    private val hologramClass: Class<*>? = runCatching {
        Class.forName("de.oliver.fancyholograms.api.hologram.Hologram")
    }.getOrNull()

    private val hologramDataClass: Class<*>? = runCatching {
        Class.forName("de.oliver.fancyholograms.api.data.HologramData")
    }.getOrNull()

    private val textDataClass: Class<*>? = runCatching {
        Class.forName("de.oliver.fancyholograms.api.data.TextHologramData")
    }.getOrNull()

    private val getApi: Method? = apiClass?.getMethod("get")
    private val getManager: Method? = apiClass?.getMethod("getHologramManager")
    private val getHologram: Method? = managerClass?.getMethod("getHologram", String::class.java)
    private val createHologram: Method? = if (managerClass != null && hologramDataClass != null) {
        managerClass.getMethod("create", hologramDataClass)
    } else {
        null
    }
    private val addHologram: Method? = if (managerClass != null && hologramClass != null) {
        managerClass.getMethod("addHologram", hologramClass)
    } else {
        null
    }
    private val removeHologram: Method? = if (managerClass != null && hologramClass != null) {
        managerClass.getMethod("removeHologram", hologramClass)
    } else {
        null
    }
    private val textDataCtor: Constructor<*>? = textDataClass?.getConstructor(String::class.java, Location::class.java)
    private val setText: Method? = textDataClass?.getMethod("setText", List::class.java)
    private val setPersistent: Method? = hologramDataClass?.getMethod("setPersistent", Boolean::class.javaPrimitiveType)
    private val getData: Method? = hologramClass?.getMethod("getData")
    private val setLocation: Method? = hologramDataClass?.getMethod("setLocation", Location::class.java)
    private val forceUpdate: Method? = hologramClass?.getMethod("forceUpdate")

    override fun isAvailable(plugin: MayorPlugin): Boolean {
        val pluginEnabled = plugin.server.pluginManager.getPlugin("FancyHolograms")?.isEnabled == true
        return pluginEnabled &&
            apiClass != null &&
            managerClass != null &&
            hologramClass != null &&
            hologramDataClass != null &&
            textDataClass != null &&
            getApi != null &&
            getManager != null
    }

    override fun formatLines(lines: List<String>): List<String> =
        lines.map { if (it.isBlank()) " " else it }

    override fun get(name: String): Any? {
        val manager = managerInstance() ?: return null
        val method = getHologram ?: return null
        val optional = runCatching { method.invoke(manager, name) }.getOrNull() as? Optional<*>
        return optional?.orElse(null)
    }

    override fun create(name: String, loc: Location, persistent: Boolean, lines: List<String>): Any? {
        val manager = managerInstance() ?: return null
        val ctor = textDataCtor ?: return null
        val create = createHologram ?: return null
        val setPersistentMethod = setPersistent ?: return null
        val setTextMethod = setText ?: return null

        val data = runCatching { ctor.newInstance(name, loc) }.getOrNull() ?: return null
        runCatching {
            setPersistentMethod.invoke(data, persistent)
            setTextMethod.invoke(data, formatLines(lines))
        }

        val hologram = runCatching { create.invoke(manager, data) }.getOrNull() ?: return null
        if (get(name) == null) {
            addHologram?.let { runCatching { it.invoke(manager, hologram) } }
        }
        forceUpdate?.let { runCatching { it.invoke(hologram) } }
        return hologram
    }

    override fun move(hologram: Any, name: String, loc: Location) {
        val dataMethod = getData ?: return
        val locationMethod = setLocation ?: return
        val updateMethod = forceUpdate ?: return
        val data = runCatching { dataMethod.invoke(hologram) }.getOrNull() ?: return
        runCatching {
            locationMethod.invoke(data, loc)
            updateMethod.invoke(hologram)
        }
    }

    override fun setLines(hologram: Any, lines: List<String>) {
        val dataMethod = getData ?: return
        val updateMethod = forceUpdate ?: return
        val setTextMethod = setText ?: return
        val data = runCatching { dataMethod.invoke(hologram) }.getOrNull() ?: return
        if (textDataClass?.isInstance(data) != true) return
        runCatching {
            setTextMethod.invoke(data, formatLines(lines))
            updateMethod.invoke(hologram)
        }
    }

    override fun remove(name: String) {
        val manager = managerInstance() ?: return
        val hologram = get(name) ?: return
        val remove = removeHologram ?: return
        runCatching { remove.invoke(manager, hologram) }
    }

    private fun managerInstance(): Any? {
        val api = getApi ?: return null
        val manager = getManager ?: return null
        val plugin = runCatching { api.invoke(null) }.getOrNull() ?: return null
        return runCatching { manager.invoke(plugin) }.getOrNull()
    }
}
