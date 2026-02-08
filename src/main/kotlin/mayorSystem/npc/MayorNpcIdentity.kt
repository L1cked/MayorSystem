package mayorSystem.npc

import net.kyori.adventure.text.Component
import java.util.UUID

data class MayorNpcIdentity(
    val uuid: UUID,
    val lastKnownName: String?,
    val displayName: Component,
    val displayNamePlain: String,
    val titleLegacy: String = "Mayor",
    val titleMini: String = "<gold>Mayor</gold>"
)

