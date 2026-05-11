package mayorSystem.platform.paper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mayorSystem.MayorPlugin
import mayorSystem.application.usecase.AdminUseCases
import mayorSystem.config.GuiTexts
import mayorSystem.config.Messages
import mayorSystem.config.Settings
import mayorSystem.config.SystemGateOption
import mayorSystem.data.MayorStore
import mayorSystem.data.repository.MayorStoreElectionRepository
import mayorSystem.economy.EconomyHook
import mayorSystem.elections.TermService
import mayorSystem.hologram.LeaderboardHologramService
import mayorSystem.integration.economy.VaultEconomyProvider
import mayorSystem.integration.permissions.LuckPermsPermissionGroupProvider
import mayorSystem.messaging.ChatPrompts
import mayorSystem.messaging.MayorBroadcasts
import mayorSystem.monitoring.AuditService
import mayorSystem.monitoring.HealthService
import mayorSystem.npc.MayorNpcService
import mayorSystem.papi.MayorPlaceholderExpansion
import mayorSystem.perks.PerkService
import mayorSystem.platform.paper.addon.AddonPerkSourceRegistry
import mayorSystem.platform.paper.api.MayorSystemApiImpl
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
import mayorSystem.util.loggedTask
import org.bukkit.Bukkit
import java.util.concurrent.atomic.AtomicInteger

class MayorRuntime(private val plugin: MayorPlugin) {

    enum class ReadyState { LOADING, READY, FAILED }

    val services = MayorServices()

    private val configBootstrap = ConfigBootstrap(plugin)
    private val externalPerks = ExternalPerkSectionSeeder(plugin, services)
    private val listenerRegistrar = PaperListenerRegistrar(plugin, services, externalPerks)

    @Volatile
    private var readyState: ReadyState = ReadyState.LOADING

    private var bootstrapJob: Job? = null
    private val bootstrapGen = AtomicInteger(0)
    private var termRunnerTaskId: Int = -1
    private var papiExpansion: MayorPlaceholderExpansion? = null
    private var apiService: MayorSystemApiImpl? = null

    fun isReady(): Boolean = readyState == ReadyState.READY

    fun isLoading(): Boolean = readyState == ReadyState.LOADING

    fun enable() {
        plugin.saveDefaultConfig()
        services.mainDispatcher = PaperMainDispatcher(plugin)
        services.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        services.actionCoordinator = ActionCoordinator()
        services.addonPerkSources = AddonPerkSourceRegistry(
            config = { plugin.config },
            logger = plugin.logger,
            saveConfig = { plugin.saveConfig() },
            reloadPerks = {
                if (services.hasPerks()) {
                    services.perks.reloadFromConfig()
                }
            }
        )

        reloadEverything()

        services.offlinePlayers = OfflinePlayerCache(plugin)
        services.playerIdentities = PlayerIdentityService(plugin)
        services.gui = GuiManager(plugin)
        services.prompts = ChatPrompts(plugin)
        services.updateNotifier = SpigotUpdateNotifier(plugin)

        services.termService = TermService(plugin)
        services.mayorUsernamePrefix = MayorUsernamePrefixService(plugin)
        services.displayRewardTags = DisplayRewardTagResolver(plugin)
        services.playerDisplayNames = PlayerDisplayNameService(plugin, identityService = services.playerIdentities)
        apiService = listenerRegistrar.registerApiService(MayorSystemApiImpl(plugin))
        services.applyFlow = ApplyFlowService(plugin)
        services.mayorNpc = MayorNpcService(plugin)
        services.showcase = ShowcaseService(plugin)
        services.leaderboardHologram = LeaderboardHologramService(plugin)

        listenerRegistrar.registerStartupListeners()
        services.mayorUsernamePrefix.onEnable()
        services.mayorNpc.onEnable()
        services.leaderboardHologram.onEnable()

        listenerRegistrar.registerCommands()
        listenerRegistrar.registerEconomyRefreshListener()
        papiExpansion = listenerRegistrar.registerPlaceholderExpansion()
        listenerRegistrar.registerExternalPerkRefreshListener()
        listenerRegistrar.scheduleOneTimeExternalPerkRefresh()

        bootstrapAsync()
    }

