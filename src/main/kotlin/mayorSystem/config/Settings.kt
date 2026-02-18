package mayorSystem.config

import org.bukkit.configuration.file.FileConfiguration
import java.time.Duration
import java.time.OffsetDateTime
import java.util.logging.Logger

data class Settings(
    val titleName: String,
    val titlePlayerPrefix: String,
    val usernameGroupEnabled: Boolean,
    val usernameGroup: String,
    val chatPrefix: String,
    val titleCommand: String,
    val titleCommandAliasEnabled: Boolean,
    val enabled: Boolean,
    val publicEnabled: Boolean,
    val pauseEnabled: Boolean,
    val enableOptions: Set<SystemGateOption>,
    val pauseOptions: Set<SystemGateOption>,
    val termLength: Duration,
    val voteWindow: Duration,
    val electionAfterTermEnd: Boolean,
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

) {
    fun titleNameLower(): String = titleName.lowercase()

    fun applyTitleTokens(raw: String): String {
        return raw
            .replace("%title_name%", titleName)
            .replace("%title_name_lower%", titleNameLower())
            .replace("%title_command%", titleCommand)
    }

    fun resolvedTitlePlayerPrefix(): String = applyTitleTokens(titlePlayerPrefix)

    fun resolvedChatPrefix(): String = applyTitleTokens(chatPrefix)

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
            val titleName = cfg.getString("title.name")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Mayor"
            val titlePlayerPrefix = cfg.getString("title.player_prefix")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "<gold><bold>%title_name%</bold></gold>"
            val usernameGroupEnabled = cfg.getBoolean("title.username_group_enabled", true)
            val usernameGroup = cfg.getString("title.username_group")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "mayor_current"
            val chatPrefix = cfg.getString("title.chat_prefix")
                ?.takeIf { it.isNotBlank() }
                ?: "<gold><bold>%title_name%</bold></gold> <dark_gray>>></dark_gray> "
            val titleCommand = sanitizeCommandRoot(titleName)
            val titleCommandAliasEnabled = cfg.getBoolean("title.command_alias_enabled", true)

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
            val termLength = parseDuration(cfg, "term.length", "P14D", log)
            val voteWindow = parseDuration(cfg, "term.vote_window", "P3D", log)
            val electionAfterTermEnd = cfg.getBoolean("term.election_after_term_end", false)
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

            return Settings(
                titleName = titleName,
                titlePlayerPrefix = titlePlayerPrefix,
                usernameGroupEnabled = usernameGroupEnabled,
                usernameGroup = usernameGroup,
                chatPrefix = chatPrefix,
                titleCommand = titleCommand,
                titleCommandAliasEnabled = titleCommandAliasEnabled,
                enabled = enabled,
                publicEnabled = publicEnabled,
                pauseEnabled = pauseEnabled,
                enableOptions = enableOptions,
                pauseOptions = pauseOptions,
                termLength = termLength,
                voteWindow = voteWindow,
                electionAfterTermEnd = electionAfterTermEnd,
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
                chatPromptMaxDescChars = chatPromptMaxDescChars
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

        private fun parseDuration(
            cfg: FileConfiguration,
            path: String,
            defaultIso: String,
            log: Logger?
        ): Duration {
            val raw = cfg.getString(path)
            val fallback = Duration.parse(defaultIso)
            if (raw.isNullOrBlank()) return fallback
            return runCatching { Duration.parse(raw) }.getOrElse { err ->
                log?.warning("Invalid duration for '$path': '$raw'. Using $defaultIso. Cause: ${err.message}")
                fallback
            }
        }

        private fun sanitizeCommandRoot(raw: String): String {
            val sanitized = raw
                .lowercase()
                .replace(Regex("[^a-z]"), "")
            return if (sanitized.isBlank()) "mayor" else sanitized
        }
    }
}

