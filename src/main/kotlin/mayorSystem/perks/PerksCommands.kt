package mayorSystem.perks

import mayorSystem.cloud.CommandContext
import mayorSystem.data.RequestStatus
import mayorSystem.perks.ui.AdminPerkCatalogMenu
import mayorSystem.perks.ui.AdminPerkRefreshMenu
import mayorSystem.perks.ui.AdminPerkRequestsMenu
import mayorSystem.perks.ui.AdminPerksMenu
import mayorSystem.perks.ui.AdminSettingsCustomRequestsMenu
import mayorSystem.security.Perms
import org.bukkit.Bukkit
import org.incendo.cloud.paper.util.sender.PlayerSource
import org.incendo.cloud.permission.Permission
import org.incendo.cloud.parser.standard.IntegerParser.integerParser
import org.incendo.cloud.parser.standard.StringParser.stringParser
import org.incendo.cloud.suggestion.SuggestionProvider
import kotlinx.coroutines.launch

class PerksCommands(private val ctx: CommandContext) {
    private val perkSectionSuggestions = SuggestionProvider.blockingStrings<org.incendo.cloud.paper.util.sender.Source> { _, _ ->
        ctx.plugin.config.getConfigurationSection("perks.sections")
            ?.getKeys(false)
            ?.toList()
            ?: emptyList()
    }

    private val perkSuggestions = SuggestionProvider.blockingStrings<org.incendo.cloud.paper.util.sender.Source> { context, _ ->
        val section = runCatching { context.get<String>("section") }.getOrNull()
        val base = if (section.isNullOrBlank()) "perks.sections" else "perks.sections.$section.perks"
        val sec = ctx.plugin.config.getConfigurationSection(base) ?: return@blockingStrings emptyList()
        if (section.isNullOrBlank()) {
            sec.getKeys(false).flatMap { sectionId ->
                ctx.plugin.config.getConfigurationSection("perks.sections.$sectionId.perks")
                    ?.getKeys(false)
                    ?.toList()
                    ?: emptyList()
            }
        } else {
            sec.getKeys(false).toList()
        }
    }

    private val stateSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>("toggle", "on", "off")

    private val requestIdSuggestions = SuggestionProvider.blockingStrings<org.incendo.cloud.paper.util.sender.Source> { _, _ ->
        val term = ctx.plugin.termService.computeNow().second
        ctx.plugin.store.listRequests(term, RequestStatus.PENDING).map { it.id.toString() }
    }

    private val approveDenySuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>("approve", "deny")

    private val refreshTargetSuggestions = SuggestionProvider.blockingStrings<org.incendo.cloud.paper.util.sender.Source> { _, _ ->
        val names = Bukkit.getOnlinePlayers().map { it.name }.sortedBy { it.lowercase() }
        listOf("--all", "all") + names
    }

    private val customConditionSuggestions = SuggestionProvider.suggestingStrings<org.incendo.cloud.paper.util.sender.Source>(
        mayorSystem.config.CustomRequestCondition.values().map { it.name }
    )

