# Storage

MayorSystem supports SQLite and MySQL.

## SQLite
- Config: `data.store.type: sqlite`
- File: `plugins/MayorSystem/elections.db`

## MySQL
- Config: `data.store.type: mysql`
- Connection settings live under `data.store.mysql`.
- Placeholder credentials such as `CHANGE_ME` are rejected.

## Startup Safety
Store startup is fail-closed. If the configured backend cannot load, MayorSystem disables itself instead of silently falling back to another backend and splitting election data.

Use admin commands and menus for normal election changes. Back up data before switching storage backends.
