package mayorSystem.npc.provider

import mayorSystem.MayorPlugin

object MayorNpcProviderFactory {

    private val ALL = listOf(
        CitizensMayorNpcProvider(),
        FancyNpcsMayorNpcProvider(),
        DisabledMayorNpcProvider())

    fun select(plugin: MayorPlugin): MayorNpcProvider {
        val requested = plugin.config.getString("npc.mayor.provider")?.lowercase()?.trim() ?: "auto"

        fun byId(id: String) = ALL.firstOrNull { it.id == id }

        // Explicit override
        if (requested != "auto") {
            val explicit = byId(requested)
            if (explicit != null && explicit.isAvailable(plugin)) return explicit
            plugin.logger.warning("[MayorNPC] Provider '$requested' requested but unavailable; falling back to auto.")
        }

        // Auto selection priority:
        // Citizens (real entity, best compatibility) -> FancyNpcs (packet)
        val auto = listOf("citizens", "fancynpcs")
        for (id in auto) {
            val p = byId(id) ?: continue
            if (p.isAvailable(plugin)) return p
        }

        // No supported NPC plugin available.
        return DisabledMayorNpcProvider()
    }
}

