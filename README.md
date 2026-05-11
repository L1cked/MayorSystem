# MayorSystem

MayorSystem is a Paper 1.21+ election plugin for servers that want scheduled terms, candidates, voting, elected mayors, server-wide perks, displays, admin tools, and addon support.

![MayorSystem Banner](docs/images/banner.png)

![Status Badge](https://img.shields.io/badge/status-active-brightgreen)
![Paper Badge](https://img.shields.io/badge/paper-1.21%2B-blue)
![Java Badge](https://img.shields.io/badge/java-21-orange)

## Features
- Scheduled election terms with configurable vote windows.
- Candidate applications with playtime and economy requirements.
- Voting, vote changes, fake vote adjustments, and force-election tools.
- Mayor perk catalog with sections, pick limits, custom perk requests, and bonus terms.
- Optional Mayor NPC and leaderboard hologram displays.
- Optional LuckPerms rank reward and DeluxeTags tag reward for the current mayor.
- Optional integrations with Vault, PlaceholderAPI, Citizens, FancyNpcs, DecentHolograms, FancyHolograms, SystemSellAddon, and SystemSkyblockStyleAddon.
- Admin menus, health checks, audit log, reload tools, and config default sync.
- Public addon API for snapshots, events, and addon-provided perk sections.

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

## Requirements
- Paper 1.21+
- Java 21

Optional integrations are auto-detected when installed. See [Integrations](docs/integrations.md).

## Quick Start
1. Drop `MayorSystem-<version>.jar` into `plugins/`.
2. Start the server once so `config.yml`, `messages.yml`, and `gui.yml` are created.
3. Set the first term start before opening the first real election:
   ```text
   /mayor admin settings first_term_start 2026-03-01T00:00:00-05:00
   ```
4. Configure term length, vote window, apply requirements, and perks in the admin menus or config.
5. Run `/mayor` in game.

Missing config, message, and GUI keys are restored on startup/reload without overwriting custom values.

## Documentation
- [Full docs hub](docs/README.md)
- [Commands](docs/commands/README.md)
- [Permissions](docs/permissions.md)
- [Configuration](docs/configuration.md)
- [Integrations](docs/integrations.md)
- [Storage](docs/storage.md)
- [PlaceholderAPI placeholders](docs/placeholders.md)
- [Menus](docs/menus.md)
- [Addon API](docs/addons/README.md)
- [How to make an addon](docs/addons/how-to-make-addon.md)
- [Architecture logic maps](docs/logic-map/README.md)
- [LuckPerms setup](docs/LUCKPERMS_SETUP.md)

## Build
```bash
./gradlew clean build
```

The normal plugin jar is written to `build/libs/`. The addon compile-time API jar is built with:

```bash
./gradlew apiJar
```

Addon developers can compile against the published API package:

```kotlin
compileOnly("ca.l1cked:mayorsystem-api:1.1.5")
```

See [Building](docs/building.md) and [How to make an addon](docs/addons/how-to-make-addon.md) for repository setup and publishing details.

## Support
- Use `/mayor admin health` for environment and integration checks.
- Use `/mayor admin audit` to review staff changes.
- Use `/mayor admin reload` after config edits.
- Detailed troubleshooting is in [docs/README.md](docs/README.md).

## License
MayorSystem is licensed under the MIT License. See [LICENSE](LICENSE).