    fun disable() {
        listenerRegistrar.unregisterApiService(apiService)
        apiService = null
        stopTermRunner()
        papiExpansion?.unregister()
        if (services.hasMayorNpc()) {
            services.mayorNpc.onDisable()
        }
        if (services.hasMayorUsernamePrefix()) {
            services.mayorUsernamePrefix.onDisable()
        }
        if (services.hasDisplayRewardTags()) {
            services.displayRewardTags.clear()
        }
        if (services.hasLeaderboardHologram()) {
            services.leaderboardHologram.onDisable()
        }
        val skinFlushJob = if (services.hasSkins()) {
            services.skins.flush()
        } else {
            null
        }
        if (services.hasScope()) {
            if (skinFlushJob != null) {
                runBlocking {
                    withTimeoutOrNull(3000L) {
                        skinFlushJob.join()
                    }
                }
            }
            val job = services.scope.coroutineContext[Job]
            services.scope.cancel("Plugin disable")
            runBlocking {
                withTimeoutOrNull(3000L) {
                    job?.children?.toList()?.joinAll()
                }
            }
        }
        if (services.hasAudit()) {
            services.audit.shutdown()
        }
        if (services.hasStore()) {
            services.store.shutdown()
        }
    }

    fun reloadEverything() {
        readyState = ReadyState.LOADING
        invalidateBootstrap()
        stopTermRunner()
        plugin.reloadConfig()

        configBootstrap.syncConfigDefaults()
        externalPerks.seedExternalPerkSectionsIfMissing()

        services.settings = Settings.from(plugin.config, plugin.logger)
        MayorBroadcasts.setTitleName(services.settings.titleName)
        MayorBroadcasts.setCommandRoot(plugin, services.settings.titleCommand, services.settings.titleCommandAliasEnabled)
        services.messages = Messages(plugin)
        services.guiTexts = GuiTexts(plugin)

        if (services.hasStore()) {
            services.store.shutdown()
        }
        services.store = MayorStore(plugin)
        services.electionRepository = MayorStoreElectionRepository(services.store)
        services.perks = PerkService(plugin)
        services.economy = EconomyHook(plugin)
        services.economyProvider = VaultEconomyProvider(services.economy)
        services.permissionGroups = LuckPermsPermissionGroupProvider(plugin)

        if (services.hasAudit()) {
            services.audit.shutdown()
        }
        services.audit = AuditService(plugin)
        services.health = HealthService(plugin)
        services.adminUseCases = AdminUseCases(plugin, services.electionRepository)
        services.adminActions = AdminActions(plugin, services.adminUseCases)
        services.voteAccess = VoteAccessService(plugin)

        if (services.hasDisplayRewardTags()) {
            services.displayRewardTags.clear()
        }
        if (!services.hasSkins()) {
            services.skins = SkinService(plugin)
        }

        if (services.hasMayorNpc()) {
            services.mayorNpc.onReload()
        }
        if (services.hasLeaderboardHologram()) {
            services.leaderboardHologram.onReload()
        }
        if (services.hasMayorUsernamePrefix()) {
            services.mayorUsernamePrefix.onReloadSettings()
        }
        if (services.hasOfflinePlayers()) {
            // Offline cache refresh is on-demand in admin menus.
        }
        if (services.hasTermService()) {
            bootstrapAsync()
        }
    }

