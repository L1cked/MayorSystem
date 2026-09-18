package mayorSystem.platform.paper

import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID

/** Optional Floodgate bridge kept reflection-only so MayorSystem remains standalone. */
object BedrockPlayerDetector {
    private val bridge: FloodgateBridge? by lazy(::resolveBridge)

    fun isBedrock(player: Player): Boolean =
        bridge?.isBedrock(player.uniqueId) == true

    private fun resolveBridge(): FloodgateBridge? = runCatching {
        val apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
        val instance = apiClass.getMethod("getInstance").invoke(null)
        val isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID::class.java)
        FloodgateBridge(instance, isFloodgatePlayer)
    }.getOrNull()

    private class FloodgateBridge(
        private val instance: Any,
        private val isFloodgatePlayer: Method
    ) {
        fun isBedrock(uuid: UUID): Boolean =
            runCatching { isFloodgatePlayer.invoke(instance, uuid) as? Boolean }.getOrNull() == true
    }
}
