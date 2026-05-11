# Display Rewards

```mermaid
flowchart TB
    Settings["display_reward config"]
    Admin["Admin reward commands and menus"]
    UseCase["AdminDisplayRewardCommandActions"]
    Resolver["DisplayRewardTargetResolver\nuser, group, track, default mode"]
    Planner["DisplayRewardPlanner\nrank/tag/both plan"]
    Names["PlayerIdentityService\nknown player validation and display names"]
    Tags["DisplayRewardTagResolver\nDeluxeTags-backed display state"]
    LP["PermissionGroupProvider\nLuckPerms groups/tracks"]
    TagProvider["DisplayTagProvider\nDeluxeTags permission and snapshot"]
    Prefix["MayorUsernamePrefixService\ncurrent mayor group sync"]
    Health["HealthService\nreward diagnostics"]

    Admin --> UseCase
    UseCase --> Settings
    UseCase --> Resolver
    Resolver --> Names
    Resolver --> LP
    Resolver --> Planner
    Planner --> Prefix
    Planner --> Tags
    Tags --> TagProvider
    Prefix --> LP
    Health --> LP
    Health --> TagProvider
```

Rank rewards grant the configured LuckPerms group. Tag rewards grant the configured DeluxeTags permission through LuckPerms. User target lookup accepts UUIDs, online names, cached known names, and previously joined Bukkit profiles.
