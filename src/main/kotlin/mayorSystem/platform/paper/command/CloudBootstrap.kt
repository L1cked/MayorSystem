package mayorSystem.platform.paper.command

import mayorSystem.MayorPlugin
import org.incendo.cloud.exception.InvalidCommandSenderException
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
            .also { registerExceptionHandlers(plugin, it) }
    }

    fun enable(plugin: MayorPlugin) {
        val manager = createManager(plugin)
        MayorCommands(plugin, manager).register()
    }

    private fun registerExceptionHandlers(plugin: MayorPlugin, manager: PaperCommandManager<Source>) {
        manager.exceptionController().registerHandler(InvalidCommandSenderException::class.java) { context ->
            plugin.messages.msg(context.context().sender().source(), "errors.player_only")
        }
    }
}

