# MayorSystem

MayorSystem is a Paper 1.21.8 plugin that runs server elections, crowns a mayor, and lets that mayor (and candidates)
pick server-wide perks. Think "civic gameplay" with a bit of extra sparkle.

---

## Features (aka "why this is fun")
- Scheduled terms + configurable vote window (ISO-8601 durations. Ex: P1Y2M3DT4H5M6S)
- Candidate application flow with playtime + cost requirements
- Perk catalog with sections, pick limits, and custom perk requests
- Optional sell-bonus integration for ShopGUI+, EconomyShopGUI, and SystemSellAddon
- Mayor NPC statue (Citizens or FancyNpcs) that opens a Mayor profile menu
- Admin audit log + health checks + force-election tools
- MiniMessage formatting with optional PlaceholderAPI

---

## SystemSellAddon Integration (Recommended)
MayorSystem is pre-configured to hook into SystemSellAddon's `/sell` payouts with zero extra setup. If both plugins are installed:
- Bonus perks are applied automatically on top of SystemSellAddon's base payout
- Category and total payouts are passed through cleanly
- No extra commands or config are needed - just install and restart

If you run SystemSellAddon, this is the smoothest path for sell bonuses.

---

## Quick Start
1. Drop the jar into `plugins/` and start the server.
2. Edit `config.yml`:
   - `term.first_term_start` (example: `2026-02-01T00:00:00-05:00`)
   - `term.length` (example: `P14D`)
   - `term.vote_window` (example: `P3D`)
3. (Optional) Install integrations (Vault, Citizens/FancyNpcs, ShopGUI+/EconomyShopGUI, PlaceholderAPI).
4. Hop in-game and run `/mayor`.

---

## Commands (grouped by section)

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

### Admin: NPC
```
/mayor admin npc spawn
/mayor admin npc remove
/mayor admin npc update
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

### Admin (new structure)
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
| `mayor.admin.economy.edit` | op | Edit economy settings |
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

## Dependencies & Integrations

### Required
- Paper 1.21.8 (API 1.21)
- Java 21

### Optional (auto-detected)
- Vault (economy + chat prefix support)
- Citizens or FancyNpcs (Mayor NPC)
- ShopGUIPlus, EconomyShopGUI, EconomyShopGUI-Premium, or SystemSellAddon (our own sell plugin for the /sell bonuses handling)
- PlaceholderAPI (placeholders in messages + broadcasts)

---


## Data Storage
- `data.store.type = sqlite` or `mysql`
- SQLite file: `elections.db`
- MySQL settings live under `data.store.mysql` in config.yml

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
- If something looks off, restart once after installing new integrations (NPCs/sell plugins).

---

## Build (for developers)
```
./gradlew build
```
The shaded jar is produced by the Shadow plugin.
