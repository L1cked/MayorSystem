# Integrations

```mermaid
flowchart TB
    Runtime["MayorRuntime"]
    Economy["EconomyProvider\nVaultEconomyProvider / no economy"]
    Permissions["PermissionGroupProvider\nLuckPerms / no group provider"]
    Placeholders["PlaceholderBridge + MayorPlaceholderExpansion\nPlaceholderAPI optional"]
    Tags["DisplayTagProvider\nDeluxeTags optional"]
    NPC["NpcBackend + MayorNpcProvider\nCitizens, FancyNpcs, disabled"]
    Holograms["HologramBackend + LeaderboardHologramProvider\nDecentHolograms, FancyHolograms, disabled"]
    Addons["MayorPerkSource\naddon-provided perk sections"]
    Services["Application and service layer"]

    Runtime --> Economy
    Runtime --> Permissions
    Runtime --> Placeholders
    Runtime --> Tags
    Runtime --> NPC
    Runtime --> Holograms
    Runtime --> Addons
    Services --> Economy
    Services --> Permissions
    Services --> Tags
    Services --> NPC
    Services --> Holograms
    Services --> Addons
```

Optional integrations remain optional. Missing plugins should route to disabled/no-op behavior instead of making MayorSystem fail unless the configured data store itself cannot load. Addons inject perk sections through the public MayorSystem API.
