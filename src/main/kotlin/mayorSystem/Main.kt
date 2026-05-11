package mayorSystem

import kotlinx.coroutines.CoroutineScope
import mayorSystem.application.usecase.AdminUseCases
import mayorSystem.config.GuiTexts
import mayorSystem.config.Messages
import mayorSystem.config.Settings
import mayorSystem.data.MayorStore
import mayorSystem.data.repository.ElectionRepository
import mayorSystem.economy.EconomyHook
import mayorSystem.elections.TermService
import mayorSystem.hologram.LeaderboardHologramService
import mayorSystem.integration.economy.EconomyProvider
import mayorSystem.integration.permissions.PermissionGroupProvider
import mayorSystem.messaging.ChatPrompts
import mayorSystem.monitoring.AuditService
import mayorSystem.monitoring.HealthService
import mayorSystem.npc.MayorNpcService
import mayorSystem.perks.PerkService
import mayorSystem.platform.paper.addon.AddonPerkSourceRegistry
import mayorSystem.platform.paper.MayorRuntime
import mayorSystem.service.ActionCoordinator
import mayorSystem.service.AdminActions
import mayorSystem.service.ApplyFlowService
import mayorSystem.service.DisplayRewardTagResolver
import mayorSystem.service.MayorUsernamePrefixService
import mayorSystem.service.OfflinePlayerCache
import mayorSystem.service.PlayerIdentityService
import mayorSystem.service.PlayerDisplayNameService
import mayorSystem.service.SkinService
import mayorSystem.service.SpigotUpdateNotifier
import mayorSystem.service.VoteAccessService
import mayorSystem.showcase.ShowcaseService
import mayorSystem.ui.GuiManager
import mayorSystem.util.PaperMainDispatcher
import org.bukkit.plugin.java.JavaPlugin

class MayorPlugin : JavaPlugin() {

    private lateinit var runtime: MayorRuntime

    val settings: Settings get() = runtime.services.settings
    val messages: Messages get() = runtime.services.messages
    val guiTexts: GuiTexts get() = runtime.services.guiTexts
    val store: MayorStore get() = runtime.services.store
    val electionRepository: ElectionRepository get() = runtime.services.electionRepository
    val gui: GuiManager get() = runtime.services.gui
    val perks: PerkService get() = runtime.services.perks
    val economy: EconomyHook get() = runtime.services.economy
    val economyProvider: EconomyProvider get() = runtime.services.economyProvider
    val permissionGroups: PermissionGroupProvider get() = runtime.services.permissionGroups
    val audit: AuditService get() = runtime.services.audit
    var health: HealthService
        get() = runtime.services.health
        set(value) {
            runtime.services.health = value
        }
    val prompts: ChatPrompts get() = runtime.services.prompts
    val updateNotifier: SpigotUpdateNotifier get() = runtime.services.updateNotifier
    val termService: TermService get() = runtime.services.termService
    val applyFlow: ApplyFlowService get() = runtime.services.applyFlow
    val adminUseCases: AdminUseCases get() = runtime.services.adminUseCases
    val adminActions: AdminActions get() = runtime.services.adminActions
    val voteAccess: VoteAccessService get() = runtime.services.voteAccess
    val actionCoordinator: ActionCoordinator get() = runtime.services.actionCoordinator
    val mayorNpc: MayorNpcService get() = runtime.services.mayorNpc
    val mayorUsernamePrefix: MayorUsernamePrefixService get() = runtime.services.mayorUsernamePrefix
    val playerIdentities: PlayerIdentityService get() = runtime.services.playerIdentities
    val playerDisplayNames: PlayerDisplayNameService get() = runtime.services.playerDisplayNames
    val displayRewardTags: DisplayRewardTagResolver get() = runtime.services.displayRewardTags
    val leaderboardHologram: LeaderboardHologramService get() = runtime.services.leaderboardHologram
    val showcase: ShowcaseService get() = runtime.services.showcase
    val offlinePlayers: OfflinePlayerCache get() = runtime.services.offlinePlayers
    val skins: SkinService get() = runtime.services.skins
    val mainDispatcher: PaperMainDispatcher get() = runtime.services.mainDispatcher
    val scope: CoroutineScope get() = runtime.services.scope
    val addonPerkSources: AddonPerkSourceRegistry get() = runtime.services.addonPerkSources

    override fun onEnable() {
        runtime = MayorRuntime(this)
        runtime.enable()
    }

    override fun onDisable() {
        if (this::runtime.isInitialized) {
            runtime.disable()
        }
    }

    fun isReady(): Boolean = this::runtime.isInitialized && runtime.isReady()

    fun isLoading(): Boolean = !this::runtime.isInitialized || runtime.isLoading()

    fun reloadEverything() {
        runtime.reloadEverything()
    }

    fun reloadSettingsOnly() {
        runtime.reloadSettingsOnly()
    }

    suspend fun reloadEverythingVerified(): Boolean = runtime.reloadEverythingVerified()

    fun hasTermService(): Boolean = this::runtime.isInitialized && runtime.services.hasTermService()
    fun hasMayorNpc(): Boolean = this::runtime.isInitialized && runtime.services.hasMayorNpc()
    fun hasMayorUsernamePrefix(): Boolean = this::runtime.isInitialized && runtime.services.hasMayorUsernamePrefix()
    fun hasDisplayRewardTags(): Boolean = this::runtime.isInitialized && runtime.services.hasDisplayRewardTags()
    fun hasLeaderboardHologram(): Boolean = this::runtime.isInitialized && runtime.services.hasLeaderboardHologram()
    fun hasShowcase(): Boolean = this::runtime.isInitialized && runtime.services.hasShowcase()
    fun hasApplyFlow(): Boolean = this::runtime.isInitialized && runtime.services.hasApplyFlow()
}
