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
import mayorSystem.ui.GuiManager
import mayorSystem.ux.ChatPrompts
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

    override fun onEnable() {
        saveDefaultConfig()
        reloadEverything()

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
        termService.tick()

        // Rebuild active potion effects after restart/reload (no other commands).
        perks.rebuildActiveEffectsForTerm(termService.computeNow().first)

        // Ensure the NPC reflects the current mayor after catch-up.
        mayorNpc.forceUpdateMayor()

        // Term runner
        Bukkit.getScheduler().runTaskTimer(this, Runnable { termService.tick() }, 20L, 20L * 30L)
    }

    override fun onDisable() {
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
            perks.rebuildActiveEffectsForTerm(termService.computeNow().first)
        }
        if (this::mayorNpc.isInitialized) {
            mayorNpc.onReload()
        }
    }
}
