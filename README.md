# Auction-House-Sync

Shared auction-house plugin for a Canvas 26.2 network. Version `2.1.1` stores auctions durably in MySQL and synchronizes live changes between servers through Redis.

## Runtime Target

| Component | Supported target |
| --- | --- |
| Java | 25 |
| Canvas | `26.2-937-6a600b8` |
| Compile API | `io.canvasmc.canvas:canvas-api:26.2.build.937-stable` |
| Persistence | MySQL 8.x |
| Synchronization | Redis 8.x |

`plugin.yml` declares both `folia-supported: true` and `canvas-supported: true`. The runtime design uses Folia's ownership model: player GUI and messages run on each player's Entity Scheduler, and display/world changes run on the owning Region Scheduler.

Paper and Folia remain source-compatible targets through MorePaperLib, but Canvas 26.2 build 937 is the release target and the environment used for compatibility work.

## Features

- Buy-it-now and bidding auctions, expiry collection, cancellation, partial selling, and configurable limits.
- One shared market across multiple Canvas nodes.
- MySQL as the durable source of truth for auction notes, bids, and metadata.
- Redis cache and authenticated pub/sub for immediate cross-server changes and chat notifications.
- Redis event de-duplication, schema/signature validation, snapshot rebuild, and reconnect handling.
- Canvas/Folia-safe GUI, notification, display, and scheduled task handling.
- Vault economy integration; PlaceholderAPI and Locale-API integrations are optional.

## Architecture

```text
Canvas node A ----\
                  +---- MySQL 8.x  (durable auction and metadata state)
Canvas node B ----/
       |                    ^
       +---- Redis 8.x -----+---- authenticated pub/sub and cache
```

Every node loads persistent state from MySQL. Mutations update the local state, persist through the database layer, update Redis cache data, and publish a signed event for the remaining nodes. Recipients ignore their own events and update their local views without directly accessing another player's region.

## Requirements

- Java 25.
- Canvas `26.2-937-6a600b8` for the production target.
- Vault and a working Vault economy provider.
- Shared MySQL 8.x and Redis 8.x for a multi-server market.
- Network access from every server node to the same MySQL and Redis endpoints.

## Installation

1. Build `AuctionHouse-2.1.1.jar` with `./gradlew shadowJar`.
2. Install the jar and Vault with a configured economy provider on every Canvas node.
3. Start each server once to generate `plugins/AuctionHouse/config.yml`.
4. Configure every node to use the same MySQL and Redis instances.
5. Give every node a distinct `server-id`.
6. Use one shared random `sync-secret` of at least 32 characters on all nodes.
7. Restart each node and confirm its log reports MySQL ready, Redis ready, and cross-server sync subscription enabled.

Do not copy one server's `server-id` to another server. Nodes with the same ID discard each other's events and cannot converge correctly.

## Cluster Configuration

The default `config.yml` contains the required structure. The following values must be changed before a production start:

```yaml
database:
  persistence: "MYSQL"
  cache: "REDIS"
  mysql:
    host: "mysql.example.internal"
    port: 3306
    database: "auction_house"
    username: "auctionhouse"
    password: "replace-me"
    use-ssl: true
  redis:
    host: "redis.example.internal"
    port: 6379
    password: "replace-me"
    use-ssl: true
    pubsub-enabled: true
    key-prefix: "auction:production:"
    channel: "auction:production:events"
    sync-secret: "replace-with-one-shared-random-secret-of-32-or-more-characters"

server-id: "canvas-auction-01"
```

Set `use-ssl` to match the actual MySQL and Redis endpoints. The shipped values are `true`; a local non-TLS service must explicitly use `false`.

When `persistence: MYSQL` and `cache: REDIS` are selected, the plugin fails closed rather than running a partially synchronized market. It refuses startup when any of these conditions is not met:

- `server-id` is blank or still `ah-server-CHANGE-ME`.
- Redis pub/sub is disabled.
- `sync-secret` is a placeholder or shorter than 32 characters.
- Redis cannot be reached.
- The Redis subscriber does not start.

For a single-server installation, use `persistence: MYSQL, cache: JSON` or `persistence: JSON, cache: JSON`. These modes intentionally do not provide live cross-server synchronization.

## Canvas and Folia Safety

Canvas does not have a single global main thread for plugin work. This project follows these rules:

- Inventory operations, GUI changes, player messages, and recipient-specific updates use the relevant player's Entity Scheduler.
- Block, sign, entity-display, and item-display work uses the target location's Region Scheduler.
- Redis and database callbacks transfer data only; Bukkit work is rescheduled to the owning player or location.
- Shared auction and display indexes use synchronized or concurrent collections where player regions and Redis application can meet.

This avoids common Canvas ownership failures such as opening another player's inventory from a foreign region or updating a display block from the global scheduler.

## Build and Test

```bash
./gradlew test shadowJar
```

The shaded release jar is written to the repository root as `AuctionHouse-2.1.1.jar`.

The test suite compiles against Canvas API build 937 and checks scheduler routing, safety guards, Redis note synchronization, and cluster fail-closed configuration paths.

## Production Verification

Before declaring a network ready, run two Canvas 26.2 build 937 nodes against the intended MySQL and Redis services and verify:

1. A listing on node A is visible on node B.
2. BIN purchases and bids made from different nodes result in one durable final state.
3. Bid, purchase, and chat notifications reach players in different regions without thread-ownership errors.
4. Redis restart/reconnect converges state without duplicate auctions or lost updates.
5. Display creation, reload, and removal work across chunk and region boundaries.

The detailed compatibility research and acceptance matrix are in [docs/canvas-26.2-compatibility-research.md](docs/canvas-26.2-compatibility-research.md).

## Development

```bash
./gradlew runServer
```

`runServer` downloads a Minecraft `26.2` development server. It does not replace the two-node acceptance test because a real shared MySQL/Redis configuration and separate Canvas nodes are required to validate network convergence.

## Credits and License

This project is based on [ElaineQheart/AuctionHouse](https://github.com/elaineqheart/AuctionHouse). See [LICENSE](LICENSE) for licensing terms.
