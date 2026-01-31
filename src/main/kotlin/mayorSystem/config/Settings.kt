package mayorSystem.config

import org.bukkit.configuration.file.FileConfiguration
import java.time.Duration
import java.time.OffsetDateTime
import java.util.logging.Logger

data class Settings(
    val enabled: Boolean,
    val publicEnabled: Boolean,
    val pauseEnabled: Boolean,
    val enableOptions: Set<SystemGateOption>,
    val pauseOptions: Set<SystemGateOption>,
    val termLength: Duration,
    val voteWindow: Duration,
    val firstTermStart: OffsetDateTime,
    val perksPerTerm: Int,
    val bonusEnabled: Boolean,
    val bonusEveryX: Int,
    val perksPerBonus: Int,
    val applyPlaytimeMinutes: Int,
    val applyCost: Double,

    // Custom perk requests
    val customRequestsLimitPerTerm: Int,
    val customRequestCondition: CustomRequestCondition,

    // Election rules
    val allowVoteChange: Boolean,
    val tiePolicy: TiePolicy,
    val stepdownAllowReapply: Boolean,
    val mayorStepdownPolicy: MayorStepdownPolicy,

    // UX
    val chatPromptTimeoutSeconds: Int,
    val chatPromptMaxBioChars: Int,
    val chatPromptMaxTitleChars: Int,
    val chatPromptMaxDescChars: Int,

    // Economy
    val sellAllBonusStacks: Boolean
) {
    fun perksAllowed(termIndex: Int): Int {
        if (!bonusEnabled) return perksPerTerm
        if (bonusEveryX <= 0) return perksPerTerm
        val humanTermNumber = termIndex + 1
        return if (humanTermNumber % bonusEveryX == 0) perksPerBonus else perksPerTerm
    }

    fun isPaused(option: SystemGateOption): Boolean =
        pauseEnabled && pauseOptions.contains(option)

    fun isDisabled(option: SystemGateOption): Boolean =
        !enabled && enableOptions.contains(option)

    fun isBlocked(option: SystemGateOption): Boolean =
        isPaused(option) || isDisabled(option)

    companion object {
        fun from(cfg: FileConfiguration, log: Logger? = null): Settings {
            val enabled = cfg.getBoolean("enabled", true)
            val publicEnabled = cfg.getBoolean("public_enabled", true)
            val pauseEnabled = cfg.getBoolean("pause.enabled", false)
            val allOptions = SystemGateOption.all()
            val enableOptions = parseOptions(
                raw = cfg.getStringList("enable_options"),
                hasKey = cfg.contains("enable_options"),
                allOptions = allOptions,
                log = log,
                label = "enable_options"
            )
            val pauseOptions = parseOptions(
                raw = cfg.getStringList("pause.options"),
                hasKey = cfg.contains("pause.options"),
                allOptions = allOptions,
                log = log,
                label = "pause.options"
            )
            val termLength = Duration.parse(cfg.getString("term.length") ?: "P14D")
            val voteWindow = Duration.parse(cfg.getString("term.vote_window") ?: "P3D")
            val firstStartRaw = cfg.getString("term.first_term_start")
            val fallbackStart = OffsetDateTime.now()
                .plusDays(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0)

            val firstStart = runCatching {
                require(!firstStartRaw.isNullOrBlank()) { "Missing term.first_term_start" }
                OffsetDateTime.parse(firstStartRaw)
            }.getOrElse { err ->
                (log ?: java.util.logging.Logger.getLogger("MayorSystem")).warning(
                    "Config key 'term.first_term_start' is missing or invalid. " +
                        "Using fallback start time: $fallbackStart. " +
                        "Fix config.yml to remove this warning. Cause: ${err.message}"
                )
                fallbackStart
            }
            val perksPer = cfg.getInt("term.perks_per_term", 2)

            val bonusEnabled = cfg.getBoolean("term.bonus_term.enabled", false)
            val bonusEveryX = cfg.getInt("term.bonus_term.every_x_terms", 4)
            val perksPerBonus = cfg.getInt("term.bonus_term.perks_per_bonus_term", perksPer)

            val playtime = cfg.getInt("apply.playtime_minutes", 0)
            val cost = cfg.getDouble("apply.cost", 0.0)

            val customRequestsLimit = cfg.getInt("custom_requests.limit_per_term", 2)
                .coerceAtLeast(0)

            val conditionStr = cfg.getString("custom_requests.request_condition", "APPLY_REQUIREMENTS") ?: "APPLY_REQUIREMENTS"
            val customCondition = runCatching { CustomRequestCondition.valueOf(conditionStr.uppercase()) }
                .getOrElse { CustomRequestCondition.APPLY_REQUIREMENTS }

            val allowVoteChange = cfg.getBoolean("election.allow_vote_change", true)

            val tiePolicyStr = cfg.getString("election.tie_policy", "SEEDED_RANDOM") ?: "SEEDED_RANDOM"
            val tiePolicy = runCatching { TiePolicy.valueOf(tiePolicyStr.uppercase()) }
                .getOrElse { TiePolicy.SEEDED_RANDOM }

            val stepdownAllowReapply = cfg.getBoolean("election.stepdown.allow_reapply", false)
            val mayorStepdownRaw = cfg.getString("election.mayor_stepdown", "OFF") ?: "OFF"
            val mayorStepdownPolicy = runCatching { MayorStepdownPolicy.valueOf(mayorStepdownRaw.uppercase()) }
                .getOrElse { MayorStepdownPolicy.OFF }

            val chatPromptTimeoutSeconds = cfg.getInt("ux.chat_prompt_timeout_seconds", 300)
                .coerceAtLeast(30)

            val chatPromptMaxBioChars = cfg.getInt("ux.chat_prompts.max_length.bio", 50)
                .coerceIn(1, 500)
            val chatPromptMaxTitleChars = cfg.getInt("ux.chat_prompts.max_length.title", 50)
                .coerceIn(1, 500)
            val chatPromptMaxDescChars = cfg.getInt("ux.chat_prompts.max_length.description", 50)
                .coerceIn(1, 500)

            val sellAllBonusStacks = cfg.getBoolean("sell_bonus.all_bonus_stack", true)

            return Settings(
                enabled = enabled,
                publicEnabled = publicEnabled,
                pauseEnabled = pauseEnabled,
                enableOptions = enableOptions,
                pauseOptions = pauseOptions,
                termLength = termLength,
                voteWindow = voteWindow,
                firstTermStart = firstStart,
                perksPerTerm = perksPer,
                bonusEnabled = bonusEnabled,
                bonusEveryX = bonusEveryX,
                perksPerBonus = perksPerBonus,
                applyPlaytimeMinutes = playtime,
                applyCost = cost,
                customRequestsLimitPerTerm = customRequestsLimit,
                customRequestCondition = customCondition,
                allowVoteChange = allowVoteChange,
                tiePolicy = tiePolicy,
                stepdownAllowReapply = stepdownAllowReapply,
                mayorStepdownPolicy = mayorStepdownPolicy,
                chatPromptTimeoutSeconds = chatPromptTimeoutSeconds,
                chatPromptMaxBioChars = chatPromptMaxBioChars,
                chatPromptMaxTitleChars = chatPromptMaxTitleChars,
                chatPromptMaxDescChars = chatPromptMaxDescChars,
                sellAllBonusStacks = sellAllBonusStacks
            )
        }

        private fun parseOptions(
            raw: List<String>,
            hasKey: Boolean,
            allOptions: Set<SystemGateOption>,
            log: Logger?,
            label: String
        ): Set<SystemGateOption> {
            if (raw.isEmpty()) return if (hasKey) emptySet() else allOptions
            val out = mutableSetOf<SystemGateOption>()
            for (entry in raw) {
                val opt = SystemGateOption.parse(entry)
                if (opt == null) {
                    log?.warning("Unknown $label option: $entry")
                    continue
                }
                out += opt
            }
            return if (out.isEmpty()) {
                if (hasKey) emptySet() else allOptions
            } else {
                out
            }
        }
    }
}
