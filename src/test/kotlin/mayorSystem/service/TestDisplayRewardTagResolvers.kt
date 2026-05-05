package mayorSystem.service

import net.kyori.adventure.text.Component
import java.util.UUID

object NoDisplayRewardTagResolver : DisplayRewardTagPrefixResolver {
    override fun resolvePrefix(uuid: UUID): Component? = null
    override fun clear() = Unit
    override fun clear(uuid: UUID) = Unit
}
