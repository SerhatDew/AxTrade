# Release notes

## 1.28.2-multiserver.1 - 2026-10-15

### Added

- SQL-backed player preferences and trade history with MySQL, MariaDB and PostgreSQL support.
- Bundled JDBC drivers for MySQL, MariaDB and PostgreSQL.
- Redis preference synchronization between servers sharing a database.
- Redis-backed network player discovery, cross-server requests and synchronized trade offers.
- Player-name tab completion for local and remote trade requests.

### Fixed

- Request expiry, incoming-request selection and ambiguous local/remote accept and deny commands.
- Cross-server request receiver isolation and session peer validation.
- Confirmation resets when a remote offer changes.
- Stale preference loads after disconnect/rejoin, missing-row SQL reconciliation and uppercase legacy YAML UUID keys.
- Currency rollback completion, duplicate refunds and rejected-refund reporting.
- Duplicate trade registration for players already trading.
- GUI configuration errors are no longer silently replaced with empty trade slots.

### Operational notes

- Participating servers must use the same plugin version, SQL database and Redis channel (`axtrade:cross_server`).
- Escrow and prepared state are held in memory during trade negotiation. In case of unexpected server crash during settlement, inspect SQL audit logs (`axtrade_history`).

### Verification

- Java 21 build and 27 unit tests passed.
- Live multi-server test network (Velocity proxy, two Leaf 26.2 instances leaf-1 and leaf-2, Redis 6380, and MySQL 8.0 `minecraft_network`) verified with active cross-server trading registration and SQL connection pools.
