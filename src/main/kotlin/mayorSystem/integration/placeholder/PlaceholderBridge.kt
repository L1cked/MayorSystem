package mayorSystem.integration.placeholder

import org.bukkit.OfflinePlayer

interface PlaceholderBridge {
    val available: Boolean

    fun apply(player: OfflinePlayer?, text: String): String
}

object NoopPlaceholderBridge : PlaceholderBridge {
    override val available: Boolean = false

    override fun apply(player: OfflinePlayer?, text: String): String = text
}

