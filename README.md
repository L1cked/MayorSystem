# MayorSystem

MayorSystem is a Paper 1.21.8 plugin that runs server elections, crowns a mayor, and lets that mayor pick server-wide perks. It includes optional displays (NPC or hologram), sell bonuses, and full admin tooling.

![MayorSystem Banner](docs/images/banner.png)
<!-- TODO: Replace with your banner image (recommended size: 1280x320) -->

![Status Badge](https://img.shields.io/badge/status-active-brightgreen)
![Paper Badge](https://img.shields.io/badge/paper-1.21.8-blue)
![Java Badge](https://img.shields.io/badge/java-21-orange)
<!-- TODO: Replace badges with your preferred set and links -->

> **Limited Use License (Plugin Use Only)**
> You may run the compiled plugin on Minecraft servers and redistribute the unmodified jar.
> No rights are granted to use or modify the source code or distribute modified binaries.

---

## At a Glance
- Scheduled terms with a configurable vote window (ISO-8601 durations)
- Candidate applications with playtime and cost requirements
- Perk catalog with sections, pick limits, and custom perk requests
- Bonus terms every N terms
- Public toggle and pause modes to selectively freeze systems
- Sell bonuses (SystemSellAddon integration or command-based detection)
- Skyblock-style perk mechanics via SystemSkyblockStyleAddon (optional)
- Mayor NPC statue and optional leaderboard hologram (DecentHolograms)
- Admin menus, audit log, health checks, and force-election tools
- MiniMessage formatting with optional PlaceholderAPI

---

## Screenshots
![Main Menu](docs/images/main-menu.png)

![Vote Menu](docs/images/vote-menu.png)

![Mayor Card](docs/images/mayor-card.png)

![Mayor NPC](docs/images/mayor-npc.png)

![Leaderboard Hologram](docs/images/leaderboard-hologram.png)

![Candidate Menu](docs/images/candidate-menu.png)

![Perk Catalog](docs/images/perk-catalog.png)

![Admin Menu](docs/images/admin-menu.png)

![Election Settings](docs/images/election-settings.png)

![Audit Log](docs/images/audit-log.png)

---

## Requirements
- Paper 1.21.8 (API 1.21)
- Java 21

---

## Quick Start
1. Drop the jar into `plugins/` and start the server once.
2. Open `plugins/MayorSystem/config.yml` and set `term.first_term_start` to a real future date/time.
   Example: `2026-03-01T00:00:00-05:00`
3. (Optional) Adjust `term.length`, `term.vote_window`, and `term.perks_per_term`.
4. (Optional) Install integrations (Vault + a compatible economy plugin, Citizens/FancyNpcs, SystemSellAddon, SystemSkyblockStyleAddon, PlaceholderAPI, DecentHolograms).
5. Join in-game and run `/mayor`.

Tip: The default `term.first_term_start` is set far in the future so nothing starts until you set it.

---

## How Elections Work (30-second overview)
- Terms run on a schedule starting at `term.first_term_start`.
- The vote window opens `term.vote_window` before a term starts.
- Players apply to be candidates if they meet playtime and cost requirements.
- Players vote, a mayor is elected, and the mayor picks perks for the term.
- Bonus terms can grant extra perks every N terms.

---

## Display: Mayor NPC + Leaderboard Hologram
MayorSystem supports two optional displays that can be used together or in rotation.

### Mayor NPC (Citizens or FancyNpcs)
- Spawn/move: `/mayor admin npc spawn`
- Remove: `/mayor admin npc remove`
- Force update: `/mayor admin npc update`

### Leaderboard Hologram (DecentHolograms)
- Spawn/move: `/mayor admin hologram spawn`
- Remove: `/mayor admin hologram remove`
- Force update: `/mayor admin hologram update`

### Showcase Mode
Set how displays behave in `config.yml`:
- `showcase.mode: SWITCHING` will show the hologram during elections and the NPC once a mayor is elected.
- `showcase.mode: INDIVIDUAL` keeps each display active at its own location.

In SWITCHING mode, if no mayor is elected yet, the hologram stays active. The hologram uses the NPC location in this mode.

You can also switch mode in-game:
- `/mayor admin display mode <switching|individual>`

---

## SystemSellAddon Integration (Recommended)
Note: SystemSellAddon may only be used if you have paid for it or received explicit authorization from the copyright holder.
If SystemSellAddon is installed, MayorSystem applies sell bonuses directly to /sell payouts with no extra setup.
- Bonuses stack on top of SystemSellAddon payouts
- Category and total bonuses are passed through cleanly
- No extra commands or config are needed

If you do not use SystemSellAddon, MayorSystem can still detect sell commands via `sell_bonus.commands`.
You can disable the command-based fallback with `sell_bonus.fallback_enabled: false`.

---

## SystemSkyblockStyleAddon Integration
Note: SystemSkyblockStyleAddon may only be used if you have paid for it or received explicit authorization from the copyright holder.
MayorSystem can drive the Skyblock-style perk mechanics provided by SystemSkyblockStyleAddon (also referred to as SystemSkyblockStyleSystem).
Enable the `skyblock_style` section in `config.yml`, elect a mayor, and the addon will apply mechanics for any active perks.

---

## Commands

### Public / Player
```
/mayor
/mayor status
/mayor apply
/mayor vote <candidate>
/mayor vote            # opens the vote menu
/mayor candidate
/mayor stepdown
```

### Admin: Core
```
/mayor admin
/mayor admin open <menuId>
/mayor admin system
/mayor admin system toggle
/mayor admin system refresh_offline_cache
```

### Admin: Display
```
/mayor admin display
/mayor admin display mode <switching|individual>
```

### Admin: NPC
```
/mayor admin npc spawn
/mayor admin npc remove
/mayor admin npc update
```

### Admin: Hologram (DecentHolograms)
```
/mayor admin hologram spawn
/mayor admin hologram remove
/mayor admin hologram update
```

### Admin: Governance
```
/mayor admin governance
```

### Admin: Economy
```
/mayor admin economy
```

### Admin: Messaging
```
/mayor admin messaging
```

### Admin: Monitoring
```
/mayor admin monitoring
/mayor admin audit
/mayor admin health
```

### Admin: Maintenance
```
/mayor admin maintenance
/mayor admin debug
/mayor admin reload
/mayor admin settings reload
```

### Admin: Candidates
```
/mayor admin candidates
/mayor admin candidates remove <player>
/mayor admin candidates restore <player>
/mayor admin candidates process <player>
/mayor admin candidates applyban perm <player>
/mayor admin candidates applyban temp <player> <days>
/mayor admin candidates applyban clear <player>
```

### Admin: Perks
```
/mayor admin perks
/mayor admin perks refresh
/mayor admin perks refresh <player|--all|all>
/mayor admin perks requests
/mayor admin perks requests approve <id>
/mayor admin perks requests deny <id>
/mayor admin customperk <id> <approve|deny>
/mayor admin perks catalog
/mayor admin perks catalog section <section> <toggle|on|off>
/mayor admin perks catalog perk <section> <perk> <toggle|on|off>
```

### Admin: Election
```
/mayor admin election
/mayor admin election start
/mayor admin election end
/mayor admin election clear
/mayor admin election elect
/mayor admin election elect set <player>
/mayor admin election elect clear
/mayor admin election elect now <player>
```

### Admin: Settings (menu shortcuts)
```
/mayor admin settings
/mayor admin settings enabled
/mayor admin settings public_enabled
/mayor admin settings pause_enabled
/mayor admin settings enable_options
/mayor admin settings pause_options
/mayor admin settings display
/mayor admin settings term_length
/mayor admin settings vote_window
/mayor admin settings first_term_start
/mayor admin settings perks_per_term
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

### Admin: Settings (direct commands)
```
/mayor admin settings enabled <true|false>
/mayor admin settings public_enabled <true|false>
/mayor admin settings pause_enabled <true|false>
/mayor admin settings enable_options <option>
/mayor admin settings pause_options <option>
/mayor admin settings term_length <ISO-8601 duration>
/mayor admin settings vote_window <ISO-8601 duration>
/mayor admin settings first_term_start <OffsetDateTime>
/mayor admin settings perks_per_term <int>
/mayor admin settings bonus_enabled <true|false>
/mayor admin settings bonus_every <int>
/mayor admin settings bonus_perks <int>
/mayor admin settings playtime_minutes <int>
/mayor admin settings apply_cost <number>
/mayor admin settings custom_limit <int>
/mayor admin settings custom_condition <NONE|ELECTED_ONCE|APPLY_REQUIREMENTS>
/mayor admin settings chat_prompts <bio|title|description> <int>
/mayor admin settings chat_prompt_timeout <int>
/mayor admin settings allow_vote_change <true|false>
/mayor admin settings tie_policy <SEEDED_RANDOM|INCUMBENT|EARLIEST_APPLICATION|ALPHABETICAL>
/mayor admin settings mayor_stepdown <OFF|NO_MAYOR|KEEP_MAYOR>
/mayor admin settings stepdown_reapply <true|false>
/mayor admin settings sell_all_stack <true|false>
/mayor admin settings reload
```

---

## Permissions

### Player
| Node | Default | Description |
| --- | --- | --- |
| `mayor.use` | true | Access the `/mayor` menu |
| `mayor.apply` | true | Apply to be a candidate |
| `mayor.vote` | true | Vote in elections |
| `mayor.candidate` | true | Candidate actions (perk selection, custom requests) |

### Admin
| Node | Default | Description |
| --- | --- | --- |
| `mayor.admin.access` | op | Root admin access (child of any admin node) |
| `mayor.admin.panel.open` | op | Open admin panel |
| `mayor.admin.system.toggle` | op | Toggle public access |
| `mayor.admin.candidates.remove` | op | Remove a candidate |
| `mayor.admin.candidates.restore` | op | Restore a candidate |
| `mayor.admin.candidates.process` | op | Mark candidate as in-process |
| `mayor.admin.candidates.applyban` | op | Manage apply bans |
| `mayor.admin.perks.refresh` | op | Refresh active perks |
| `mayor.admin.perks.requests` | op | Approve/deny custom perk requests |
| `mayor.admin.perks.catalog` | op | Enable/disable perk sections or perks |
| `mayor.admin.governance.edit` | op | Edit governance policies |
| `mayor.admin.messaging.edit` | op | Edit messaging settings |
| `mayor.admin.economy.edit` | op | Edit economy integration settings |
| `mayor.admin.election.start` | op | Force-start election |
| `mayor.admin.election.end` | op | Force-end election |
| `mayor.admin.election.clear` | op | Clear term overrides |
| `mayor.admin.election.elect` | op | Force-elect a player |
| `mayor.admin.settings.edit` | op | Edit settings |
| `mayor.admin.settings.reload` | op | Reload config + store |
| `mayor.admin.maintenance.reload` | op | Reload config + store |
| `mayor.admin.maintenance.debug` | op | Access maintenance tools |
| `mayor.admin.audit.view` | op | View audit log |
| `mayor.admin.health.view` | op | Run health checks |
| `mayor.admin.npc.mayor` | op | Spawn/remove/update Mayor NPC |
| `mayor.admin.hologram.leaderboard` | op | Spawn/remove/update leaderboard hologram |

### Legacy (backwards compatibility)
```
mayor.admin
mayor.admin.perk
mayor.admin.toggle
mayor.admin.candidates
mayor.admin.perks
mayor.admin.election
mayor.admin.settings
mayor.admin.audit
mayor.admin.health
```

---

## Menus (GUI)

### Public / Player
- MainMenu: entry point + quick status
- StatusMenu: term timeline + election window
- VoteMenu / VoteConfirmMenu: pick + confirm a vote
- CandidateMenu: candidate hub
- CandidatePerkCatalogMenu / CandidatePerkSectionMenu / CandidatePerksViewMenu
- CandidateCustomPerksMenu: request custom perks
- ApplySectionsMenu / ApplyPerksMenu / ApplyConfirmMenu
- StepDownConfirmMenu
- MayorProfileMenu (opened from the Mayor NPC)

### Admin
- AdminMenu: staff home
- AdminDebugMenu (reload, offline cache, reset)
- AdminMonitoringMenu / AdminAuditMenu / AdminHealthMenu
- AdminCandidatesMenu / ConfirmRemoveCandidateMenu
- AdminApplyBanSearchMenu / AdminApplyBanTypeMenu / AdminApplyBanDurationMenu
- AdminPerksMenu / AdminPerkCatalogMenu / AdminPerkSectionMenu / AdminPerkRequestsMenu / AdminPerkRefreshMenu
- AdminElectionMenu / AdminElectionSettingsMenu
- AdminForceElectMenu / AdminForceElectSectionsMenu / AdminForceElectPerksMenu / AdminForceElectConfirmMenu
- AdminSettingsMenu / AdminSettingsGeneralMenu / AdminSettingsEnableOptionsMenu / AdminSettingsPauseOptionsMenu
- AdminSettingsTermMenu / AdminBonusTermMenu / GovernanceSettingsMenu
- AdminSettingsApplyMenu / AdminSettingsCustomRequestsMenu / AdminSettingsChatPromptsMenu / AdminSettingsSellBonusesMenu
- AdminEconomyMenu / AdminMessagingMenu
- AdminDisplayMenu (NPC + hologram controls)
- AdminResetElectionConfirmMenu

### Admin menu IDs (for `/mayor admin open <menuId>`)
```
ADMIN, SYSTEM, GOVERNANCE, ELECTION, ELECTION_SETTINGS, ELECTION_TERM, FORCE_ELECT,
CANDIDATES, APPLYBAN, PERKS, PERKS_CATALOG, PERK_REQUESTS, PERKS_REFRESH,
ECONOMY, MESSAGING, MONITORING, MAINTENANCE,
SETTINGS, SETTINGS_GENERAL, SETTINGS_TERM, SETTINGS_TERM_EXTRAS, SETTINGS_APPLY,
SETTINGS_CUSTOM, SETTINGS_CHAT, SETTINGS_ELECTION, BONUS_TERM, AUDIT, HEALTH, DEBUG
```

---

## Configuration Highlights
- `enabled`: Master switch for the plugin.
- `public_enabled`: Toggle the system for regular players while keeping admin access.
- `enable_options`: Select which subsystems are affected when `enabled=false`.
- `pause.enabled`: Pause scheduling without disabling the plugin.
- `pause.options`: Select which subsystems are affected when paused.
- `term.length`, `term.vote_window`, `term.first_term_start`, `term.perks_per_term`: Core term settings.
- `term.bonus_term.*`: Bonus term settings.
- `apply.playtime_minutes`, `apply.cost`: Candidate requirements.
- `election.allow_vote_change`, `election.tie_policy`, `election.mayor_stepdown`, `election.stepdown.allow_reapply`.
- `sell_bonus.*`: Sell-bonus behavior, command detection, and stacking.
- `sell_bonus.fallback_enabled`: Enable/disable command-based sell detection fallback.
- `custom_requests.*`: Custom perk request limits and conditions.
- `showcase.*`, `npc.*`, `hologram.*`: Display settings.
- `data.store.*`: SQLite or MySQL storage.

Subsystem options for `enable_options` and `pause.options`:
`SCHEDULE`, `ACTIONS`, `PERKS`, `MAYOR_NPC`, `BROADCASTS`.

---

## Configuration Examples
- [Example config.yml](docs/examples/config.yml)
- [Example messages.yml](docs/examples/messages.yml)

---

## Dependencies & Integrations

### Required
- Paper 1.21.8 (API 1.21)
- Java 21

### Optional (auto-detected)
- Vault (economy bridge + chat prefix support)
- Vault-compatible economy plugin (economy provider, e.g., EssentialsX Economy)
- Citizens or FancyNpcs (Mayor NPC)
- SystemSellAddon (sell bonus integration)
- SystemSkyblockStyleAddon (Skyblock-style perk mechanics)
- PlaceholderAPI (placeholders in messages + broadcasts)
- DecentHolograms (leaderboard hologram)

---

## Data Storage
- `data.store.type = sqlite` or `mysql`
- SQLite file: `elections.db`
- MySQL settings live under `data.store.mysql` in `config.yml`

---

## PlaceholderAPI
If PlaceholderAPI is installed, MayorSystem registers these placeholders:
- `%mayorsystem_leaderboard_term%` -> current election term number (1-based)
- `%mayorsystem_leaderboard_<pos>_name%` -> candidate name at position `<pos>`
- `%mayorsystem_leaderboard_<pos>_votes%` -> vote count at position `<pos>`
- `%mayorsystem_leaderboard_<pos>_uuid%` -> candidate UUID at position `<pos>`

`<pos>` starts at 1. If there is no candidate at that position, the placeholder returns an empty string.

---

## Support & Troubleshooting
- Use `/mayor admin maintenance` (or `/mayor admin debug`) for reloads and offline cache refresh.
- Use `/mayor admin monitoring` (or `/mayor admin health`) for a full environment check.
- Use `/mayor admin audit` to see who changed what.
- Check `config.yml` and `messages.yml` for customization.
- If NPCs or holograms do not show, confirm the integration plugin is installed and enabled, then run `/mayor admin health`.

---

## Build (for developers)
```
./gradlew build
```
The shaded jar is produced by the Shadow plugin.

---

## License & Copyright
Copyright (c) 2026 Lou Morel (Canada). All rights reserved.

This repository is proprietary. Permission is granted to use the compiled
MayorSystem plugin solely by installing and running it on Minecraft servers.
You may redistribute the unmodified plugin binary (the official jar), provided
the LICENSE and NOTICE remain included and the jar is unmodified.

Use of the integration addons SystemSellAddon and SystemSkyblockStyleAddon
is permitted only if you have paid for them or received explicit
authorization from the copyright holder.

No other rights are granted. You may not use the source code, and you may not
distribute modified versions of the plugin.

See `LICENSE` for the full terms.
