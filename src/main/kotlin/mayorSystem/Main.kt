package mayorSystem

import mayorSystem.cloud.CloudBootstrap
import mayorSystem.monitoring.AuditService
import mayorSystem.api.MayorSystemApi
import mayorSystem.api.MayorSystemApiImpl
import mayorSystem.config.Messages
import mayorSystem.config.Settings
import mayorSystem.data.MayorStore
import mayorSystem.economy.EconomyHook
import mayorSystem.economy.SellBonusService
import mayorSystem.npc.MayorNpcService
import mayorSystem.papi.MayorPlaceholderExpansion
import mayorSystem.service.ApplyFlowService
import mayorSystem.service.AdminActions
import mayorSystem.monitoring.HealthService
import mayorSystem.perks.PerkService
import mayorSystem.elections.TermService
import mayorSystem.perks.PerkJoinListener
import mayorSystem.service.OfflinePlayerCache
import mayorSystem.ui.GuiManager
import mayorSystem.messaging.ChatPrompts
import mayorSystem.util.PaperMainDispatcher
import mayorSystem.service.SkinService
import mayorSystem.hologram.LeaderboardHologramService
import mayorSystem.showcase.ShowcaseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class MayorPlugin : JavaPlugin() {

    lateinit var settings: Settings
        private set

    lateinit var messages: Messages
        private set

    lateinit var store: MayorStore
        private set

    lateinit var gui: GuiManager
        private set

    lateinit var perks: PerkService
        private set

    lateinit var economy: EconomyHook
        private set

    lateinit var audit: AuditService
        private set

    lateinit var health: HealthService
    lateinit var sellBonus: SellBonusService
        private set

    // Still used for custom perk requests (we'll replace with menus later)
    lateinit var prompts: ChatPrompts
        private set

    lateinit var termService: TermService
        private set

    /**
     * In-memory apply wizard state (per-player selections).
     */
    lateinit var applyFlow: ApplyFlowService
        private set
    lateinit var adminActions: AdminActions
        private set

    lateinit var mayorNpc: MayorNpcService
        private set

    lateinit var leaderboardHologram: LeaderboardHologramService
        private set

    lateinit var showcase: ShowcaseService
        private set

    lateinit var offlinePlayers: OfflinePlayerCache
        private set

    lateinit var skins: SkinService
        private set

    lateinit var mainDispatcher: PaperMainDispatcher
        private set

    lateinit var scope: CoroutineScope
        private set

    private var bootstrapJob: Job? = null
    private val bootstrapGen = AtomicInteger(0)

    enum class ReadyState { LOADING, READY, FAILED }

    @Volatile
    private var readyState: ReadyState = ReadyState.LOADING

    private var termRunnerTaskId: Int = -1

    private var papiExpansion: MayorPlaceholderExpansion? = null
    private var apiService: MayorSystemApiImpl? = null

    fun isReady(): Boolean = readyState == ReadyState.READY
    fun isLoading(): Boolean = readyState == ReadyState.LOADING

    override fun onEnable() {
        saveDefaultConfig()
        mainDispatcher = PaperMainDispatcher(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        reloadEverything()
        offlinePlayers = OfflinePlayerCache(this)

        gui = GuiManager(this).also { server.pluginManager.registerEvents(it, this) }
        prompts = ChatPrompts(this).also { server.pluginManager.registerEvents(it, this) }

        // Keep term-wide perks consistent for players joining while perks are active.
        server.pluginManager.registerEvents(PerkJoinListener(this), this)

        // Services
        termService = TermService(this)
        apiService = MayorSystemApiImpl(this)
        server.servicesManager.register(MayorSystemApi::class.java, apiService!!, this, ServicePriority.Normal)
        applyFlow = ApplyFlowService(this)
        server.pluginManager.registerEvents(applyFlow, this)

        // /sell bonus integration
        // - Uses SystemSellAddon API when available
        // - Falls back to a Vault balance-delta method for unknown /sell plugins
        sellBonus = SellBonusService(this)
        sellBonus.enable()

        mayorNpc = MayorNpcService(this).also { server.pluginManager.registerEvents(it, this); it.onEnable() }

        showcase = ShowcaseService(this)
        leaderboardHologram = LeaderboardHologramService(this).also { it.onEnable() }

        CloudBootstrap.enable(this)

        // Refresh Vault economy provider if it appears/disappears after startup.
        server.pluginManager.registerEvents(object : Listener {
            private fun isEconomyService(service: Class<*>?): Boolean =
                service?.name == "net.milkbowl.vault.economy.Economy"
            private fun isChatService(service: Class<*>?): Boolean =
                service?.name == "net.milkbowl.vault.chat.Chat"

            @EventHandler
            fun onServiceRegister(e: ServiceRegisterEvent) {
                if (isEconomyService(e.provider.service)) {
                    economy.refresh()
                }
                if (isChatService(e.provider.service) && this@MayorPlugin::mayorNpc.isInitialized) {
                    mayorNpc.invalidateChatCache()
                }
            }

            @EventHandler
            fun onServiceUnregister(e: ServiceUnregisterEvent) {
                if (isEconomyService(e.provider.service)) {
                    economy.refresh()
                }
                if (isChatService(e.provider.service) && this@MayorPlugin::mayorNpc.isInitialized) {
                    mayorNpc.invalidateChatCache()
                }
            }
        }, this)

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            papiExpansion = MayorPlaceholderExpansion(this).also { it.register() }
        }

        bootstrapAsync()
    }

    override fun onDisable() {
        apiService?.let { server.servicesManager.unregister(MayorSystemApi::class.java, it) }
        apiService = null
        stopTermRunner()
        papiExpansion?.unregister()
        if (this::mayorNpc.isInitialized) {
            mayorNpc.onDisable()
        }
        if (this::leaderboardHologram.isInitialized) {
            leaderboardHologram.onDisable()
        }
        if (this::skins.isInitialized) {
            skins.flush()
        }
        if (this::scope.isInitialized) {
            val job = scope.coroutineContext[Job]
            scope.cancel("Plugin disable")
            runBlocking {
                withTimeoutOrNull(3000L) {
                    job?.children?.toList()?.joinAll()
                }
            }
        }
        if (this::audit.isInitialized) {
            audit.shutdown()
        }
        if (this::store.isInitialized) {
            store.shutdown()
        }
    }

    fun reloadEverything() {
        readyState = ReadyState.LOADING
        stopTermRunner()
        reloadConfig()
        settings = Settings.from(config, logger)
        messages = Messages(this)
        if (this::store.isInitialized) {
            store.shutdown()
        }
        store = MayorStore(this)
        perks = PerkService(this)
        perks.invalidateSellMultiplierCache()
        economy = EconomyHook(this)
        audit = AuditService(this)
        health = HealthService(this)
        adminActions = AdminActions(this)
        if (!this::skins.isInitialized) {
            skins = SkinService(this)
        }

        val disabled = perks.enforceSellCategoryPerkAvailability()
        if (disabled > 0) {
            logger.warning("[MayorSystem] Disabled $disabled sell-perk section(s) (SystemSellAddon not found).")
        }

        val skyblockDisabled = perks.enforceSkyblockStyleSectionAvailability()
        if (skyblockDisabled > 0) {
            logger.warning("[MayorSystem] Disabled skyblock_style perk section (SystemSkyblockStyleAddon not found). Install the addon to enable it.")
        }

        if (this::termService.isInitialized) {
            val term = if (settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) -1 else termService.computeNow().first
            perks.rebuildActiveEffectsForTerm(term)
        }
        if (this::mayorNpc.isInitialized) {
            mayorNpc.onReload()
            if (settings.isBlocked(mayorSystem.config.SystemGateOption.MAYOR_NPC)) {
                mayorNpc.forceUpdateMayorForTerm(-1)
            }
        }
        if (this::leaderboardHologram.isInitialized) {
            leaderboardHologram.onReload()
        }
        if (hasShowcase()) {
            showcase.sync()
        }
        if (this::offlinePlayers.isInitialized) {
            // Offline cache refresh is now on-demand (admin menus).
        }
        if (this::termService.isInitialized) {
            bootstrapAsync()
        }
    }

    fun reloadSettingsOnly() {
        settings = Settings.from(config, logger)
        if (this::termService.isInitialized) {
            termService.invalidateScheduleCache()
        }
        if (settings.isDisabled(mayorSystem.config.SystemGateOption.PERKS)) {
            if (this::perks.isInitialized) {
                perks.rebuildActiveEffectsForTerm(-1)
            }
        }
        if (settings.isDisabled(mayorSystem.config.SystemGateOption.MAYOR_NPC)) {
            if (this::mayorNpc.isInitialized) {
                mayorNpc.forceUpdateMayorForTerm(-1)
            }
        }
        updateTermRunnerState()
        if (hasShowcase()) {
            showcase.sync()
        }
    }

    fun hasTermService(): Boolean = this::termService.isInitialized

    fun hasMayorNpc(): Boolean = this::mayorNpc.isInitialized

    fun hasLeaderboardHologram(): Boolean = this::leaderboardHologram.isInitialized

    fun hasShowcase(): Boolean = this::showcase.isInitialized

    private fun startTermRunner() {
        if (termRunnerTaskId != -1) return
        termRunnerTaskId = Bukkit.getScheduler().runTaskTimer(this, Runnable { termService.tick() }, 20L, 20L * 30L).taskId
    }

    private fun stopTermRunner() {
        if (termRunnerTaskId != -1) {
            runCatching { Bukkit.getScheduler().cancelTask(termRunnerTaskId) }
            termRunnerTaskId = -1
        }
    }

    private fun updateTermRunnerState() {
        if (!isEnabled) return
        if (!this::settings.isInitialized) return
        if (settings.isBlocked(mayorSystem.config.SystemGateOption.SCHEDULE)) {
            stopTermRunner()
        } else {
            startTermRunner()
        }
    }

    private fun bootstrapAsync() {
        readyState = ReadyState.LOADING
        val gen = bootstrapGen.incrementAndGet()
        bootstrapJob?.cancel()
        bootstrapJob = scope.launch {
            val ok = store.loadAsync()
            if (gen != bootstrapGen.get()) return@launch
            if (!ok) {
                readyState = ReadyState.FAILED
                logger.severe("[MayorSystem] Failed to load store. Plugin remains in LOADING/FAILED state.")
                return@launch
            }
            if (!isEnabled) return@launch
            withContext(mainDispatcher) {
                if (gen != bootstrapGen.get()) return@withContext
                if (!isEnabled) return@withContext

                // Catch-up on startup (server may have been offline at a term boundary).
                termService.tickNow()

                // Rebuild active potion effects after restart/reload (no other commands).
                val termForEffects = if (settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) -1 else termService.computeNow().first
                perks.rebuildActiveEffectsForTerm(termForEffects)

                // Ensure the NPC reflects the current mayor after catch-up.
                if (settings.isBlocked(mayorSystem.config.SystemGateOption.MAYOR_NPC)) {
                    mayorNpc.forceUpdateMayorForTerm(-1)
                } else {
                    mayorNpc.forceUpdateMayor()
                }

                updateTermRunnerState()
                readyState = ReadyState.READY
                logger.info("[MayorSystem] Ready.")
                if (hasShowcase()) {
                    showcase.sync()
                }
            }
        }
    }
}


