package mayorSystem.platform.paper.command

import mayorSystem.MayorPlugin
import mayorSystem.application.usecase.PlayerElectionCommandFeedback
import mayorSystem.application.usecase.PlayerElectionCommandUseCases
import mayorSystem.candidates.CandidatesCommands
import mayorSystem.data.CandidateStatus
import mayorSystem.elections.ElectionsCommands
import mayorSystem.governance.GovernanceCommands
import mayorSystem.messaging.MessagingCommands
import mayorSystem.messaging.MiniMessageSafety
import mayorSystem.monitoring.MonitoringCommands
import mayorSystem.perks.PerksCommands
import mayorSystem.security.Perms
import mayorSystem.system.SystemCommands
import mayorSystem.maintenance.MaintenanceCommands
import mayorSystem.ui.menus.CandidateMenu
import mayorSystem.ui.menus.MainMenu
import mayorSystem.ui.menus.StatusMenu
import org.bukkit.entity.Player
import org.incendo.cloud.paper.PaperCommandManager
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.paper.util.sender.Source
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import java.time.Duration

class MayorCommands(
    private val plugin: MayorPlugin,
    private val cm: PaperCommandManager<Source>
) {
    private val ctx = CommandContext(plugin, cm)
    private val playerElectionUseCases = PlayerElectionCommandUseCases(
        plugin,
        object : PlayerElectionCommandFeedback {
            override fun msg(player: Player, key: String, placeholders: Map<String, String>) {
                ctx.msg(player, key, placeholders)
            }

            override fun blockIfActionsPaused(player: Player): Boolean =
                ctx.blockIfActionsPaused(player)
        }
    )

    fun register() {
        registerPublic()
        registerAdmin()
    }

    private val voteCooldown = Duration.ofSeconds(2)
    private val applyCooldown = Duration.ofSeconds(2)
    private val helpCooldown = Duration.ofSeconds(2)
    private val mini = MiniMessage.miniMessage()

    private val candidateSuggestions = SuggestionProvider.blockingStrings<Source> { _, _ ->
        if (!plugin.isReady()) return@blockingStrings emptyList()
        val term = plugin.termService.computeNow().second
        plugin.store.candidates(term, includeRemoved = false)
            .filter { it.status == CandidateStatus.ACTIVE }
            .map { it.lastKnownName }
    }

    // ---------------------------------------------------------------------
    // Public commands
    // ---------------------------------------------------------------------

    private fun registerPublic() {
        ctx.registerMenuRoute(
            literals = emptyList(),
            permission = Permission.of(Perms.USE),
            menuFactory = { MainMenu(plugin) },
            requirePublicAccess = true
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("help")
                .permission(Permission.of(Perms.USE))
                .handler { command ->
                    ctx.withPlayer(command.sender().source()) { player ->
                        if (!ctx.checkPublicAccess(player)) return@withPlayer
                        if (ctx.checkCooldown(player, "help", helpCooldown)) return@withPlayer
                        sendPlayerHelp(player)
                    }
                }
        )

        ctx.registerMenuRoute(
            literals = listOf("status"),
            permission = Permission.of(Perms.USE),
            menuFactory = { StatusMenu(plugin) },
            requirePublicAccess = true
        )

        // /mayor apply
        cm.command(
            ctx.rootCommandBuilder()
                .literal("apply")
                .permission(Permission.of(Perms.APPLY))
                .handler { command ->
                    ctx.withPlayer(command.sender().source()) { player ->
                        withPublicAccess(player) {
                            if (checkCooldown(player, "apply", applyCooldown)) return@withPublicAccess
                            playerElectionUseCases.apply(player)
                        }
                    }
                }
        )

        // /mayor vote <candidateName>
        cm.command(
            ctx.rootCommandBuilder()
                .literal("vote")
                .permission(Permission.of(Perms.VOTE))
                .senderType(PlayerSource::class.java)
                .required("candidate", stringParser(), candidateSuggestions)
                .handler { command ->
                    val player: Player = command.sender().source()
                    withPublicAccess(player) {
                        if (checkCooldown(player, "vote", voteCooldown)) return@withPublicAccess
                        val name = command.get<String>("candidate")
                        playerElectionUseCases.voteForCandidate(player, name)
                    }
                }
        )

        // /mayor vote -> open vote menu
        cm.command(
            ctx.rootCommandBuilder()
                .literal("vote")
                .permission(Permission.of(Perms.VOTE))
                .handler { command ->
                    val sender = command.sender().source()
                    val player = sender as? Player
                    if (player == null) {
                        ctx.msg(sender, "errors.player_only")
                        return@handler
                    }
                    if (!ctx.checkPublicAccess(player)) return@handler
                    if (ctx.checkCooldown(player, "vote", voteCooldown)) return@handler
                    playerElectionUseCases.openVoteMenu(player)
                }
        )

        // /mayor candidate
        ctx.registerMenuRoute(
            literals = listOf("candidate"),
            permission = Permission.of(Perms.CANDIDATE),
            menuFactory = { CandidateMenu(plugin) },
            requirePublicAccess = true
        )

        // /mayor stepdown
        cm.command(
            ctx.rootCommandBuilder()
                .literal("stepdown")
                .permission(Permission.of(Perms.CANDIDATE))
                .handler { command ->
                    ctx.withPlayer(command.sender().source()) { player ->
                        withPublicAccess(player) {
                            playerElectionUseCases.stepDown(player)
                        }
                    }
                }
        )
    }

    // ---------------------------------------------------------------------
    // Admin commands
    // ---------------------------------------------------------------------

    private fun registerAdmin() {
        SystemCommands(ctx).register()
        GovernanceCommands(ctx).register()
        ElectionsCommands(ctx).register()
        CandidatesCommands(ctx).register()
        PerksCommands(ctx).register()
        MessagingCommands(ctx).register()
        MonitoringCommands(ctx).register()
        MaintenanceCommands(ctx).register()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------
    private fun checkCooldown(player: Player, key: String, duration: Duration): Boolean =
        ctx.checkCooldown(player, key, duration)

    private inline fun withPublicAccess(player: Player, block: () -> Unit) {
        if (!ctx.checkPublicAccess(player)) return
        block()
    }

    private fun sendPlayerHelp(player: Player) {
        val root = displayCommandRoot()
        val titleName = plugin.settings.titleName
        val titleNameMini = MiniMessageSafety.escapeUntrustedMiniMessage(titleName)
        val fallback = if (root == "mayor") {
            emptyList()
        } else {
            listOf("<dark_gray>Fallback:</dark_gray> <gray>/mayor always works too.</gray>")
        }

        player.sendMessage(Component.empty())
        player.sendMessage(mini.deserialize("<gradient:#f7971e:#ffd200><bold>$titleNameMini Help</bold></gradient> <dark_gray>/</dark_gray> <white>Election tools</white>"))
        player.sendMessage(mini.deserialize("<dark_gray>--------------------------------</dark_gray>"))
        player.sendMessage(helpCommandLine("/$root", "Open the $titleName menu."))
        player.sendMessage(helpCommandLine("/$root status", "See the term, timing, and current $titleName."))
        player.sendMessage(helpCommandLine("/$root apply", "Run for the next election."))
        player.sendMessage(helpCommandLine("/$root vote", "Browse candidates and cast your vote."))
        player.sendMessage(helpCommandLine("/$root candidate", "Edit your profile, bio, and custom perks."))
        player.sendMessage(helpCommandLine("/$root stepdown", "Leave the race, or step down if enabled."))
        fallback.forEach { line -> player.sendMessage(mini.deserialize(line)) }
        player.sendMessage(mini.deserialize("<dark_gray>--------------------------------</dark_gray>"))
        player.sendMessage(mini.deserialize("<dark_gray>MayorSystem by</dark_gray> <gradient:#00c6ff:#0072ff>L1cked</gradient>"))
        player.sendMessage(Component.empty())
    }

    private fun helpCommandLine(command: String, description: String): Component {
        val hover = Component.text("Click to type $command", NamedTextColor.GRAY)
        return Component.text(command, NamedTextColor.YELLOW)
            .clickEvent(ClickEvent.suggestCommand(command))
            .hoverEvent(HoverEvent.showText(hover))
            .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
            .append(Component.text(description, NamedTextColor.GRAY))
    }

    private fun displayCommandRoot(): String {
        if (!plugin.settings.titleCommandAliasEnabled) return "mayor"
        val alias = plugin.settings.titleCommand.lowercase().trim()
        if (alias.isBlank() || alias == "mayor") return "mayor"
        return if (CommandAliasSafety.blockedReason(plugin, alias) == null) alias else "mayor"
    }
}

