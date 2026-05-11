# Admin Settings Commands

## Menu Shortcuts
```text
/%title_command% admin settings
/%title_command% admin settings enabled
/%title_command% admin settings public_enabled
/%title_command% admin settings pause_enabled
/%title_command% admin settings mayor_group
/%title_command% admin settings enable_options
/%title_command% admin settings pause_options
/%title_command% admin settings display
/%title_command% admin settings display_reward
/%title_command% admin settings term_length
/%title_command% admin settings vote_window
/%title_command% admin settings first_term_start
/%title_command% admin settings perks_per_term
/%title_command% admin settings election
/%title_command% admin settings term_extras
/%title_command% admin settings bonus_enabled
/%title_command% admin settings bonus_every
/%title_command% admin settings bonus_perks
/%title_command% admin settings playtime_minutes
/%title_command% admin settings apply_cost
/%title_command% admin settings custom_limit
/%title_command% admin settings custom_condition
/%title_command% admin settings chat_prompts
/%title_command% admin settings broadcasts
```

## Direct Edits
```text
/%title_command% admin settings enabled <true|false>
/%title_command% admin settings public_enabled <true|false>
/%title_command% admin settings pause_enabled <true|false>
/%title_command% admin settings mayor_group_enabled <true|false>
/%title_command% admin settings mayor_group <group>
/%title_command% admin settings enable_options <option>
/%title_command% admin settings pause_options <option>
/%title_command% admin settings term_length <ISO-8601 duration>
/%title_command% admin settings vote_window <ISO-8601 duration>
/%title_command% admin settings first_term_start <OffsetDateTime>
/%title_command% admin settings perks_per_term <int>
/%title_command% admin settings election_timing <while_term|after_term>
/%title_command% admin settings bonus_enabled <true|false>
/%title_command% admin settings bonus_every <int>
/%title_command% admin settings bonus_perks <int>
/%title_command% admin settings playtime_minutes <int>
/%title_command% admin settings apply_cost <number>
/%title_command% admin settings custom_limit <int>
/%title_command% admin settings custom_condition <NONE|ELECTED_ONCE|APPLY_REQUIREMENTS>
/%title_command% admin settings chat_prompts <bio|title|description> <int>
/%title_command% admin settings chat_prompt_timeout <int>
/%title_command% admin settings broadcasts enabled <true|false>
/%title_command% admin settings broadcasts mode <chat|title|both>
/%title_command% admin settings broadcasts event <vote|apply> <disabled|chat|title|both>
/%title_command% admin settings allow_vote_change <true|false>
/%title_command% admin settings tie_policy <SEEDED_RANDOM|INCUMBENT|EARLIEST_APPLICATION|ALPHABETICAL>
/%title_command% admin settings mayor_stepdown <OFF|NO_MAYOR|KEEP_MAYOR>
/%title_command% admin settings stepdown_reapply <true|false>
/%title_command% admin settings reload
```

Fallback command root: `/mayor`.
