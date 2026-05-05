package mayorSystem.util

import kotlinx.coroutines.CoroutineDispatcher
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine dispatcher that always runs on the Bukkit main thread.
 */
class PaperMainDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (Bukkit.isPrimaryThread()) {
            block.run()
        } else {
            Bukkit.getScheduler().runTask(plugin, block)
        }
    }
}

