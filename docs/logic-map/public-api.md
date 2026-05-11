# Public API

```mermaid
flowchart TD
    Addon["External addon plugin"]
    BukkitServices["Bukkit ServicesManager"]
    ApiJar["MayorSystem API jar\ncompileOnly dependency"]
    API["MayorSystemApi\nsingle addon entrypoint"]
    Impl["platform.paper.api\nruntime implementation"]
    Snapshots["api.snapshot\nimmutable DTOs"]
    Events["api.events\nMayorPerksAppliedEvent\nMayorPerksClearedEvent"]
    Registry["AddonPerkSourceRegistry"]
    Config["perks.sections.*\nmissing keys only"]
    Perks["PerkService reload"]

    Addon --> ApiJar
    Addon --> BukkitServices
    BukkitServices --> API
    API --> Impl
    Impl --> Snapshots
    Impl --> Registry
    Registry --> Config
    Registry --> Perks
    Perks --> Events
    Events --> Addon
```

Addons compile against the API jar and load the live runtime API through Bukkit services. Public access is limited to snapshots, events, active/all perk ids, and addon perk-source registration.
