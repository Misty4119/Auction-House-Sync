# Upstream review for Auction-House-Sync 2.1.3

Reviewed: 2026-10-09

Repositories: [upstream Auction-House](https://github.com/ElaineQheart/Auction-House), [Auction-House-Sync fork](https://github.com/Misty4119/Auction-House-Sync)

## Upstream commits

The review starts at upstream 1.5.5 commit [ff56b66](https://github.com/ElaineQheart/Auction-House/commit/ff56b6642f0b96f226ff6671ae58674a95c2612e). GitHub compare `ff56b66...master` reports six commits ahead and none behind. Current upstream master is [7a2da1c](https://github.com/ElaineQheart/Auction-House/commit/7a2da1cb05b96401b1f0674842999f3bcd82fbab), version 1.5.6, dated 2026-09-26. The local `upstream/master` ref stops at 5b020f7, so it omits the last four commits visible on GitHub.

| Commit | Change | Relevance to the fork |
| --- | --- | --- |
| [a7d97ff](https://github.com/ElaineQheart/Auction-House/commit/a7d97ff3a5a4f2f65481a5d72467b483c23c4d88) | Changes Folia item-name handling to spawn an item entity at world spawn. | Do not copy. This fork resolves names without world access or entity spawning; the source-level safety test enforces that boundary. |
| [5b020f7](https://github.com/ElaineQheart/Auction-House/commit/5b020f7e0565c30de0b3f6c79a1644ef788a4e78) | Removes a sound-name `System.out.println`. | Already reflected in the fork; no print remains in `Sounds.playSound`. |
| [7fc3d93](https://github.com/ElaineQheart/Auction-House/commit/7fc3d9351da773836f6c02d335d30d76bef74730) | Passes a `Location` to item-name extraction, which still spawns an entity. | Do not copy. It retains entity and world side effects that the fork intentionally removed for Canvas/Folia safety. |
| [82e11bb](https://github.com/ElaineQheart/Auction-House/commit/82e11bb47399cb396f1b16a8474765766224bf92) | Adds a right-click bundle preview hint to auction-item lore. | Adopt the hint only. The fork already has bundle detection and right-click preview GUI behavior, so this is additive presentation text. |
| [92ed867](https://github.com/ElaineQheart/Auction-House/commit/92ed867b7e50480980feeec8bbe5874cb04ac23c) | Adds DiscordSRV/InteractiveChat listing embeds and optional-dependency guarding; also computes BIN-vs-bid classification once. | Defer the integrations and their extra repositories/dependencies. Adopt the independent one-time command classification because it does not require the integrations or change command behavior. The `createAuction` sound/config dependency belongs to a separate earlier sound feature and is not included. |
| [7a2da1c](https://github.com/ElaineQheart/Auction-House/commit/7a2da1cb05b96401b1f0674842999f3bcd82fbab) | Bumps upstream from 1.5.5 to 1.5.6. | Fork uses its own 2.1.3 version sequence; no upstream code change to backport. |

The 1.5.5 baseline already contains [0491477](https://github.com/ElaineQheart/Auction-House/commit/0491477f95cfe96169f637ba79defdbc207ba939), which adds the `b` price suffix. The fork already supports `b` and has `StringUtilsSafetyTest.parsesBillionPriceSuffix`. Upstream has no GitHub Releases or tags.

## Shaded dependency audit

The Gradle `runtimeClasspath` resolves MySQL Connector/J 9.0.0 with protobuf 4.26.1; HikariCP 5.1.0; Jedis 6.1.0 with `redis-authx-core`, Commons Pool 2.12.1, JSON 20250517, Gson 2.13.1, and SLF4J 2.0.13. Source code also uses Gson for private plugin JSON storage. These packages are implementation details and do not cross the Canvas/Bukkit API boundary. Relocate exact private namespaces under the plugin's `libs` package, including the MySQL driver and its protobuf runtime, Hikari, `redis.clients`, Commons Pool, JSON, Gson, and SLF4J; do not relocate all of `com.google`, because Canvas supplies Guava and related packages.

Canvas API 26.2 build 941 provides Adventure 5.2.0 on `compileClasspath`, while the current build explicitly adds Adventure 4.25.0 as `implementation`, which places an unrelocated Adventure copy in the plugin JAR. Remove those runtime declarations and use the platform-provided Adventure API. Keep `mergeServiceFiles()` and inspect `META-INF/services/java.sql.Driver` plus the Hikari driver-class-name path after relocating MySQL. `RedisManager.getResource()` returns Jedis from an internal storage class, not from a Canvas/Bukkit extension point, so Shadow can rewrite that internal signature.

## Upstream issues

| Issue | Status and request | Local comparison |
| --- | --- | --- |
| [#12](https://github.com/ElaineQheart/Auction-House/issues/12) | Open: hub/category navigation, selling GUI, duration tax, and purchase/sale history. The author said on 2026-09-30 they would review the proposal later. | This fork has configurable categories but no history, selling GUI, or hub GUI. The related PR is a feature proposal, not a matching bug fix. |
| [#10](https://github.com/ElaineQheart/Auction-House/issues/10) | Closed: Canvas/Folia scheduling and item-name behavior. | The fork has entity/region scheduler paths and scheduler safety tests. Its no-entity-spawn name extraction is intentionally different. Review ItemNote name fallback call sites separately if that path changes. |
| [#8](https://github.com/ElaineQheart/Auction-House/issues/8) | Closed: bid seller collection can throw an NPE after restart when bid claimants are missing. | The fork includes claimant reconstruction and defensive missing-list handling in AuctionHouseStorage.checkRemove. |
| [#7](https://github.com/ElaineQheart/Auction-House/issues/7) | Closed: pagination can call subList with an out-of-range page. | The fork clamps pages with Pagination.clampPage and has PaginationTest coverage. |
| [#6](https://github.com/ElaineQheart/Auction-House/issues/6) | Closed: shaded dependencies can conflict with other plugins when not relocated. | The fork relocates MorePaperLib, but build.gradle also shades private database/Redis libraries and currently packages an unrelocated Adventure copy. Relocate private database/Redis packages and rely on the Canvas-provided shared Adventure API. |

The upstream #8 fix is [51812d6](https://github.com/ElaineQheart/Auction-House/commit/51812d604e0df84f6626ef346d79af8e8c0ff276). The fork contains that fix and additional defensive handling.

## Upstream pull requests

| Pull request | Status | Relevance |
| --- | --- | --- |
| [#13](https://github.com/ElaineQheart/Auction-House/pull/13) | Open; 29 files, 1,691 additions, 74 deletions; no reviews/comments, last updated 2026-09-28, and GitHub reports it mergeable. | Adds hub/category/sell/history/tax flows. It stores history in per-server SQLite, which does not match this fork's shared MySQL source of truth and Redis synchronization. Porting it requires a shared history model, MySQL migrations, Redis propagation, and atomic claim handling. Defer beyond 2.1.3. |
| [#11](https://github.com/ElaineQheart/Auction-House/pull/11) | Closed, not merged. | Paper API/MiniMessage migration removes legacy ampersand color behavior; the fork already has a different Canvas API/Adventure stack. |
| [#9](https://github.com/ElaineQheart/Auction-House/pull/9) | Merged. | Folia GUI scheduling is relevant background, but this fork has its own Canvas-safe GUI and item-name implementation. |
| [#5](https://github.com/ElaineQheart/Auction-House/pull/5) | Merged. | Avoids a BLACK_BUNDLE constant missing on older versions. This fork uses material-name matching in ItemManager.isBundle. |
| [#4](https://github.com/ElaineQheart/Auction-House/pull/4) | Merged. | Earlier partial Folia support; the fork has since added its own scheduler handling. |
| [#3](https://github.com/ElaineQheart/Auction-House/pull/3), [#2](https://github.com/ElaineQheart/Auction-House/pull/2) | Merged. | No clear new defect affecting the current 2.1.x fork was found. |
| [#1](https://github.com/ElaineQheart/Auction-House/pull/1) | Closed, not merged. | Old messages.yml cleanup; no relevant current behavior change. |

## Sources and limits

- GitHub repository, branch, commit, issue, pull-request, compare, and release APIs plus `git ls-remote` were checked on 2026-10-09. The current master HEAD was 7a2da1c; no later upstream master commit existed at review time.
- Local source comparison used the current tracked fork checkout. Existing issue fixes and candidate paths were inspected in AuctionHouseCommand, AuctionHouseStorage, ItemManager, Pagination, StringUtils, Sounds, and build.gradle.
- This is source and metadata research, not a live gameplay test or a security audit.
