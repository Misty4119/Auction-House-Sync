# Repository guidance

## Working target

- Use Java 25 and the Gradle wrapper. Canvas 26.2 build 941 is the release target; the compile API is `26.2.build.941-stable`.
- Build and run the existing tests with `./gradlew test shadowJar` (Windows: `.\gradlew.bat test shadowJar`). The shaded release JAR is written to the repository root.
- Read [CONTRIBUTING.md](CONTRIBUTING.md) when changing behavior or planning runtime verification. Read [RELEASING.md](RELEASING.md) before changing versions or preparing tags.

## Runtime boundaries

- Route inventories, GUIs, messages, and other player work through that player's Entity Scheduler. Route blocks and displays through the owning location's Region Scheduler.
- Database and Redis callbacks transfer data; schedule Bukkit work onto its owner. Preserve concurrency protection where shared indexes meet multiple regions.
- MySQL is durable cluster state; Redis is cache and live synchronization. Preserve persistence, event validation, de-duplication, and reconnect behavior together.
- Keep platform-shared Adventure/Bukkit/Canvas types outside private shaded relocations. Review service metadata and reflective class names when changing database dependencies.
- Use full Canvas restarts for plugin changes; never use Bukkit `/reload`.

## Local data and releases

- Use isolated test schemas, Redis namespaces, unique node IDs, and test-only secrets for runtime checks. Preserve existing local databases, server directories, JARs, and PlugDev caches.
- Keep credentials, runtime output, and internal notes ignored. Only `docs/research/upstream-review-2026-10.md` is public under `docs/`; check ignore rules before adding documentation.
- Keep Gradle version, README JAR name, changelog, and release tag consistent. Tags use `v<version>`; create a release tag only after the reviewed change is merged.
- Report commands run, observed results, and incomplete runtime checks. Startup or sync smoke tests do not establish financial atomicity.
