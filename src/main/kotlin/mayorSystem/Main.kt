package mayorSystem

import mayorSystem.cloud.CloudBootstrap
import mayorSystem.monitoring.AuditService
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
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant

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

    lateinit var offlinePlayers: OfflinePlayerCache
        private set

    lateinit var skins: SkinService
        private set

    lateinit var mainDispatcher: PaperMainDispatcher
        private set

    lateinit var scope: CoroutineScope
        private set

    enum class ReadyState { LOADING, READY, FAILED }

    @Volatile
    private var readyState: ReadyState = ReadyState.LOADING

    private var termRunnerTaskId: Int = -1

    private var papiExpansion: MayorPlaceholderExpansion? = null

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
        applyFlow = ApplyFlowService(this)

        // /sell bonus integration
        // - Uses plugin APIs when available (ShopGUI+, EconomyShopGUI)
        // - Falls back to a Vault balance-delta method for unknown /sell plugins
        sellBonus = SellBonusService(this)
        sellBonus.enable()

        mayorNpc = MayorNpcService(this).also { server.pluginManager.registerEvents(it, this); it.onEnable() }

        CloudBootstrap.enable(this)

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            papiExpansion = MayorPlaceholderExpansion(this).also { it.register() }
        }

        bootstrapAsync()
    }

    override fun onDisable() {
        stopTermRunner()
        papiExpansion?.unregister()
        if (this::skins.isInitialized) {
            skins.flush()
        }
        if (this::scope.isInitialized) {
            val job = scope.coroutineContext[Job]
            runBlocking {
                withTimeoutOrNull(3000L) {
                    job?.children?.toList()?.joinAll()
                }
            }
            scope.cancel("Plugin disable")
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
            logger.warning("[MayorSystem] Disabled $disabled sell-perk section(s) (no supported sell plugin found).")
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
    }

    fun hasTermService(): Boolean = this::termService.isInitialized

    fun hasMayorNpc(): Boolean = this::mayorNpc.isInitialized

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

    private fun bootstrapAsync() {
        readyState = ReadyState.LOADING
        scope.launch {
            val ok = store.loadAsync()
            if (!ok) {
                readyState = ReadyState.FAILED
                logger.severe("[MayorSystem] Failed to load store. Plugin remains in LOADING/FAILED state.")
                return@launch
            }
            if (!isEnabled) return@launch
            withContext(mainDispatcher) {
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

                startTermRunner()
                readyState = ReadyState.READY
                logger.info("[MayorSystem] Ready.")
            }
        }
    }
}


