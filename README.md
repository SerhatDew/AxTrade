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

Storage changes require a full server restart, not `/trade reload`. Redis does
not enable cross-server trade requests or item transfers: both players must be
on the same server.

## Building

Run `mvn package` with JDK 21. The upstream build also requires the proprietary
economy API JARs named by the `systemPath` entries in `pom.xml`, placed in
`libs/`, plus access to the UltraEconomy API repository. Those JARs are not
included in this repository. Without them, a normal Maven build cannot resolve
all existing currency integrations.
