# Configuration

MayorSystem writes `config.yml`, `messages.yml`, and `gui.yml` on first startup. Missing keys are restored on startup and reload without overwriting existing user values.

## Core Settings
- `enabled`: master plugin switch.
- `public_enabled`: disables public player access while keeping admin access.
- `title.name`: display title used across messages and menus.
- `title.command_alias_enabled`: enables the sanitized title command alias while keeping `/mayor`.
- `term.length`, `term.vote_window`, `term.first_term_start`, `term.perks_per_term`: base election schedule.
- `term.bonus_term.*`: bonus term schedule and extra perk count.
- `apply.playtime_minutes`, `apply.cost`: candidate requirements.
- `election.allow_vote_change`, `election.tie_policy`, `election.mayor_stepdown`, `election.stepdown.allow_reapply`: election rules.

## Rewards And Perks
- `perks.enabled`: master perk catalog switch.
- `perks.sections.*`: configured perk sections and perks.
- `custom_requests.*`: custom perk request limits and requirements.
- `display_reward.*`: LuckPerms rank and DeluxeTags tag reward settings.
- `display_reward.targets.tracks`, `display_reward.targets.groups`, `display_reward.targets.users`: reward mode overrides.

## Displays
- `showcase.*`: chooses switching or individual display behavior.
- `npc.mayor.*`: Mayor NPC settings.
- `hologram.leaderboard.*`: leaderboard hologram settings.

## Safety
- `enable_options`: systems blocked when `enabled=false`.
- `pause.enabled` and `pause.options`: scheduling and subsystem pause controls.
- `perks.command_execution.enable_console_commands`: controls command execution for non-effect perks.
- `perks.command_execution.allow_roots`: allowlist for command roots that are blocked by default.

## Examples
- [Example config.yml](examples/config.yml)
- [Example messages.yml](examples/messages.yml)
- [Example gui.yml](examples/gui.yml)
