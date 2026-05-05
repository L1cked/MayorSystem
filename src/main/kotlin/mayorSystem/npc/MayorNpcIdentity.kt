package mayorSystem.npc

import net.kyori.adventure.text.Component
import java.util.UUID

data class MayorNpcIdentity(
    val uuid: UUID,
    val lastKnownName: String?,
    val isBedrockPlayer: Boolean = false,
    val skinTextureValue: String? = null,
    val skinTextureSignature: String? = null,
    val skinTextureUrl: String? = null,
    val displayName: Component,
    val displayNamePlain: String,
    val usesLuckPermsPrefix: Boolean = false,
    val titleLegacy: String = "Mayor",
    val titleMini: String = "<gold>Mayor</gold>"
)

