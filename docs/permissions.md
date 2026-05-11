# Permissions

Admin panel access is feature-permission driven. Staff can open `/mayor admin` with `mayor.admin.access` or any admin action node, but menu sections and direct actions are still checked against their specific permission.

## Player
| Node | Default | Description |
| --- | --- | --- |
| `mayor.use` | true | Access the `/mayor` menu |
| `mayor.apply` | true | Apply as a candidate |
| `mayor.vote` | true | Vote in elections |
| `mayor.candidate` | true | Candidate actions, perk selection, and custom requests |

## Admin
| Node | Default | Description |
| --- | --- | --- |
| `mayor.admin.access` | op | Root admin panel access |
| `mayor.admin.system.toggle` | op | Toggle public access |
| `mayor.admin.candidates.remove` | op | Remove candidates |
| `mayor.admin.candidates.restore` | op | Restore candidates |
| `mayor.admin.candidates.process` | op | Mark candidates in process |
| `mayor.admin.candidates.applyban` | op | Manage apply bans |
| `mayor.admin.perks.refresh` | op | Refresh active perks |
| `mayor.admin.perks.requests` | op | Approve or deny custom perk requests |
| `mayor.admin.perks.catalog` | op | Enable or disable perk sections/perks |
| `mayor.admin.governance.edit` | op | Edit governance policies |
| `mayor.admin.messaging.edit` | op | Edit messaging settings |
| `mayor.admin.election.start` | op | Force-start elections |
| `mayor.admin.election.end` | op | Force-end elections |
| `mayor.admin.election.clear` | op | Clear election overrides |
| `mayor.admin.election.elect` | op | Force-elect a player |
| `mayor.admin.election.fakevotes` | op | View and adjust fake votes |
| `mayor.admin.settings.edit` | op | Edit settings |
| `mayor.admin.settings.reload` | op | Reload config and store |
| `mayor.admin.reward.view` | op | View display reward settings |
| `mayor.admin.reward.edit` | op | Edit display reward settings |
| `mayor.admin.maintenance.reload` | op | Reload config and store |
| `mayor.admin.maintenance.debug` | op | Maintenance debug tools |
| `mayor.admin.audit.view` | op | View audit log |
| `mayor.admin.health.view` | op | Run health checks |
| `mayor.admin.npc.mayor` | op | Spawn, remove, or update Mayor NPC |
| `mayor.admin.hologram.leaderboard` | op | Spawn, remove, or update leaderboard hologram |

## Wildcards
| Node | Default | Description |
| --- | --- | --- |
| `mayor.*` | false | All public and admin permissions |
| `mayor.admin` | false | Full admin tree |
| `mayor.admin.*` | false | All admin action nodes |
