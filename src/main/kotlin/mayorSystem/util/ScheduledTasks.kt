package mayorSystem.util

import org.bukkit.plugin.Plugin
import java.util.logging.Level

inline fun Plugin.loggedTask(taskName: String, crossinline block: () -> Unit): Runnable =
    Runnable {
        try {
            block()
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "[MayorSystem] Scheduled task failed: $taskName", t)
        }
    }
