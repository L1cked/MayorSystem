# LuckPerms Setup Guide

This guide only covers the LuckPerms side of MayorSystem's mayor-group integration.

MayorSystem already does the rest by default:

- `title.username_group_enabled: true`
- `title.username_group: "mayor_current"`
- assigns/removes that group for the elected player
- auto-creates the group if it does not exist yet

If you changed the group name in MayorSystem, replace `mayor_current` below with your custom name.

## Required LuckPerms config

In `LuckPerms/config.yml`, use the normal highest-priority prefix selector:

```yml
meta-formatting:
  prefix:
    format:
      - "highest"
    duplicates: first-only
    start-spacer: ""
    middle-spacer: " "
    end-spacer: ""
```

Then give `mayor_current` a prefix priority above your normal rank prefixes. This makes the mayor prefix override the visible LuckPerms prefix while the player still keeps their normal rank groups and permissions.

## Optional group prefix/meta

If you want the mayor group to add a visible LuckPerms prefix, set it on the group in LuckPerms:

```text
/lp group mayor_current meta setprefix 1000 "&6[Mayor]&r"
```

Use a prefix priority above your normal rank prefixes. For example, if `owner` is priority `900`, make `mayor_current` priority `1000`.

You do not need to create the group manually unless you prefer managing it yourself before MayorSystem does.
You do not need to change MayorSystem `config.yml` unless you disabled this feature or renamed the group.
MayorSystem now prefers LuckPerms-backed display names when they exist, so menus, broadcasts, and mayor profile screens will use the elected player's LuckPerms prefix instead of prepending the configured `title.player_prefix` a second time.

Result:

```text
[Mayor] PlayerName
```

Do not use a two-entry prefix stack such as `highest_from_group_mayor_current` plus `highest_not_from_group_mayor_current` unless you intentionally want both prefixes to show, for example `[Mayor] [Owner] PlayerName`.

## Keep mayor_current out of tracks

`mayor_current` should be a temporary overlay group, not part of a rank or staff promotion track.

Do not add it to tracks such as:

```text
ranks
staff
```

Track order controls promotion/demotion paths, not prefix stacking. Putting `mayor_current` in a track can make LuckPerms treat it like a normal rank and can cause confusing promotion, demotion, and TAB sorting behavior.
