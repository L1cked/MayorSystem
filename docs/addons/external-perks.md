# External Perk Sources

Addon perk sources let another plugin add MayorSystem perk sections without editing MayorSystem internals.

## ID Rules
Source ids, section ids, and perk ids must match:

```text
^[a-z0-9][a-z0-9_.-]{0,63}$
```

Use stable lowercase ids. Do not change ids after release unless you also provide a migration path for server configs.

## Example Source
```kotlin
import mayorSystem.api.MayorPerkDefinition
import mayorSystem.api.MayorPerkSection
import mayorSystem.api.MayorPerkSource

class ExamplePerkSource : MayorPerkSource {
    override val id = "example_addon"
    override val displayName = "Example Addon"
    override val available = true

    override fun sections(): List<MayorPerkSection> = listOf(
        MayorPerkSection(
            id = "example",
            enabled = true,
            pickLimit = 1,
            displayName = "<green>Example</green>",
            icon = "EMERALD",
            perks = listOf(
                MayorPerkDefinition(
                    id = "double_drops",
                    enabled = true,
                    displayName = "<green>Double Drops</green>",
                    icon = "DIAMOND_PICKAXE",
                    lore = listOf("<gray>Example addon perk.</gray>"),
                    adminLore = listOf("<dark_gray>Provided by Example Addon.</dark_gray>"),
                    onStart = listOf("say Double Drops enabled"),
                    onEnd = listOf("say Double Drops disabled")
                )
            )
        )
    )
}
```

## Seeding Behavior
When a source is registered, MayorSystem writes only missing keys under:

```text
perks.sections.<sectionId>
```

Existing server-owner values are preserved. If the config changes, MayorSystem reloads perk definitions.

## Cleanup
Store the returned `MayorAddonRegistration` and call `close()` on addon disable. MayorSystem also removes registrations owned by a plugin when that plugin disables.
