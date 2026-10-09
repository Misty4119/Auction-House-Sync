# Contributing

Please follow the [Code of Conduct](CODE_OF_CONDUCT.md). For a suspected vulnerability, use [SECURITY.md](SECURITY.md) before posting details publicly.

## Development setup

Use Java 25, the Gradle wrapper, and Canvas 26.2 build 941. Vault and an economy provider are required for gameplay; shared MySQL and Redis are required for a multi-server market. See [README.md](README.md) for configuration.

Run the build and existing tests:

```bash
./gradlew test shadowJar
```

On Windows, use `.\gradlew.bat test shadowJar`. The output for version 2.1.3 is `AuctionHouse-2.1.3.jar` in the repository root.

## Changes and review

1. Describe the problem and expected behavior in an issue or pull request. For larger features or shared-data changes, discuss the design before implementation.
2. Keep the change focused. Preserve the player's Entity Scheduler for inventories, GUIs, and messages and the owning Region Scheduler for world/display work. Move Bukkit work out of database and Redis callbacks.
3. Preserve MySQL as durable cluster state and Redis as cache and live synchronization. Explain changes to persistence, event validation, recovery, or economic behavior.
4. Run the existing tests and shaded build. Add a focused regression test where it can demonstrate the changed behavior; document runtime checks for paths that need Canvas or an economy provider.
5. Include reproduction steps, verification results, and limitations in the pull request. Keep public documentation in English and update user-facing instructions when behavior changes.

## Runtime verification and local data

Use an isolated test environment with a new MySQL schema, a separate Redis key prefix/channel, a shared test sync secret, and a distinct `server-id` for every node. Use loopback-only services for local checks where practical. Keep secrets out of logs and reports; redact credentials, addresses, and player data.

Use full Canvas restarts, never Bukkit `/reload`. Preserve existing server data, database contents, local JARs, and shared PlugDev caches; do not use `plugdev clean` for repository verification.

For synchronization changes, verify at least two Canvas nodes against the same test services. Record startup, propagation, reconnect, and gameplay results separately. A successful startup or cross-node update does not prove purchase/bid atomicity.

Build output, runtime data, and internal notes stay ignored. Check `git status` and `git check-ignore` before adding files. Read [AGENTS.md](AGENTS.md) for concise repository guardrails and [RELEASING.md](RELEASING.md) for version and tag preparation.
