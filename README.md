# Auction-House-Sync

> A synchronized branch of the Auction-House plugin: Built upon the original foundation, it integrates **MySQL persistence** and **Redis real-time cross-server synchronization**, enabling a shared auction market across multiple Minecraft servers.

## Project Overview

Auction-House is a **Minecraft auction plugin** designed for Spigot / Paper servers. Players can open the auction interface using the command `/ah` to list items, place bids, view their active auctions or bids, and reclaim expired items.

This branch (`Auction-House-Sync`) introduces a distributed storage layer to address the two primary limitations of the original YAML-based local storage:

* **Data Volatility**: Originally, data was stored in local files, making it susceptible to loss during server restarts or migrations.
* **No Cross-Server Support**: Items listed on Server A were invisible on Server B, preventing shared markets across multiple sub-servers.

The synchronization layer includes:

* **MySQL**: Utilizing `HikariCP` for connection pooling, it serves as the persistent backend for auction data (`ItemNote`, bids, transaction history).
* **Redis 8.0**: Acts as the real-time cache and cross-server message bus, allowing auction changes (listing, bidding, completion, reclamation) to be pushed instantly to all connected servers.

## Synchronization Architecture

```
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│  Paper Node  │       │  Paper Node  │ ...   │  Paper Node  │
│  (1.21.4)    │       │  (1.21.4)    │       │  (1.21.4)    │
└──────┬───────┘       └──────┬───────┘       └──────┬───────┘
       │                      │                      │
       │      Jedis Pub/Sub + Serialized Snapshot    │
       └──────────────┬────────────────────────────┘
                      │
              ┌───────▼────────┐
              │   Redis 8.0    │  Real-time messaging + Caching
              └───────┬────────┘
                      │  Scheduled snapshot
              ┌───────▼────────┐
              │     MySQL      │  Persistent storage
              └────────────────┘

```

### Key Classes:

* `data/persistentStorage/database/MySQLManager` / `MySQLMetaStore` — MySQL connection pooling and table operations.
* `data/persistentStorage/database/RedisManager` — Jedis connection management.
* `data/persistentStorage/database/RedisMetaCache` — Metadata caching within Redis.
* `data/persistentStorage/database/RedisNoteStorage` — Item serialization storage in Redis.
* `data/persistentStorage/database/RedisSyncManager` — Cross-server synchronization coordination.
* `data/persistentStorage/database/CrossServerMessenger` — Cross-server event publish/subscribe.
* `data/persistentStorage/database/RedisSnapshotService` — Periodic snapshot backups.

## Tech Stack

* **Language**: Java 21
* **Build System**: Gradle (with `shadow` plugin for fat JARs)
* **Runtime**: Paper / Spigot 1.21.4 (compatible with higher versions)
* **Database**: MySQL 8.x (`mysql-connector-j` 9.0.0 + `HikariCP` 5.1.0)
* **Cache / Messaging**: Redis 8.0 (`Jedis` 6.1.0)
* **GUI / Text**: `Kyori Adventure` 4.25 (including MiniMessage, Legacy, and Plain serializers)
* **Dependencies**: `morepaperlib` 0.4.4, `PlaceholderAPI` 2.11.6, `VaultAPI` 1.7
* **Integrations**: Vault, PlaceholderAPI, Citizens (NPCs), Locale-API

## Environment Requirements

* **JDK 21** (Toolchain locked)
* At least one Paper 1.21.4 server
* A reachable MySQL 8.x database
* A reachable Redis 8.0 service

## Building

```bash
# Execute in the project root directory
./gradlew shadowJar

```

Upon success, `AuctionHouse-2.0.0.jar` will be generated in the root directory. Simply place this JAR into your server's `plugins/` directory to enable it.

## Local Development

```bash
# Launch a local Paper 1.21 test server (with this plugin auto-mounted)
./gradlew runServer

```

MySQL and Redis connection details (host, port, username, password, database index, etc.) are located in the synchronization section of `config.yml`. Please ensure you change the default passwords before production deployment.

## Upstream & Credits

* Upstream Project: [https://github.com/elaineqheart/AuctionHouse](https://github.com/elaineqheart/AuctionHouse)
* Original Author's SpigotMC Page: [https://www.spigotmc.org/resources/auction-house.125238/](https://www.spigotmc.org/resources/auction-house.125238/)
* Modrinth Page: [https://modrinth.com/plugin/auction-house-plugin](https://modrinth.com/plugin/auction-house-plugin)

This branch only adds database and synchronization layers on top of the original project. All rights to the core functionality remain with the original author; see the [`LICENSE`](https://www.google.com/search?q=./LICENSE) for details.