package mayorSystem.util

import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import org.bukkit.plugin.IllegalPluginAccessException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.logging.Level

object ProfileResolver {
    private val JAVA_USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")

    fun resolve(
        plugin: MayorPlugin,
        name: String,
        callback: (uuid: UUID, resolvedName: String) -> Unit
    ) {
        resolve(plugin, name, null, callback)
    }

    fun resolve(
        plugin: MayorPlugin,
        name: String,
        onError: ((String) -> Unit)?,
        callback: (uuid: UUID, resolvedName: String) -> Unit
    ) {
        val cleanName = name.trim()
        fun runOnMain(action: () -> Unit) {
            if (!plugin.isEnabled || Bukkit.isPrimaryThread()) {
                action()
                return
            }
            try {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (plugin.isEnabled) action()
                })
            } catch (ex: IllegalPluginAccessException) {
                plugin.logger.log(
                    Level.WARNING,
                    "Profile lookup for '$cleanName' could not return to the main thread; running callback inline.",
                    ex
                )
                action()
            }
        }
        val mainThreadExecutor = Executor { runnable -> runOnMain { runnable.run() } }
        fun complete(uuid: UUID, resolvedName: String) =
            runOnMain { callback(uuid, resolvedName) }
        fun fail(message: String, throwable: Throwable? = null) {
            if (throwable == null) {
                plugin.logger.warning(message)
            } else {
                plugin.logger.log(Level.WARNING, message, throwable)
            }
            runOnMain {
                onError?.invoke(message)
            }
        }

        if (cleanName.isBlank()) {
            fail("Player name cannot be blank.")
            return
        }

        val online = plugin.server.getPlayerExact(cleanName)
        if (online != null) {
            complete(online.uniqueId, online.name)
            return
        }

        val cached = plugin.server.getOfflinePlayerIfCached(cleanName)
        if (cached != null) {
            complete(cached.uniqueId, cached.name ?: cleanName)
            return
        }

        if (!JAVA_USERNAME.matches(cleanName)) {
            fail(
                "Profile lookup for '$cleanName' was rejected: the name is not a valid Java username " +
                    "and no cached server profile exists. Bedrock players must join once before they can be selected."
            )
            return
        }

        val profile = runCatching { plugin.server.createProfile(cleanName) }.getOrElse {
            fail("Profile lookup for '$cleanName' could not create a profile: ${it.message}; player was not selected.")
            return
        }
        profile.update()
            .thenAcceptAsync({ updated ->
                val uuid = updated.id
                val resolvedName = updated.name ?: cleanName
                if (uuid == null) {
                    fail("Profile lookup for '$cleanName' returned no UUID; player was not selected.")
                } else {
                    complete(uuid, resolvedName)
                }
            }, mainThreadExecutor)
            .exceptionally { err ->
                val cause = err.cause ?: err
                fail(
                    "Profile lookup failed for '$cleanName': ${cause.message}; player was not selected.",
                    throwable = cause
                )
                null
            }
    }
}
