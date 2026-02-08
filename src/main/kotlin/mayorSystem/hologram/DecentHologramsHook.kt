package mayorSystem.hologram

import mayorSystem.MayorPlugin
import org.bukkit.Location
import java.lang.reflect.Method

class DecentHologramsHook(private val plugin: MayorPlugin) {

    private val apiClass: Class<*>? = runCatching {
        Class.forName("eu.decentsoftware.holograms.api.DHAPI")
    }.getOrNull()

    private val hologramClass: Class<*>? = runCatching {
        Class.forName("eu.decentsoftware.holograms.api.holograms.Hologram")
    }.getOrNull()

    private val createHologram: Method? = apiClass?.getMethod(
        "createHologram",
        String::class.java,
        Location::class.java,
        Boolean::class.javaPrimitiveType,
        List::class.java
    )

    private val getHologram: Method? = apiClass?.getMethod("getHologram", String::class.java)

    private val removeHologram: Method? = apiClass?.getMethod("removeHologram", String::class.java)

    private val moveHologram: Method? = apiClass?.getMethod("moveHologram", String::class.java, Location::class.java)

    private val setHologramLines: Method? = if (apiClass != null && hologramClass != null) {
        apiClass.getMethod("setHologramLines", hologramClass, List::class.java)
    } else {
        null
    }


    fun isAvailable(): Boolean {
        val pluginEnabled = plugin.server.pluginManager.getPlugin("DecentHolograms")?.isEnabled == true
        return pluginEnabled && apiClass != null
    }

    fun get(name: String): Any? {
        val m = getHologram ?: return null
        return runCatching { m.invoke(null, name) }.getOrNull()
    }

    fun create(name: String, loc: Location, persistent: Boolean, lines: List<String>): Any? {
        val m = createHologram ?: return null
        return runCatching { m.invoke(null, name, loc, persistent, lines) }.getOrNull()
    }

    fun move(name: String, loc: Location) {
        val m = moveHologram ?: return
        runCatching { m.invoke(null, name, loc) }
    }

    fun setLines(hologram: Any, lines: List<String>) {
        val m = setHologramLines ?: return
        runCatching { m.invoke(null, hologram, lines) }
    }

    fun remove(name: String) {
        val m = removeHologram ?: return
        runCatching { m.invoke(null, name) }
    }
}
