package mayorSystem.util

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Method
import java.util.UUID

object BukkitCompat {
    fun pluginVersion(plugin: JavaPlugin): String = plugin.description.version

    fun getOfflinePlayerIfCached(server: Server, name: String): OfflinePlayer? {
        val method = server.javaClass.methods.firstOrNull {
            it.name == "getOfflinePlayerIfCached" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: return null
        return runCatching { method.invoke(server, name) as? OfflinePlayer }.getOrNull()
    }

    fun getOfflinePlayer(server: Server, name: String): OfflinePlayer =
        getOfflinePlayerIfCached(server, name) ?: server.getOfflinePlayer(name)

    fun createPlayerProfile(uuid: UUID?, name: String?): Any? {
        if (uuid != null && name != null) {
            invokeProfileFactory(Bukkit::class.java, null, "createPlayerProfile", uuid, name)?.let { return it }
            invokeProfileFactory(Bukkit::class.java, null, "createProfile", uuid, name)?.let { return it }
            invokeProfileFactory(Bukkit.getServer().javaClass, Bukkit.getServer(), "createPlayerProfile", uuid, name)?.let { return it }
            invokeProfileFactory(Bukkit.getServer().javaClass, Bukkit.getServer(), "createProfile", uuid, name)?.let { return it }
        }
        if (uuid != null) {
            invokeProfileFactory(Bukkit::class.java, null, "createPlayerProfile", uuid)?.let { return it }
            invokeProfileFactory(Bukkit::class.java, null, "createProfile", uuid)?.let { return it }
            invokeProfileFactory(Bukkit.getServer().javaClass, Bukkit.getServer(), "createPlayerProfile", uuid)?.let { return it }
            invokeProfileFactory(Bukkit.getServer().javaClass, Bukkit.getServer(), "createProfile", uuid)?.let { return it }
        }
        if (!name.isNullOrBlank()) {
            invokeProfileFactory(Bukkit::class.java, null, "createPlayerProfile", name)?.let { return it }
            invokeProfileFactory(Bukkit::class.java, null, "createProfile", name)?.let { return it }
            invokeProfileFactory(Bukkit.getServer().javaClass, Bukkit.getServer(), "createPlayerProfile", name)?.let { return it }
            invokeProfileFactory(Bukkit.getServer().javaClass, Bukkit.getServer(), "createProfile", name)?.let { return it }
        }
        return null
    }

    private fun invokeProfileFactory(
        owner: Class<*>,
        target: Any?,
        methodName: String,
        vararg args: Any
    ): Any? {
        val method = findMethod(owner, methodName, args) ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }

    private fun findMethod(owner: Class<*>, methodName: String, args: Array<out Any>): Method? {
        return owner.methods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterCount == args.size &&
                method.parameterTypes.indices.all { idx ->
                    method.parameterTypes[idx].isAssignableFrom(args[idx].javaClass)
                }
        }
    }
}
