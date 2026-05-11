package mayorSystem.platform.paper

import mayorSystem.MayorPlugin
import mayorSystem.api.MayorSystemApi
import mayorSystem.platform.paper.api.MayorSystemApiImpl
import mayorSystem.platform.paper.command.CloudBootstrap
import mayorSystem.platform.paper.command.TitleCommandAliasListener
import mayorSystem.papi.MayorPlaceholderExpansion
import mayorSystem.perks.PerkJoinListener
import mayorSystem.util.loggedTask
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.plugin.ServicePriority

class PaperListenerRegistrar(
    private val plugin: MayorPlugin,
    private val services: MayorServices,
    private val externalPerks: ExternalPerkSectionSeeder
) {

    fun registerStartupListeners() {
        plugin.server.pluginManager.registerEvents(TitleCommandAliasListener(plugin), plugin)
        plugin.server.pluginManager.registerEvents(services.gui, plugin)
        plugin.server.pluginManager.registerEvents(services.prompts, plugin)
        plugin.server.pluginManager.registerEvents(PerkJoinListener(plugin), plugin)
        plugin.server.pluginManager.registerEvents(services.updateNotifier, plugin)
        plugin.server.pluginManager.registerEvents(services.mayorUsernamePrefix, plugin)
        plugin.server.pluginManager.registerEvents(services.displayRewardTags, plugin)
        plugin.server.pluginManager.registerEvents(services.applyFlow, plugin)
        plugin.server.pluginManager.registerEvents(services.mayorNpc, plugin)
    }

    fun registerApiService(apiService: MayorSystemApiImpl): MayorSystemApiImpl {
        plugin.server.servicesManager.register(MayorSystemApi::class.java, apiService, plugin, ServicePriority.Normal)
        return apiService
    }

    fun unregisterApiService(apiService: MayorSystemApiImpl?) {
        apiService?.let { plugin.server.servicesManager.unregister(MayorSystemApi::class.java, it) }
    }

    fun registerCommands() {
        CloudBootstrap.enable(plugin)
    }

    fun registerPlaceholderExpansion(): MayorPlaceholderExpansion? {
        if (plugin.server.pluginManager.getPlugin("PlaceholderAPI") == null) return null
        return MayorPlaceholderExpansion(plugin).also { it.register() }
    }

    fun registerEconomyRefreshListener() {
        plugin.server.pluginManager.registerEvents(object : Listener {
            private fun isEconomyService(service: Class<*>?): Boolean =
                service?.name == "net.milkbowl.vault.economy.Economy"

            private fun refreshEconomyAfterServiceChange() {
                plugin.server.scheduler.runTask(plugin, plugin.loggedTask("economy service refresh") {
                    services.economy.refresh()
                })
            }

            @EventHandler
            fun onServiceRegister(e: ServiceRegisterEvent) {
                if (isEconomyService(e.provider.service)) {
                    refreshEconomyAfterServiceChange()
                }
            }

            @EventHandler
            fun onServiceUnregister(e: ServiceUnregisterEvent) {
                if (isEconomyService(e.provider.service)) {
                    refreshEconomyAfterServiceChange()
                }
            }
        }, plugin)
    }

    fun registerExternalPerkRefreshListener() {
        plugin.server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onPluginEnable(e: PluginEnableEvent) {
                if (externalPerks.isExternalPerkPlugin(e.plugin.name)) {
                    scheduleExternalPerkRefresh()
                }
            }

            @EventHandler
            fun onPluginDisable(e: PluginDisableEvent) {
                if (services.hasAddonPerkSources()) {
                    services.addonPerkSources.unregisterOwner(e.plugin)
                }
                if (externalPerks.isExternalPerkPlugin(e.plugin.name)) {
                    scheduleExternalPerkRefresh()
                }
            }
        }, plugin)
    }

    fun scheduleOneTimeExternalPerkRefresh() {
        scheduleExternalPerkRefresh()
    }

    private fun scheduleExternalPerkRefresh() {
        plugin.server.scheduler.runTask(plugin, plugin.loggedTask("external perk seed refresh") {
            refreshExternalPerks()
        })
    }

    private fun refreshExternalPerks() {
        if (!services.hasPerks()) return
        if (externalPerks.seedExternalPerkSectionsIfMissing()) {
            services.perks.reloadFromConfig()
        }
    }
}
