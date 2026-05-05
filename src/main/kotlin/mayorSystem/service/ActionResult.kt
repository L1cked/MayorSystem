package mayorSystem.service

import mayorSystem.MayorPlugin
import org.bukkit.command.CommandSender

sealed class ActionResult(
    open val key: String,
    open val placeholders: Map<String, String> = emptyMap()
) {
    data class Success(
        override val key: String,
        override val placeholders: Map<String, String> = emptyMap()
    ) : ActionResult(key, placeholders)

    data class Failure(
        override val key: String = "errors.action_failed",
        override val placeholders: Map<String, String> = emptyMap()
    ) : ActionResult(key, placeholders)

    data class Rejected(
        override val key: String,
        override val placeholders: Map<String, String> = emptyMap()
    ) : ActionResult(key, placeholders)

    val isSuccess: Boolean
        get() = this is Success

    fun send(plugin: MayorPlugin, sender: CommandSender) {
        plugin.messages.msg(sender, key, placeholders)
    }
}
