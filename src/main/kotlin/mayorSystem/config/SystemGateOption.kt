package mayorSystem.config

/**
 * High-level feature gates that can be disabled when the system is paused or disabled.
 */
enum class SystemGateOption(val label: String, val description: String) {
    SCHEDULE(
        label = "Schedule",
        description = "Term/election timeline + start/end controls"
    ),
    ACTIONS(
        label = "Player Actions",
        description = "Apply/vote/stepdown/custom requests/perk selection/bio edit"
    ),
    PERKS(
        label = "Perk Effects",
        description = "Apply/refresh perks"
    ),
    MAYOR_NPC(
        label = "Mayor NPC",
        description = "Mayor statue updates"
    ),
    BROADCASTS(
        label = "Broadcasts",
        description = "Election open/new mayor announcements"
    );

    companion object {
        fun parse(raw: String?): SystemGateOption? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }

        fun all(): Set<SystemGateOption> = entries.toSet()
    }
}

