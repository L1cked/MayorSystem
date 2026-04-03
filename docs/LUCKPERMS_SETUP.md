# LuckPerms Setup Guide

This guide only covers the MayorSystem part of the LuckPerms setup.

## Step 1

In `LuckPerms/config.yml`, make sure the mayor group is checked first:

```yml
meta-formatting:
  prefix:
    format:
      - "highest_from_group_mayor_current"
      - "highest"
    duplicates: first-only
    start-spacer: ""
    middle-spacer: " "
    end-spacer: ""
```

## Step 2

Create the mayor group:

```text
/lp creategroup mayor_current
```

## Step 3

Give the mayor group a prefix:

```text
/lp group mayor_current setweight 1000
/lp group mayor_current meta addprefix 1000 "&6[Mayor]&r"
```

## Step 4

In MayorSystem `config.yml`, keep this enabled:

```yml
title:
  username_group_enabled: true
  username_group: "mayor_current"
```

Result:

```text
[Mayor] [ExistingPrefix] PlayerName
```
