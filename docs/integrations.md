# Integrations

All integrations are optional unless your server workflow requires them. MayorSystem auto-detects supported plugins and addons when they are installed, and most are drop-in: install the integration plugin, restart or reload, then use `/mayor admin health` and the relevant admin menus to finish setup.

If an optional plugin is missing, MayorSystem uses disabled/no-op behavior for that integration instead of making the whole plugin unusable.

## Economy
- Vault plus a Vault-compatible economy plugin powers candidate apply costs.

## LuckPerms
- Used for mayor username group rewards and display reward rank mode.
- See [LuckPerms setup](LUCKPERMS_SETUP.md).

## DeluxeTags
- Used for display reward tag mode.
- MayorSystem grants the configured DeluxeTags permission through LuckPerms.

## PlaceholderAPI
- Used in messages, broadcasts, and the MayorSystem placeholder expansion.
- See [placeholders](placeholders.md).

## NPCs
- Citizens or FancyNpcs can provide the Mayor NPC backend.
- Configure with `npc.mayor.provider` or leave on `auto`.

## Holograms
- DecentHolograms or FancyHolograms can provide the leaderboard hologram backend.
- Configure with `hologram.leaderboard.provider` or leave on `auto`.

## External Perk Sources
- SystemSellAddon can seed economy perks.
- SystemSkyblockStyleAddon can seed skyblock-style perks.
- New addons should use the [MayorSystem addon API](addons/README.md).
