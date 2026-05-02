package mayorSystem

import mayorSystem.cloud.CloudBootstrap
import mayorSystem.cloud.TitleCommandAliasListener
import mayorSystem.monitoring.AuditService
import mayorSystem.api.MayorSystemApi
import mayorSystem.api.MayorSystemApiImpl
import mayorSystem.config.GuiTexts
import mayorSystem.config.Messages
import mayorSystem.config.Settings
import mayorSystem.data.MayorStore
import mayorSystem.economy.EconomyHook
import mayorSystem.npc.MayorNpcService
import mayorSystem.papi.MayorPlaceholderExpansion
import mayorSystem.service.ApplyFlowService
import mayorSystem.service.AdminActions
import mayorSystem.service.ActionCoordinator
import mayorSystem.service.PlayerDisplayNameService
import mayorSystem.service.SpigotUpdateNotifier
import mayorSystem.service.MayorUsernamePrefixService
import mayorSystem.service.VoteAccessService
import mayorSystem.monitoring.HealthService
import mayorSystem.perks.PerkService
import mayorSystem.elections.TermService
import mayorSystem.perks.PerkJoinListener
import mayorSystem.service.OfflinePlayerCache
import mayorSystem.ui.GuiManager
import mayorSystem.messaging.ChatPrompts
import mayorSystem.messaging.MayorBroadcasts
import mayorSystem.config.ConfigDefaultsSync
import mayorSystem.util.PaperMainDispatcher
import mayorSystem.util.loggedTask
import mayorSystem.service.SkinService
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
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
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.FileConfiguration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class MayorPlugin : JavaPlugin() {

    lateinit var settings: Settings
        private set

    lateinit var messages: Messages
        private set

    lateinit var guiTexts: GuiTexts
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

    // Still used for custom perk requests (we'll replace with menus later)
    lateinit var prompts: ChatPrompts
        private set

    lateinit var updateNotifier: SpigotUpdateNotifier
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

    lateinit var voteAccess: VoteAccessService
        private set

    lateinit var actionCoordinator: ActionCoordinator
        private set

    lateinit var mayorNpc: MayorNpcService
        private set

    lateinit var mayorUsernamePrefix: MayorUsernamePrefixService
        private set

    lateinit var playerDisplayNames: PlayerDisplayNameService
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
        actionCoordinator = ActionCoordinator()
        reloadEverything()
        offlinePlayers = OfflinePlayerCache(this)
        server.pluginManager.registerEvents(TitleCommandAliasListener(this), this)

        gui = GuiManager(this).also { server.pluginManager.registerEvents(it, this) }
        prompts = ChatPrompts(this).also { server.pluginManager.registerEvents(it, this) }

        // Keep term-wide perks consistent for players joining while perks are active.
        server.pluginManager.registerEvents(PerkJoinListener(this), this)
        updateNotifier = SpigotUpdateNotifier(this).also { server.pluginManager.registerEvents(it, this) }

        // Services
        termService = TermService(this)
        mayorUsernamePrefix = MayorUsernamePrefixService(this).also { server.pluginManager.registerEvents(it, this); it.onEnable() }
        playerDisplayNames = PlayerDisplayNameService(this)
        apiService = MayorSystemApiImpl(this)
        server.servicesManager.register(MayorSystemApi::class.java, apiService!!, this, ServicePriority.Normal)
        applyFlow = ApplyFlowService(this)
        server.pluginManager.registerEvents(applyFlow, this)

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

        // Seed external perk sections if addon plugins enable after startup.
        server.pluginManager.registerEvents(object : Listener {
            private fun isExternalPerkPlugin(name: String): Boolean {
                return when (name) {
                    "SystemSellAddon",
                    "SystemSkyblockStyleAddon",
                    "SystemSkyblockStyleSystem",
                    "SystemSkyblockStyle" -> true
                    else -> false
                }
            }

            private fun refreshExternalPerks() {
                if (!this@MayorPlugin::perks.isInitialized) return
                if (seedExternalPerkSectionsIfMissing()) {
                    perks.reloadFromConfig()
                }
            }

            @EventHandler
            fun onPluginEnable(e: PluginEnableEvent) {
                if (isExternalPerkPlugin(e.plugin.name)) {
                    refreshExternalPerks()
                }
            }

            @EventHandler
            fun onPluginDisable(e: PluginDisableEvent) {
                if (isExternalPerkPlugin(e.plugin.name)) {
                    refreshExternalPerks()
                }
            }
        }, this)

        // One-time seed after all plugins have finished enabling.
        // This avoids missing sections if external addons load after MayorSystem.
        server.scheduler.runTask(this, loggedTask("external perk seed refresh") {
            if (this::perks.isInitialized) {
                if (seedExternalPerkSectionsIfMissing()) {
                    perks.reloadFromConfig()
                }
            }
        })

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
        if (this::mayorUsernamePrefix.isInitialized) {
            mayorUsernamePrefix.onDisable()
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
        invalidateBootstrap()
        stopTermRunner()
        reloadConfig()

        // Sync missing config values from default config structure (non-destructive)
        syncConfigDefaults()

        seedExternalPerkSectionsIfMissing()
        settings = Settings.from(config, logger)
        MayorBroadcasts.setTitleName(settings.titleName)
        MayorBroadcasts.setCommandRoot(this, settings.titleCommand, settings.titleCommandAliasEnabled)
        messages = Messages(this)
        guiTexts = GuiTexts(this)
        if (this::store.isInitialized) {
            store.shutdown()
        }
        store = MayorStore(this)
        perks = PerkService(this)
        economy = EconomyHook(this)
        if (this::audit.isInitialized) {
            audit.shutdown()
        }
        audit = AuditService(this)
        health = HealthService(this)
        adminActions = AdminActions(this)
        voteAccess = VoteAccessService(this)
        if (!this::skins.isInitialized) {
            skins = SkinService(this)
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
        if (this::mayorUsernamePrefix.isInitialized) {
            mayorUsernamePrefix.onReloadSettings()
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
        syncConfigDefaults()
        settings = Settings.from(config, logger)
        MayorBroadcasts.setTitleName(settings.titleName)
        MayorBroadcasts.setCommandRoot(this, settings.titleCommand, settings.titleCommandAliasEnabled)
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
        if (this::mayorUsernamePrefix.isInitialized) {
            mayorUsernamePrefix.onReloadSettings()
        }
        updateTermRunnerState()
        if (hasShowcase()) {
            showcase.sync()
        }
    }

    private fun syncConfigDefaults(): Boolean {
        val file = File(dataFolder, "config.yml")
        val yaml = YamlConfiguration.loadConfiguration(file)
        val hadDisplayReward = yaml.isConfigurationSection("display_reward")
        val legacyGroupEnabled = yaml.getBoolean("title.username_group_enabled", true)
        val legacyGroup = yaml.getString("title.username_group")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "mayor_current"
        val defaults = runCatching {
            getResource("config.yml")?.use { stream ->
                YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
            }
        }.getOrNull() ?: YamlConfiguration()

        val changed = ConfigDefaultsSync.syncMissingKeys(file, yaml, defaults, logger)
        if (changed && !hadDisplayReward && yaml.isConfigurationSection("display_reward")) {
            yaml.set("display_reward.enabled", legacyGroupEnabled)
            yaml.set("display_reward.default_mode", "RANK")
            yaml.set("display_reward.rank.enabled", legacyGroupEnabled)
            yaml.set("display_reward.rank.luckperms_group", legacyGroup)
            yaml.save(file)
        }
        if (changed) {
            reloadConfig()
        }
        return changed
    }

    private fun seedExternalPerkSectionsIfMissing(): Boolean {
        var changed = false
        if (seedEconomySectionIfMissing()) changed = true
        if (seedSkyblockSectionIfMissing()) changed = true
        if (changed) {
            saveConfig()
        }
        return changed
    }

    private fun seedEconomySectionIfMissing(): Boolean {
        val base = "perks.sections.economy"
        val sell = server.pluginManager.getPlugin("SystemSellAddon") ?: return false
        if (!sell.isEnabled) return false

        val sellCfg = addonConfig(sell) ?: return false

        val perksSec = sellCfg.getConfigurationSection("mayor-perks") ?: return false
        return syncExternalPerkSection(
            base = base,
            pickLimit = 0,
            displayName = "<gradient:#f7971e:#ffd200>Economy</gradient>",
            icon = "GOLD_INGOT",
            sourceConfig = sellCfg,
            perkIds = perksSec.getKeys(false),
            sourcePath = { perkId -> "mayor-perks.$perkId" },
            logMessage = "Synced economy perks from SystemSellAddon into config.yml."
        )
    }

    private fun seedSkyblockSectionIfMissing(): Boolean {
        val base = "perks.sections.skyblock_style"
        val addon = findSkyblockStyleAddon() ?: return false
        if (!addon.isEnabled) return false

        val addonCfg = addonConfig(addon) ?: return false

        val perksSec = addonCfg.getConfigurationSection("perks") ?: return false
        return syncExternalPerkSection(
            base = base,
            pickLimit = 2,
            displayName = "<gradient:#2c3e50:#4ca1af>Skyblock Style</gradient>",
            icon = "DIAMOND_PICKAXE",
            sourceConfig = addonCfg,
            perkIds = perksSec.getKeys(false),
            sourcePath = { perkId -> "perks.$perkId.meta" },
            logMessage = "Synced skyblock_style perks from ${addon.name} into config.yml."
        )
    }

    private fun addonConfig(plugin: Plugin): FileConfiguration? {
        return runCatching {
            plugin.javaClass.getMethod("getConfig").invoke(plugin) as? FileConfiguration
        }.getOrNull()
    }

    private fun syncExternalPerkSection(
        base: String,
        pickLimit: Int,
        displayName: String,
        icon: String,
        sourceConfig: FileConfiguration,
        perkIds: Set<String>,
        sourcePath: (String) -> String,
        logMessage: String
    ): Boolean {
        var changed = false
        if (config.getConfigurationSection(base) == null) {
            config.set("$base.enabled", true)
            config.set("$base.pick_limit", pickLimit)
            config.set("$base.display_name", displayName)
            config.set("$base.icon", icon)
            config.set("$base.perks", linkedMapOf<String, Any>())
            changed = true
        }

        for (perkId in perkIds) {
            val src = sourcePath(perkId)
            val dest = "$base.perks.$perkId"
            if (!config.contains(dest)) {
                config.set("$dest.enabled", sourceConfig.getBoolean("$src.enabled", true))
                config.set("$dest.display_name", sourceConfig.getString("$src.display_name") ?: "<white>$perkId</white>")
                config.set("$dest.icon", sourceConfig.getString("$src.icon") ?: "CHEST")
                config.set("$dest.lore", sourceConfig.getStringList("$src.lore"))
                config.set("$dest.admin_lore", sourceConfig.getStringList("$src.admin_lore"))
                config.set("$dest.on_start", emptyList<String>())
                config.set("$dest.on_end", emptyList<String>())
                changed = true
            } else {
                changed = syncIfMissing(
                    "$dest.display_name",
                    sourceConfig.getString("$src.display_name") ?: "<white>$perkId</white>"
                ) || changed
                changed = syncIfMissing("$dest.icon", sourceConfig.getString("$src.icon") ?: "CHEST") || changed
                changed = syncIfMissing("$dest.lore", sourceConfig.getStringList("$src.lore")) || changed
                changed = syncIfMissing("$dest.admin_lore", sourceConfig.getStringList("$src.admin_lore")) || changed
                changed = syncIfMissing("$dest.on_start", emptyList<String>()) || changed
                changed = syncIfMissing("$dest.on_end", emptyList<String>()) || changed
            }
        }

        if (changed) {
            logger.info(logMessage)
        }
        return changed
    }

    private fun syncIfMissing(path: String, value: Any?): Boolean {
        if (config.contains(path)) return false
        config.set(path, value)
        return true
    }

    private fun findSkyblockStyleAddon(): org.bukkit.plugin.Plugin? {
        val pm = server.pluginManager
        val names = listOf(
            "SystemSkyblockStyleAddon",
            "SystemSkyblockStyleSystem",
            "SystemSkyblockStyle"
        )
        for (name in names) {
            val p = pm.getPlugin(name) ?: continue
            if (p.isEnabled) return p
        }

        val normalizedTargets = setOf(
            "systemskyblockstyleaddon",
            "systemskyblockstylesystem",
            "systemskyblockstyle"
        )
        for (p in pm.plugins) {
            if (!p.isEnabled) continue
            val norm = p.name.lowercase().filter { it.isLetterOrDigit() }
            if (norm in normalizedTargets || norm.contains("skyblockstyle")) {
                return p
            }
        }
        return null
    }

    fun hasTermService(): Boolean = this::termService.isInitialized

    fun hasMayorNpc(): Boolean = this::mayorNpc.isInitialized

    fun hasMayorUsernamePrefix(): Boolean = this::mayorUsernamePrefix.isInitialized

    fun hasLeaderboardHologram(): Boolean = this::leaderboardHologram.isInitialized

    fun hasShowcase(): Boolean = this::showcase.isInitialized

    private fun startTermRunner() {
        if (termRunnerTaskId != -1) return
        termRunnerTaskId = Bukkit.getScheduler()
            .runTaskTimer(this, loggedTask("term runner") { termService.tick() }, 20L, 20L * 30L)
            .taskId
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
                logger.severe("Failed to load store. Plugin remains in LOADING/FAILED state.")
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
                if (this@MayorPlugin::mayorUsernamePrefix.isInitialized) {
                    mayorUsernamePrefix.syncAllOnline()
                }
                logger.info("Ready.")
                if (this@MayorPlugin::updateNotifier.isInitialized) {
                    updateNotifier.onPluginReady()
                }
                if (hasShowcase()) {
                    showcase.sync()
                }
            }
        }
    }

    suspend fun reloadEverythingVerified(): Boolean {
        return runCatching {
            reloadEverything()
            bootstrapJob?.join()
            isReady()
        }.getOrElse {
            logger.severe("Reload failed: ${it.message}")
            false
        }
    }

    private fun invalidateBootstrap() {
        bootstrapGen.incrementAndGet()
        bootstrapJob?.cancel()
        bootstrapJob = null
    }
}


