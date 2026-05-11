package mayorSystem.platform.paper

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

class MayorServices {
    lateinit var settings: Settings
    lateinit var messages: Messages
    lateinit var guiTexts: GuiTexts
    lateinit var store: MayorStore
    lateinit var electionRepository: ElectionRepository
    lateinit var gui: GuiManager
    lateinit var perks: PerkService
    lateinit var economy: EconomyHook
    lateinit var economyProvider: EconomyProvider
    lateinit var permissionGroups: PermissionGroupProvider
    lateinit var audit: AuditService
    lateinit var health: HealthService
    lateinit var prompts: ChatPrompts
    lateinit var updateNotifier: SpigotUpdateNotifier
    lateinit var termService: TermService
    lateinit var applyFlow: ApplyFlowService
    lateinit var adminUseCases: AdminUseCases
    lateinit var adminActions: AdminActions
    lateinit var voteAccess: VoteAccessService
    lateinit var actionCoordinator: ActionCoordinator
    lateinit var mayorNpc: MayorNpcService
    lateinit var mayorUsernamePrefix: MayorUsernamePrefixService
    lateinit var playerIdentities: PlayerIdentityService
    lateinit var playerDisplayNames: PlayerDisplayNameService
    lateinit var displayRewardTags: DisplayRewardTagResolver
    lateinit var leaderboardHologram: LeaderboardHologramService
    lateinit var showcase: ShowcaseService
    lateinit var offlinePlayers: OfflinePlayerCache
    lateinit var skins: SkinService
    lateinit var mainDispatcher: PaperMainDispatcher
    lateinit var scope: CoroutineScope
    lateinit var addonPerkSources: AddonPerkSourceRegistry

    fun hasTermService(): Boolean = this::termService.isInitialized
    fun hasMayorNpc(): Boolean = this::mayorNpc.isInitialized
    fun hasMayorUsernamePrefix(): Boolean = this::mayorUsernamePrefix.isInitialized
    fun hasDisplayRewardTags(): Boolean = this::displayRewardTags.isInitialized
    fun hasLeaderboardHologram(): Boolean = this::leaderboardHologram.isInitialized
    fun hasShowcase(): Boolean = this::showcase.isInitialized
    fun hasApplyFlow(): Boolean = this::applyFlow.isInitialized
    fun hasAdminUseCases(): Boolean = this::adminUseCases.isInitialized
    fun hasSkins(): Boolean = this::skins.isInitialized
    fun hasAudit(): Boolean = this::audit.isInitialized
    fun hasStore(): Boolean = this::store.isInitialized
    fun hasElectionRepository(): Boolean = this::electionRepository.isInitialized
    fun hasScope(): Boolean = this::scope.isInitialized
    fun hasSettings(): Boolean = this::settings.isInitialized
    fun hasPerks(): Boolean = this::perks.isInitialized
    fun hasEconomyProvider(): Boolean = this::economyProvider.isInitialized
    fun hasPermissionGroups(): Boolean = this::permissionGroups.isInitialized
    fun hasPlayerIdentities(): Boolean = this::playerIdentities.isInitialized
    fun hasOfflinePlayers(): Boolean = this::offlinePlayers.isInitialized
    fun hasUpdateNotifier(): Boolean = this::updateNotifier.isInitialized
    fun hasAddonPerkSources(): Boolean = this::addonPerkSources.isInitialized
}
