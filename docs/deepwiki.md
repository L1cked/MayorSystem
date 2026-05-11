# DeepWiki

MayorSystem has a DeepWiki steering file at `.devin/wiki.json`.

Use it to compare the generated, code-aware wiki with the repository docs:

- [DeepWiki for MayorSystem](https://deepwiki.com/L1cked/MayorSystem)
- [Repository docs](README.md)

The repository docs are the stable server-owner and addon-developer reference. DeepWiki is useful for architecture exploration because it can summarize source files, implementation boundaries, and code relationships.

## What DeepWiki Should Cover
- Runtime lifecycle and plugin startup/shutdown phases.
- Election domain rules and player workflows.
- Admin use cases, menus, audit logging, and reload behavior.
- Perk sections, active perk rebuilds, and addon perk sources.
- Storage safety, SQLite/MySQL behavior, and repository boundaries.
- Optional integrations and Noop fallbacks.
- Public addon API, snapshots, events, and API jar usage.
- Commands, permissions, configuration, and documentation structure.

## When To Use Each
- Use the repository docs when configuring a server, checking commands, or building addons against the supported API.
- Use DeepWiki when exploring how the code is organized or tracing how a feature works internally.
