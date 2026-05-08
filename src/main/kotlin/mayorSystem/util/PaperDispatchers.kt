package mayorSystem.util

import kotlinx.coroutines.CoroutineDispatcher
import org.bukkit.Bukkit
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine dispatcher that always runs on the Bukkit main thread.
 */
class PaperMainDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!plugin.isEnabled) {
            throw RejectedExecutionException("Cannot dispatch Bukkit main-thread coroutine because ${plugin.name} is disabled.")
        }
        try {
            Bukkit.getScheduler().runTask(plugin, block)
        } catch (ex: IllegalPluginAccessException) {
            throw RejectedExecutionException(
                "Bukkit scheduler rejected main-thread coroutine dispatch for ${plugin.name}.",
                ex
            )
        }
    }
}