    fun register() {
        val plugin = ctx.plugin
        val cm = ctx.cm

        ctx.registerMenuRoute(
            literals = listOf("admin", "perks"),
            permission = Permission.anyOf(
                Permission.of(Perms.ADMIN_PERKS_CATALOG),
                Permission.of(Perms.ADMIN_PERKS_REQUESTS),
                Permission.of(Perms.ADMIN_PERKS_REFRESH)
            ),
            menuFactory = { AdminPerksMenu(plugin) }
        )

        // Refresh menu route
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("perks")
                .literal("refresh")
                .permission(Perms.ADMIN_PERKS_REFRESH)
                .handler { command ->
                    val sender = command.sender().source()
                    ctx.withPlayer(sender) { admin ->
                        plugin.gui.open(admin, AdminPerkRefreshMenu(plugin))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("perks")
                .literal("refresh")
                .permission(Perms.ADMIN_PERKS_REFRESH)
                .senderType(PlayerSource::class.java)
                .required("target", stringParser(), refreshTargetSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val target = command.get<String>("target")

                    if (target.equals("--all", ignoreCase = true) || target.equals("all", ignoreCase = true)) {
                        val count = plugin.adminActions.refreshPerksAll(admin)
                        ctx.msg(admin, "admin.perks.refresh_all", mapOf("count" to count.toString()))
                        return@handler
                    }

                    val p = ctx.findOnlinePlayer(target)
                    if (p == null) {
                        ctx.msg(admin, "admin.perks.refresh_player_not_found", mapOf("name" to target))
                        ctx.sendOnlinePlayers(admin)
                        return@handler
                    }

                    plugin.adminActions.refreshPerksPlayer(admin, p)
                    ctx.msg(admin, "admin.perks.refresh_player", mapOf("name" to p.name))
                }
        )

        // Requests menu routes
        ctx.registerMenuRoute(
            literals = listOf("admin", "perks", "requests"),
            permission = Permission.of(Perms.ADMIN_PERKS_REQUESTS),
            menuFactory = { AdminPerkRequestsMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "perks", "requests", "approve"),
            permission = Permission.of(Perms.ADMIN_PERKS_REQUESTS),
            menuFactory = { AdminPerkRequestsMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "perks", "requests", "deny"),
            permission = Permission.of(Perms.ADMIN_PERKS_REQUESTS),
            menuFactory = { AdminPerkRequestsMenu(plugin) }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("perks")
                .literal("requests")
                .literal("approve")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser(), requestIdSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val id = command.get<Int>("id")
                    val term = plugin.termService.computeNow().second
                    plugin.scope.launch(plugin.mainDispatcher) {
                        plugin.adminActions.setRequestStatus(admin, term, id, RequestStatus.APPROVED)
                        ctx.msg(admin, "admin.perks.request_approved", mapOf("id" to id.toString()))
                    }
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("perks")
                .literal("requests")
                .literal("deny")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser(), requestIdSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val id = command.get<Int>("id")
                    val term = plugin.termService.computeNow().second
                    plugin.scope.launch(plugin.mainDispatcher) {
                        plugin.adminActions.setRequestStatus(admin, term, id, RequestStatus.DENIED)
                        ctx.msg(admin, "admin.perks.request_denied", mapOf("id" to id.toString()))
                    }
                }
        )

        // /mayor admin customperk <id> <approve|deny>
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("customperk")
                .permission(Perms.ADMIN_PERKS_REQUESTS)
                .senderType(PlayerSource::class.java)
                .required("id", integerParser(), requestIdSuggestions)
                .required("action", stringParser(), approveDenySuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val id = command.get<Int>("id")
                    val action = command.get<String>("action").lowercase()
                    val term = plugin.termService.computeNow().second
                    val status = when (action) {
                        "approve" -> RequestStatus.APPROVED
                        "deny" -> RequestStatus.DENIED
                        else -> null
                    }
                    if (status == null) {
                        ctx.msg(admin, "admin.perks.request_action_invalid")
                        return@handler
                    }
                    plugin.scope.launch(plugin.mainDispatcher) {
                        plugin.adminActions.setRequestStatus(admin, term, id, status)
                        if (status == RequestStatus.APPROVED) {
                            ctx.msg(admin, "admin.perks.request_approved", mapOf("id" to id.toString()))
                        } else {
                            ctx.msg(admin, "admin.perks.request_denied", mapOf("id" to id.toString()))
                        }
                    }
                }
        )

