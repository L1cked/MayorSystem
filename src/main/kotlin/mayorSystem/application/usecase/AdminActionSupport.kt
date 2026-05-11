package mayorSystem.application.usecase

import kotlinx.coroutines.withContext
import mayorSystem.MayorPlugin
import mayorSystem.service.ActionResult
import org.bukkit.entity.Player
import java.util.logging.Level

internal class AdminActionSupport(private val plugin: MayorPlugin) {
    fun requirePerms(actor: Player?, vararg perms: String): ActionResult? {
        if (actor == null) return null
        return if (perms.any { actor.hasPermission(it) }) {
            null
        } else {
            ActionResult.Rejected("errors.no_permission")
        }
    }

    suspend fun serializedResult(key: String, block: suspend () -> ActionResult): ActionResult =
        plugin.actionCoordinator.trySerialized(key, block)
            ?: ActionResult.Rejected("errors.action_in_progress")

    suspend fun <T> onMain(block: () -> T): T =
        withContext(plugin.mainDispatcher) { block() }

    suspend fun updateConfig(actor: Player?, path: String, value: Any?, reload: Boolean = true) {
        onMain {
            val prev = plugin.config.get(path)?.toString()
            plugin.config.set(path, value)
            plugin.saveConfig()
            log(
                actor,
                "CONFIG_SET",
                details = mapOf(
                    "path" to path,
                    "from" to (prev ?: "<null>"),
                    "to" to (value?.toString() ?: "<null>"),
                    "reload" to reload.toString()
                )
            )
            if (reload) plugin.reloadEverything()
        }
    }

    suspend fun saveConfigValue(path: String, value: Any?) {
        onMain {
            plugin.config.set(path, value)
            plugin.saveConfig()
        }
    }

    suspend fun saveConfigValues(values: Map<String, Any?>) {
        onMain {
            values.forEach { (path, value) ->
                plugin.config.set(path, value)
            }
            plugin.saveConfig()
        }
    }

    fun log(
        actor: Player?,
        action: String,
        term: Int? = null,
        target: String? = null,
        details: Map<String, String> = emptyMap()
    ) {
        plugin.audit.log(
            actorUuid = actor?.uniqueId?.toString(),
            actorName = actor?.name ?: "CONSOLE",
            action = action,
            term = term,
            target = target,
            details = details
        )
    }

    fun logFailure(context: String, ex: Throwable) {
        plugin.logger.log(Level.SEVERE, context, ex)
    }
}
