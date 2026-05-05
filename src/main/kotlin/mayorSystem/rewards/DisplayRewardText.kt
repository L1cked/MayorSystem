package mayorSystem.rewards

import mayorSystem.messaging.DisplayTextParser

object DisplayRewardText {
    fun previewMini(raw: String): String =
        DisplayTextParser.mini(raw)

    fun plain(raw: String): String =
        DisplayTextParser.plain(raw).ifBlank { DisplayTextParser.escapePlain(raw) }
}