        // Catalog menu routes
        ctx.registerMenuRoute(
            literals = listOf("admin", "perks", "catalog"),
            permission = Permission.of(Perms.ADMIN_PERKS_CATALOG),
            menuFactory = { AdminPerkCatalogMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "perks", "catalog", "section"),
            permission = Permission.of(Perms.ADMIN_PERKS_CATALOG),
            menuFactory = { AdminPerkCatalogMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "perks", "catalog", "perk"),
            permission = Permission.of(Perms.ADMIN_PERKS_CATALOG),
            menuFactory = { AdminPerkCatalogMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "customperk"),
            permission = Permission.of(Perms.ADMIN_PERKS_REQUESTS),
            menuFactory = { AdminPerkRequestsMenu(plugin) }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("perks")
                .literal("catalog")
                .literal("section")
                .permission(Perms.ADMIN_PERKS_CATALOG)
                .senderType(PlayerSource::class.java)
                .required("section", stringParser(), perkSectionSuggestions)
                .required("state", stringParser(), stateSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val section = command.get<String>("section")
                    val state = command.get<String>("state")

                    val base = "perks.sections.$section"
                    if (!plugin.config.contains(base)) {
                        ctx.msg(admin, "admin.perks.section_not_found", mapOf("section" to section))
                        return@handler
                    }

                    val current = plugin.config.getBoolean("$base.enabled", true)
                    val next = ctx.resolveToggle(state, current) ?: run {
                        ctx.msg(admin, "admin.perks.state_invalid")
                        return@handler
                    }

                    plugin.adminActions.setPerkSectionEnabled(admin, section, next)
                    ctx.msg(admin, "admin.perks.section_updated", mapOf("section" to section, "state" to if (next) "ENABLED" else "DISABLED"))
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("perks")
                .literal("catalog")
                .literal("perk")
                .permission(Perms.ADMIN_PERKS_CATALOG)
                .senderType(PlayerSource::class.java)
                .required("section", stringParser(), perkSectionSuggestions)
                .required("perk", stringParser(), perkSuggestions)
                .required("state", stringParser(), stateSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val section = command.get<String>("section")
                    val perk = command.get<String>("perk")
                    val state = command.get<String>("state")

                    val base = "perks.sections.$section.perks.$perk"
                    if (!plugin.config.contains(base)) {
                        ctx.msg(admin, "admin.perks.perk_not_found", mapOf("section" to section, "perk" to perk))
                        return@handler
                    }

                    val current = plugin.config.getBoolean("$base.enabled", true)
                    val next = ctx.resolveToggle(state, current) ?: run {
                        ctx.msg(admin, "admin.perks.state_invalid")
                        return@handler
                    }

                    plugin.adminActions.setPerkEnabled(admin, section, perk, next)
                    ctx.msg(admin, "admin.perks.perk_updated", mapOf("section" to section, "perk" to perk, "state" to if (next) "ENABLED" else "DISABLED"))
                }
        )

        // Settings menu routes
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "custom_limit"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsCustomRequestsMenu(plugin) }
        )
        ctx.registerMenuRoute(
            literals = listOf("admin", "settings", "custom_condition"),
            permission = Permission.of(Perms.ADMIN_SETTINGS_EDIT),
            menuFactory = { AdminSettingsCustomRequestsMenu(plugin) }
        )

        // Settings commands
        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("custom_limit")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("value", integerParser())
                .handler { command ->
                    val admin = command.sender().source()
                    val value = command.get<Int>("value").coerceAtLeast(0)
                    plugin.adminActions.updateSettingsConfig(admin, "custom_requests.limit_per_term", value)
                    ctx.msg(admin, "admin.settings.custom_limit_set", mapOf("value" to value.toString()))
                }
        )

        cm.command(
            ctx.rootCommandBuilder()
                .literal("admin")
                .literal("settings")
                .literal("custom_condition")
                .permission(Perms.ADMIN_SETTINGS_EDIT)
                .senderType(PlayerSource::class.java)
                .required("condition", stringParser(), customConditionSuggestions)
                .handler { command ->
                    val admin = command.sender().source()
                    val raw = command.get<String>("condition")
                    val cond = runCatching { mayorSystem.config.CustomRequestCondition.valueOf(raw.uppercase()) }.getOrNull()
                    if (cond == null) {
                        ctx.msg(admin, "admin.settings.custom_condition_invalid")
                        return@handler
                    }
                    plugin.adminActions.updateSettingsConfig(admin, "custom_requests.request_condition", cond.name)
                    ctx.msg(admin, "admin.settings.custom_condition_set", mapOf("value" to cond.name))
                }
        )
    }
}

