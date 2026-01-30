package mayorSystem

import mayorSystem.cloud.CloudBootstrap
import mayorSystem.audit.AuditService
import mayorSystem.config.Messages
import mayorSystem.config.Settings
import mayorSystem.data.MayorStore
import mayorSystem.econ.EconomyHook
import mayorSystem.econ.SellBonusService
import mayorSystem.npc.MayorNpcService
import mayorSystem.service.ApplyFlowService
import mayorSystem.service.AdminActions
import mayorSystem.service.HealthService
import mayorSystem.service.PerkService
import mayorSystem.service.TermService
import mayorSystem.service.PerkJoinListener
import mayorSystem.service.OfflinePlayerCache
import mayorSystem.ui.GuiManager
import mayorSystem.ux.ChatPrompts
import mayorSystem.util.PaperMainDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    lateinit var mainDispatcher: PaperMainDispatcher
        private set

    lateinit var scope: CoroutineScope
        private set

    override fun onEnable() {
        saveDefaultConfig()
        mainDispatcher = PaperMainDispatcher(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        reloadEverything()
        offlinePlayers = OfflinePlayerCache(this).also { it.refreshAsync() }

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

        // Catch-up on startup (server may have been offline at a term boundary).
        runBlocking {
            termService.tickNow()
        }

        // Rebuild active potion effects after restart/reload (no other commands).
        val termForEffects = if (settings.isBlocked(mayorSystem.config.SystemGateOption.PERKS)) -1 else termService.computeNow().first
        perks.rebuildActiveEffectsForTerm(termForEffects)

        // Ensure the NPC reflects the current mayor after catch-up.
        if (settings.isBlocked(mayorSystem.config.SystemGateOption.MAYOR_NPC)) {
            mayorNpc.forceUpdateMayorForTerm(-1)
        } else {
            mayorNpc.forceUpdateMayor()
        }

        // Term runner
        Bukkit.getScheduler().runTaskTimer(this, Runnable { termService.tick() }, 20L, 20L * 30L)
    }

    override fun onDisable() {
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

        val disabled = perks.enforceSellCategoryPerkAvailability()
        if (disabled > 0) {
            logger.warning("[MayorSystem] Disabled $disabled sell-category perk(s) (no supported sell plugin found).")
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
            offlinePlayers.refreshAsync()
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
}
