package mayorSystem.rewards

object DisplayRewardModeLore {
    fun lines(selected: DisplayRewardMode): List<String> =
        DisplayRewardMode.entries.map { mode ->
            val arrow = if (mode == selected) "<green>></green>" else "<gray>></gray>"
            "$arrow <white>${mode.name}</white>"
        }
}
