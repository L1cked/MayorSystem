package mayorSystem.cloud

import mayorSystem.MayorPlugin
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.PaperCommandManager
import org.incendo.cloud.paper.util.sender.PaperSimpleSenderMapper
import org.incendo.cloud.paper.util.sender.Source

object CloudBootstrap {

    fun createManager(plugin: MayorPlugin): PaperCommandManager<Source> {
        return PaperCommandManager
            .builder<Source>(PaperSimpleSenderMapper.simpleSenderMapper())
            .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
            .buildOnEnable(plugin)
    }

    fun enable(plugin: MayorPlugin) {
        val manager = createManager(plugin)
        MayorCommands(plugin, manager).register()
    }
}
