# AxTrade

My fork of [Artillex-Studios/AxTrade](https://github.com/Artillex-Studios/AxTrade). I've added SQL storage, shared player settings through Redis, and experimental cross-server trading.

## What's changed

- Player settings and trade history can use MySQL, MariaDB, or PostgreSQL instead of local files.
- Servers can share trade preferences, with Redis notifying them when a setting changes.
- Cross-server player lookup, trade requests, and item/currency offers are implemented, but settlement still needs work.
- Fixes for incoming requests, expired requests, reconnects, currency refunds, and trade confirmation.

**Cross-server trading is not ready for production.** Trade state is held in memory, and Redis does not retain missed messages. A crash or a lost settlement message can leave one side of a trade unfinished. Keep Redis disabled on production servers for now; enabling it also enables cross-server trading. No multiserver release has been published. See [release notes](RELEASE_NOTES.md) for the remaining work.

## Commands

- `/trade <player>` — send a request, or accept one that player has already sent you.
- `/trade accept [player]` — accept an incoming request.
- `/trade deny [player]` — deny an incoming request.
- `/trade toggle` — turn incoming requests on or off.

You can leave out the player name when there is only one incoming request. If there are several, you need to pick one. Expired requests cannot be accepted.

Tab completion hides yourself and invisible players. For `accept` and `deny`, it only lists players with an active incoming request. Existing permissions and configured aliases still apply.

Accepting a request checks both players' availability, preferences, safety, game mode, world, distance, and whether either is already trading. Starting a trade clears their other pending requests. Denying a request allows a new one immediately; expired requests and requests from disconnected players are removed.

## Storage

The default is `storage.backend: yaml`. Player preferences stay in `plugins/AxTrade/toggled.yml`, and text trade logs stay in `plugins/AxTrade/logs/`.

For SQL storage, add this to `config.yml`:

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

Supported dialects are `mysql`, `mariadb`, and `postgresql`. For PostgreSQL, use port `5432`. Create the database first and give the account permission to create tables and read/write records. The plugin creates `axtrade_preferences` and `axtrade_history`; MySQL/MariaDB history uses `utf8mb4`. Database drivers are included.

Setting `sql.url` overrides the host, port, and database fields. Use it for a custom JDBC URL, including driver-specific TLS options.

Back up `toggled.yml` and your database before switching. Existing disabled preferences are imported from YAML without replacing records already in SQL. Switching back to YAML does not copy SQL preferences back to the file.

Restart the server after storage changes. `/trade reload` is not enough.

### Sharing preferences with Redis

Redis requires SQL. Use the same SQL database and Redis namespace on each server, but give every server its own `server-id`. Keep unrelated networks on separate databases and namespaces.

SQL holds the settings; Redis only tells other servers to reload them. Loaded settings are also checked every 30 seconds, so missed notifications can be picked up later. A Redis outage can delay a change reaching other servers, but it does not replace SQL values with cached ones. Restrict Redis access to trusted hosts and use ACL credentials and TLS where needed.

Players cannot trade until their settings have loaded. A setting change is only acknowledged after it has been saved. If the configured storage cannot connect, the plugin stops startup instead of falling back to local files. SQL history errors are logged, and local text logs remain available.

## Cross-server trading

This is experimental. Setting `storage.redis.enabled: true` starts both preference synchronization and the cross-server trade manager.

All participating servers need:

- `storage.backend: sql`, the same SQL database, and the same Redis namespace.
- A different `storage.redis.server-id` on each server.
- Matching plugin and Minecraft versions, currency integrations, and trade rules.

Remote players appear in `/trade <player>` completion. Requests can be accepted or denied by name across servers. Leaving the name out only works when there is one incoming request in total, counting both local and remote players.

Item and currency offers appear in each player's trade GUI. Changing an offer clears confirmation. Session messages are checked against the expected player and server, and settlement uses the prepared offer.

### What's still missing

- Trade state and escrow are not saved durably. A lost prepare/finalize message or a server crash can leave a trade unresolved, or completed for only one player.
- Currency transfers and inventory delivery are separate operations. A partial economy failure cannot be recovered automatically.
- Crash, disconnect, and reconnect scenarios still need testing on two servers with the real economy integrations.

Trade history is just an audit log. It is not a delivery queue or a recovery journal, and it does not protect against lost or duplicated items.

## Building

Use JDK 21 and Maven:

```sh
mvn package
```

The build needs the proprietary economy API JARs listed under `systemPath` in `pom.xml`. Put those in `libs/`; they are not included here. You also need access to the UltraEconomy API repository. Without those dependencies, Maven cannot build all of the existing currency integrations.

## Upstream

Original plugin by Artillex Studios:

- [Source](https://github.com/Artillex-Studios/AxTrade)
- [Bug reports and feature requests](https://github.com/Artillex-Studios/Issues)
- [Discord](https://dc.artillex-studios.com/)

![AxTrade banner](https://github.com/Artillex-Studios/AxTrade/assets/52270269/5383e676-8355-4ee6-a4b6-624b5c59aaa9)
