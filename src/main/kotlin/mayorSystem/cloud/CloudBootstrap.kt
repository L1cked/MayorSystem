package mayorSystem.cloud

import mayorSystem.MayorPlugin
import org.bukkit.command.CommandSender
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.LegacyPaperCommandManager

object CloudBootstrap {

    fun createManager(plugin: MayorPlugin): LegacyPaperCommandManager<CommandSender> {
        return LegacyPaperCommandManager.createNative(
            plugin,
            ExecutionCoordinator.simpleCoordinator()
        )
    }

    fun enable(plugin: MayorPlugin) {
        val manager = createManager(plugin)
        MayorCommands(plugin, manager).register()
    }
}