    fun reloadSettingsOnly() {
        configBootstrap.syncConfigDefaults()
        services.settings = Settings.from(plugin.config, plugin.logger)
        MayorBroadcasts.setTitleName(services.settings.titleName)
        MayorBroadcasts.setCommandRoot(plugin, services.settings.titleCommand, services.settings.titleCommandAliasEnabled)
        if (services.hasDisplayRewardTags()) {
            services.displayRewardTags.clear()
        }
        if (services.hasTermService()) {
            services.termService.invalidateScheduleCache()
        }
        if (services.settings.isDisabled(SystemGateOption.PERKS)) {
            if (services.hasPerks()) {
                services.perks.rebuildActiveEffectsForTerm(-1)
            }
        }
        if (services.settings.isDisabled(SystemGateOption.MAYOR_NPC)) {
            if (services.hasMayorNpc()) {
                services.mayorNpc.forceUpdateMayorForTerm(-1)
            }
        }
        if (services.hasMayorUsernamePrefix()) {
            services.mayorUsernamePrefix.onReloadSettings()
        }
        updateTermRunnerState()
        if (services.hasShowcase()) {
            services.showcase.sync()
        }
    }

    suspend fun reloadEverythingVerified(): Boolean {
        return runCatching {
            withContext(services.mainDispatcher) {
                reloadEverything()
            }
            bootstrapJob?.join()
            isReady()
        }.getOrElse {
            plugin.logger.severe("Reload failed: ${it.message}")
            false
        }
    }

    private fun startTermRunner() {
        if (termRunnerTaskId != -1) return
        termRunnerTaskId = Bukkit.getScheduler()
            .runTaskTimer(plugin, plugin.loggedTask("term runner") { services.termService.tick() }, 20L, 20L * 30L)
            .taskId
    }

    private fun stopTermRunner() {
        if (termRunnerTaskId != -1) {
            runCatching { Bukkit.getScheduler().cancelTask(termRunnerTaskId) }
            termRunnerTaskId = -1
        }
    }

    private fun updateTermRunnerState() {
        if (!plugin.isEnabled) return
        if (!services.hasSettings()) return
        if (services.settings.isBlocked(SystemGateOption.SCHEDULE)) {
            stopTermRunner()
        } else {
            startTermRunner()
        }
    }

    private fun bootstrapAsync() {
        readyState = ReadyState.LOADING
        val gen = bootstrapGen.incrementAndGet()
        bootstrapJob?.cancel()
        bootstrapJob = services.scope.launch {
            val ok = services.store.loadAsync()
            if (gen != bootstrapGen.get()) return@launch
            if (!ok) {
                readyState = ReadyState.FAILED
                plugin.logger.severe("Failed to load the configured data store. Disabling MayorSystem.")
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (plugin.isEnabled) {
                        plugin.server.pluginManager.disablePlugin(plugin)
                    }
                })
                return@launch
            }
            if (!plugin.isEnabled) return@launch
            withContext(services.mainDispatcher) {
                if (gen != bootstrapGen.get()) return@withContext
                if (!plugin.isEnabled) return@withContext

                services.termService.tickNow()

                val termForEffects = if (services.settings.isBlocked(SystemGateOption.PERKS)) {
                    -1
                } else {
                    services.termService.computeNow().first
                }
                services.perks.rebuildActiveEffectsForTerm(termForEffects)

                updateTermRunnerState()
                readyState = ReadyState.READY
                if (services.hasMayorUsernamePrefix()) {
                    services.mayorUsernamePrefix.syncAllOnline()
                }
                if (services.hasShowcase()) {
                    services.showcase.sync()
                } else if (services.hasMayorNpc()) {
                    if (services.settings.isBlocked(SystemGateOption.MAYOR_NPC)) {
                        services.mayorNpc.forceUpdateMayorForTerm(-1)
                    } else {
                        services.mayorNpc.forceUpdateMayor()
                    }
                }
                plugin.logger.info("Ready.")
                if (services.hasUpdateNotifier()) {
                    services.updateNotifier.onPluginReady()
                }
            }
        }
    }

    private fun invalidateBootstrap() {
        bootstrapGen.incrementAndGet()
        bootstrapJob?.cancel()
        bootstrapJob = null
    }
}
