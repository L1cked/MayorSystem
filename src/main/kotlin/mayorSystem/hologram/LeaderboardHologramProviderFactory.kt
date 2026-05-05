package mayorSystem.hologram

import mayorSystem.MayorPlugin

object LeaderboardHologramProviderFactory {

    private val all = listOf(
        DecentHologramsHook(),
        FancyHologramsHook(),
        DisabledLeaderboardHologramProvider()
    )

    fun select(plugin: MayorPlugin): LeaderboardHologramProvider {
        val requested = plugin.config.getString("hologram.leaderboard.provider")?.lowercase()?.trim() ?: "auto"

        fun byId(id: String) = all.firstOrNull { it.id == id }

        if (requested != "auto") {
            val explicit = byId(requested)
            if (explicit != null && explicit.isAvailable(plugin)) return explicit
            plugin.logger.warning("[LeaderboardHologram] Provider '$requested' requested but unavailable; falling back to auto.")
        }

        val auto = listOf("decentholograms", "fancyholograms")
        for (id in auto) {
            val provider = byId(id) ?: continue
            if (provider.isAvailable(plugin)) return provider
        }

        return DisabledLeaderboardHologramProvider()
    }

    fun watchedPluginNames(): Set<String> =
        all.flatMapTo(linkedSetOf()) { it.pluginNames.map(String::lowercase) }
}
