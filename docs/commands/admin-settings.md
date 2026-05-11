# Admin Settings Commands

## Menu Shortcuts
```text
/mayor admin settings
/mayor admin settings enabled
/mayor admin settings public_enabled
/mayor admin settings pause_enabled
/mayor admin settings mayor_group
/mayor admin settings enable_options
/mayor admin settings pause_options
/mayor admin settings display
/mayor admin settings display_reward
/mayor admin settings term_length
/mayor admin settings vote_window
/mayor admin settings first_term_start
/mayor admin settings perks_per_term
/mayor admin settings election
/mayor admin settings term_extras
/mayor admin settings bonus_enabled
/mayor admin settings bonus_every
/mayor admin settings bonus_perks
/mayor admin settings playtime_minutes
/mayor admin settings apply_cost
/mayor admin settings custom_limit
/mayor admin settings custom_condition
/mayor admin settings chat_prompts
/mayor admin settings broadcasts
```

## Direct Edits
```text
/mayor admin settings enabled <true|false>
/mayor admin settings public_enabled <true|false>
/mayor admin settings pause_enabled <true|false>
/mayor admin settings mayor_group_enabled <true|false>
/mayor admin settings mayor_group <group>
/mayor admin settings enable_options <option>
/mayor admin settings pause_options <option>
/mayor admin settings term_length <ISO-8601 duration>
/mayor admin settings vote_window <ISO-8601 duration>
/mayor admin settings first_term_start <OffsetDateTime>
/mayor admin settings perks_per_term <int>
/mayor admin settings election_timing <while_term|after_term>
/mayor admin settings bonus_enabled <true|false>
/mayor admin settings bonus_every <int>
/mayor admin settings bonus_perks <int>
/mayor admin settings playtime_minutes <int>
/mayor admin settings apply_cost <number>
/mayor admin settings custom_limit <int>
/mayor admin settings custom_condition <NONE|ELECTED_ONCE|APPLY_REQUIREMENTS>
/mayor admin settings chat_prompts <bio|title|description> <int>
/mayor admin settings chat_prompt_timeout <int>
/mayor admin settings broadcasts enabled <true|false>
/mayor admin settings broadcasts mode <chat|title|both>
/mayor admin settings broadcasts event <vote|apply> <disabled|chat|title|both>
/mayor admin settings allow_vote_change <true|false>
/mayor admin settings tie_policy <SEEDED_RANDOM|INCUMBENT|EARLIEST_APPLICATION|ALPHABETICAL>
/mayor admin settings mayor_stepdown <OFF|NO_MAYOR|KEEP_MAYOR>
/mayor admin settings stepdown_reapply <true|false>
/mayor admin settings reload
```

Fallback command root: `/mayor`.
