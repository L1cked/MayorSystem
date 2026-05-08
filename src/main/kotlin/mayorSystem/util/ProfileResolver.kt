package mayorSystem.util

import mayorSystem.MayorPlugin
import org.bukkit.Bukkit
import org.bukkit.plugin.IllegalPluginAccessException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.logging.Level

object ProfileResolver {
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
        fun fallbackUuid(): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$cleanName".toByteArray(Charsets.UTF_8))
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
        fun fail(message: String, uuid: UUID = fallbackUuid(), throwable: Throwable? = null) {
            if (throwable == null) {
                plugin.logger.warning(message)
            } else {
                plugin.logger.log(Level.WARNING, message, throwable)
            }
            runOnMain {
                onError?.invoke(message)
                callback(uuid, cleanName)
            }
        }

        if (cleanName.isBlank()) {
            runOnMain { onError?.invoke("Player name cannot be blank.") }
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

        val profile = runCatching { plugin.server.createProfile(cleanName) }.getOrElse {
            fail("Profile lookup for '$cleanName' could not create a profile: ${it.message}; using offline fallback.")
            return
        }
        profile.update()
            .thenAcceptAsync({ updated ->
                val uuid = updated.id
                val resolvedName = updated.name ?: cleanName
                if (uuid == null) {
                    fail("Profile lookup for '$cleanName' returned no UUID; using offline fallback.", fallbackUuid())
                } else {
                    complete(uuid, resolvedName)
                }
            }, mainThreadExecutor)
            .exceptionally { err ->
                val cause = err.cause ?: err
                fail(
                    "Profile lookup failed for '$cleanName': ${cause.message}; using offline fallback.",
                    throwable = cause
                )
                null
            }
    }
}
