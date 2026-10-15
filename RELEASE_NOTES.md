# Release notes

## Unreleased

### Added

- SQL-backed player preferences and trade history with MySQL, MariaDB and PostgreSQL support.
- Redis preference synchronization between servers sharing a database.
- Redis-backed network player discovery, cross-server requests and synchronized trade offers.
- Player-name completion for local and remote trade requests.

### Fixed

- Request expiry, incoming-request selection and ambiguous local/remote accept and deny commands.
- Cross-server request receiver isolation and session peer validation.
- Confirmation resets when a remote offer changes.
- Stale preference loads after disconnect/rejoin, missing-row SQL reconciliation and uppercase legacy YAML UUID keys.
- Currency rollback completion, duplicate refunds and rejected-refund reporting.
- Duplicate trade registration for players already trading.
- GUI configuration errors are no longer silently replaced with empty trade slots.

### Release status

Not published. Cross-server trading remains unsuitable for production until durable settlement and recovery are implemented and verified. Redis Pub/Sub does not retain missed commit messages; current escrow is held in memory. Server crashes, partial economy failures and delivery interruptions can leave a trade unresolved or only partially settled.

A release also requires a full build with the proprietary economy API dependencies and end-to-end verification on two Minecraft servers. Isolated compilation and unit tests are not a substitute for these checks.
