# AxTrade — Multi-server

Fork of [Artillex-Studios/AxTrade](https://github.com/Artillex-Studios/AxTrade), based on 1.28.2.
Adds SQL player preferences, Redis preference synchronization, and Redis-backed cross-server trade requests and offers.

**Downloads:** [Releases](https://github.com/SerhatDew/AxTrade/releases)

**Bug Reports and Feature Requests:** https://github.com/Artillex-Studios/Issues

**Support:** https://dc.artillex-studios.com/

![axtrade-banner](https://github.com/Artillex-Studios/AxTrade/assets/52270269/5383e676-8355-4ee6-a4b6-624b5c59aaa9)

## Commands

- `/trade <player>` sends a request; sending one back accepts an existing incoming request.
- `/trade accept [player]` and `/trade deny [player]` act only on incoming,
  unexpired requests. With one incoming request the name is optional; with
  multiple requests the player must choose a name.
- `/trade toggle` changes whether the player accepts requests.

Player-name completion excludes the sender and invisible players.
`accept`/`deny` completion lists only active incoming senders. Configured command
aliases and existing permissions remain supported.

Acceptance rechecks availability, trading preferences, safety, game mode, world,
distance and existing trades. Accepted participants' other requests are removed.
Denial permits a new request immediately; expiry and disconnect remove pending
requests.

## Storage

Java 21 is required to build. The default `storage.backend: yaml` keeps player
preferences in `plugins/AxTrade/toggled.yml`. Trade logs remain in
`plugins/AxTrade/logs/`.

For shared preferences and SQL history, configure `config.yml`:

```yaml
storage:
  backend: sql
  sql:
    dialect: mysql
    host: localhost
    port: 3306
    database: axtrade
    username: axtrade
    password: "change-me"
    pool-size: 4
    connection-timeout-ms: 3000
    validation-timeout-ms: 1000
    url: ""
  redis:
    enabled: false
    host: localhost
    port: 6379
    username: ""
    password: ""
    database: 0
    tls: false
    namespace: axtrade
    server-id: survival-1
    connect-timeout-ms: 3000
    socket-timeout-ms: 3000
```

Supported SQL dialects: `mysql`, `mariadb`, `postgresql`. Use port `5432` for
PostgreSQL. Create the database and grant the plugin user permission to create
tables and read/write records. The plugin creates `axtrade_preferences` and
`axtrade_history`; MySQL/MariaDB history uses `utf8mb4`. A non-empty `sql.url`
overrides the host/port/database fields, including driver-specific TLS settings.
Drivers are bundled; no separate database plugin is needed.

Existing disabled preferences from `toggled.yml` are imported without overwriting
existing SQL records. Back up both before changing backend. Switching back to
YAML does not export SQL preferences.

Redis requires SQL. Servers sharing preferences must use the same SQL database
and Redis namespace; give each server a distinct `server-id`. Use separate
databases and namespaces for unrelated networks. Redis publishes invalidations,
not authoritative preference values; recipients reload SQL. Loaded preferences
are reconciled every 30 seconds to repair missed notifications. A Redis outage
therefore delays synchronization, but does not replace SQL with stale Redis data.
Restrict Redis to trusted hosts and use ACL credentials/TLS where appropriate.

Preference writes are acknowledged only after persistence. Until a player's
preferences load, trading is refused. A configured backend that cannot connect
prevents plugin startup rather than silently falling back to local storage.
SQL history failures are logged; local text logs remain available. History is an
audit log, not an item-delivery recovery system.

Storage changes require a full server restart, not `/trade reload`.

## Redis multiserver trading

Enabling `storage.redis.enabled` also starts the cross-server trade manager.
Use `storage.backend: sql`, the same SQL database and Redis namespace on every
participating server, and a unique `storage.redis.server-id` for each server.
All participating servers must run the same plugin and Minecraft versions and
have matching currency hooks and trade rules.

The network player registry adds remote players to `/trade <player>` completion.
Requests can be accepted or denied by name across servers. A bare `accept` or
`deny` requires a single incoming request across both local and remote players.
Remote item and currency offers update the local GUI; changing an offer clears
confirmation. Session messages are checked against the expected player and
server, and settlement is bound to the prepared offer.

### Release blockers

- Trade state and escrow are not durable. A lost prepare/finalize message or a
  server crash can leave a trade unresolved or settle only one participant.
- Currency operations and inventory delivery do not share an atomic transaction.
  A partial economy failure cannot be recovered automatically.
- Cross-server crash, disconnect and reconnect scenarios still need end-to-end
  verification on two servers with the real economy integrations.

SQL history is an audit trail, not a delivery queue or recovery journal. Do not
treat it as protection against lost or duplicated items.

## Building

Run `mvn package` with JDK 21. The upstream build also requires the proprietary
economy API JARs named by the `systemPath` entries in `pom.xml`, placed in
`libs/`, plus access to the UltraEconomy API repository. Those JARs are not
included in this repository. Without them, a normal Maven build cannot resolve
all existing currency integrations.
