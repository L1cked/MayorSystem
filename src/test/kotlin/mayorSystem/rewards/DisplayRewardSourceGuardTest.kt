package mayorSystem.rewards

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayRewardSourceGuardTest {

    @Test
    fun `no public reward selection menu was added`() {
        val publicMenus = Path.of("src/main/kotlin/mayorSystem/ui/menus")
        val text = Files.walk(publicMenus)
            .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
            .map { Files.readString(it) }
            .toList()
            .joinToString("\n")

        assertFalse(text.contains("DisplayReward"))
        assertFalse(text.contains("display_reward"))
    }

    @Test
    fun `deluxetags integration has no compile time plugin import`() {
        val integration = Files.readString(Path.of("src/main/kotlin/mayorSystem/rewards/DeluxeTagsIntegration.kt"))

        assertFalse(integration.contains("import me.clip.deluxetags"))
    }

    @Test
    fun `reward flow does not block luckperms futures or dispatch luckperms commands`() {
        val service = Files.readString(Path.of("src/main/kotlin/mayorSystem/service/MayorUsernamePrefixService.kt"))
        val futureFlow = service.replace("LuckPermsProvider.get()", "")

        assertFalse(futureFlow.contains(".join()"))
        assertFalse(futureFlow.contains(".get()"))
        assertFalse(service.contains("dispatchCommand(plugin.server.consoleSender, \"lp"))
        assertFalse(service.contains("dispatchCommand(plugin.server.consoleSender, \"luckperms"))
    }

    @Test
    fun `reward flow uses typed luckperms nodes`() {
        val service = Files.readString(Path.of("src/main/kotlin/mayorSystem/service/MayorUsernamePrefixService.kt"))

        assertTrue(service.contains("InheritanceNode.builder"))
        assertTrue(service.contains("PermissionNode.builder"))
        assertFalse(service.contains("DeluxeTags.GUI"))
        assertFalse(service.contains("DeluxeTags.List"))
        assertFalse(service.contains("DeluxeTags.Select"))
    }

    @Test
    fun `deluxetags commands are not used without a verified path`() {
        val integration = Files.readString(Path.of("src/main/kotlin/mayorSystem/rewards/DeluxeTagsIntegration.kt"))

        assertFalse(integration.contains("dispatchTagsCommand"))
        assertFalse(integration.contains("server.dispatchCommand"))
    }

    @Test
    fun `reward admin commands include target and tag icon paths`() {
        val commands = Files.readString(Path.of("src/main/kotlin/mayorSystem/system/SystemCommands.kt"))

        listOf("open", "list", "remove", "default", "rank", "tag", "icon").forEach {
            assertTrue(commands.contains(".literal(\"$it\")"))
        }
        assertTrue(commands.contains("targetCommand(\"inspect\""))
        assertTrue(commands.contains("targetCommand(\"add\""))
        assertTrue(commands.contains("targetCommand(\"edit\""))
        assertTrue(commands.contains("itemMaterialSuggestions"))
        assertTrue(commands.contains("canSuggestReward"))
    }

    @Test
    fun `no public reward choice command was added`() {
        val commands = Files.readString(Path.of("src/main/kotlin/mayorSystem/system/SystemCommands.kt"))

        assertFalse(commands.contains(".literal(\"reward_choice\")"))
        assertFalse(commands.contains(".literal(\"choose_reward\")"))
    }

    @Test
    fun `deluxetags order is not exposed in normal reward UI commands or health`() {
        val commands = Files.readString(Path.of("src/main/kotlin/mayorSystem/system/SystemCommands.kt"))
        val health = Files.readString(Path.of("src/main/kotlin/mayorSystem/monitoring/HealthService.kt"))
        val gui = Files.readString(Path.of("src/main/resources/gui.yml"))
        val messages = Files.readString(Path.of("src/main/resources/messages.yml"))

        assertFalse(commands.contains(".literal(\"order\")"))
        assertFalse(commands.contains("display_reward.tag.order"))
        assertFalse(health.contains("deluxetags.order"))
        assertFalse(health.contains("orderConflict("))
        assertFalse(gui.contains("tag_order"))
        assertFalse(messages.contains("display_reward_tag_order"))
        assertFalse(messages.contains("display_reward_order_invalid"))
    }

    @Test
    fun `normal reward UI does not use identifier placeholders or internal tag value placeholders`() {
        val health = Files.readString(Path.of("src/main/kotlin/mayorSystem/monitoring/HealthService.kt"))
        val gui = Files.readString(Path.of("src/main/resources/gui.yml"))
        val messages = Files.readString(Path.of("src/main/resources/messages.yml"))

        assertFalse(gui.contains("%deluxetags_identifier%"))
        assertFalse(gui.contains("{deluxetags_identifier}"))
        assertFalse(gui.contains("%tag_id%"))
        assertFalse(messages.contains("%tag_id%"))
        assertFalse(health.contains("\"tag=\$tagId\""))
        assertFalse(health.contains("\"expected=\$tagId\""))
        assertFalse(health.contains("\"permission=\$permission\""))
    }
}
