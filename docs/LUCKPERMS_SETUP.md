# LuckPerms Setup Guide

This guide only covers the LuckPerms side of MayorSystem's mayor-group integration.

MayorSystem already does the rest by default:

- `title.username_group_enabled: true`
- `title.username_group: "mayor_current"`
- assigns/removes that group for the elected player
- auto-creates the group if it does not exist yet

If you changed the group name in MayorSystem, replace `mayor_current` below with your custom name.

## Required LuckPerms config

In `LuckPerms/config.yml`, put the mayor group first in the prefix stack and exclude it from the fallback slot:

```yml
meta-formatting:
  prefix:
    format:
      - "highest_from_group_mayor_current"
      - "highest_not_from_group_mayor_current"
    duplicates: first-only
    start-spacer: ""
    middle-spacer: " "
    end-spacer: ""
```

This uses LuckPerms' documented `highest_from_group_<group>` and `highest_not_from_group_<group>` stack elements so the mayor prefix appears first without replacing the player's normal highest prefix.

## Optional group prefix/meta

If you want the mayor group to add a visible LuckPerms prefix, set it on the group in LuckPerms:

```text
/lp group mayor_current meta addprefix 100 "&6[Mayor]&r"
```

You do not need to create the group manually unless you prefer managing it yourself before MayorSystem does.
You do not need to change MayorSystem `config.yml` unless you disabled this feature or renamed the group.

Result:

```text
[Mayor] [ExistingPrefix] PlayerName
```
