package mayorSystem.platform.paper.command

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.plugin.Plugin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandAliasSafetyTest {
    @BeforeTest
    fun setUp() = mockkStatic(Bukkit::class)

    @AfterTest
    fun tearDown() = unmockkStatic(Bukkit::class)

    @Test
    fun `cloud alias owned by MayorSystem remains allowed`() {
        val plugin = mockk<Plugin>()
        val commandMap = mockk<CommandMap>()
        val command = OwnedCommand(plugin)
        every { plugin.name } returns "MayorSystem"
        every { Bukkit.isPrimaryThread() } returns true
        every { Bukkit.getPluginCommand("emperor") } returns null
        every { Bukkit.getCommandMap() } returns commandMap
        every { commandMap.getCommand("emperor") } returns command

        assertNull(CommandAliasSafety.blockedReason(plugin, "emperor"))
    }

    @Test
    fun `foreign command map entry still blocks dynamic alias`() {
        val plugin = mockk<Plugin>()
        val commandMap = mockk<CommandMap>()
        every { plugin.name } returns "MayorSystem"
        every { Bukkit.isPrimaryThread() } returns true
        every { Bukkit.getPluginCommand("emperor") } returns null
        every { Bukkit.getCommandMap() } returns commandMap
        every { commandMap.getCommand("emperor") } returns mockk()
        every { commandMap.getCommand("mayorsystem:emperor") } returns null

        assertEquals("already registered by the server command map", CommandAliasSafety.blockedReason(plugin, "emperor"))
    }

    @Test
    fun `foreign plugin identifiable command still blocks dynamic alias`() {
        val plugin = mockk<Plugin>()
        val foreignPlugin = mockk<Plugin>()
        val commandMap = mockk<CommandMap>()
        every { plugin.name } returns "MayorSystem"
        every { foreignPlugin.name } returns "OtherPlugin"
        every { Bukkit.isPrimaryThread() } returns true
        every { Bukkit.getPluginCommand("emperor") } returns null
        every { Bukkit.getCommandMap() } returns commandMap
        every { commandMap.getCommand("emperor") } returns OwnedCommand(foreignPlugin)

        assertEquals("already registered by plugin OtherPlugin", CommandAliasSafety.blockedReason(plugin, "emperor"))
    }

    private class OwnedCommand(private val owner: Plugin) : Command("emperor"), PluginIdentifiableCommand {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean = true

        override fun getPlugin(): Plugin = owner
    }
}
