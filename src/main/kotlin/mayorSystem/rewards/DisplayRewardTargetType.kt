package mayorSystem.rewards

enum class DisplayRewardTargetType(val configKey: String, val label: String) {
    TRACK("tracks", "Track"),
    GROUP("groups", "Group"),
    USER("users", "User");

    val configPath: String = "display_reward.targets.$configKey"

    companion object {
        fun parse(raw: String?): DisplayRewardTargetType? =
            entries.firstOrNull { type ->
                type.name.equals(raw?.trim(), ignoreCase = true) ||
                    type.configKey.equals(raw?.trim(), ignoreCase = true)
            }
    }
}
