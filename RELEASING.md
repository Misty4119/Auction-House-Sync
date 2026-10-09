# Releasing

Releases are prepared from reviewed changes merged into `master`. Version 2.1.3 uses tag `v2.1.3` and artifact `AuctionHouse-2.1.3.jar`.

## Prepare the release

1. Keep `build.gradle`, README version/JAR references, and [CHANGELOG.md](CHANGELOG.md) consistent.
2. With Java 25, run `./gradlew test shadowJar` (Windows: `.\gradlew.bat test shadowJar`).
3. Inspect the shaded JAR: plugin entry point and JDBC service metadata must remain valid; private runtime packages must be isolated; platform-shared Adventure classes must stay outside the artifact.
4. Record Canvas 26.2 build 941 runtime evidence. For cluster changes, check two nodes with isolated MySQL/Redis services and distinguish startup/sync results from gameplay and economic checks.
5. Review the diff, confirm the required CI checks pass, and merge the change into `master` before creating or pushing a release tag.

## Publish

A maintainer creates `v<version>` at the reviewed merged commit and pushes that tag. The [release workflow](.github/workflows/release.yml) compares the tag with `v` plus the Gradle project version before publishing, then runs the tests and shaded build.

For a matching tag, the workflow uses the repository's GitHub token to create a GitHub Release with generated notes and attach the exact versioned JAR from the repository root. Write permission is scoped to the release job. A mismatched tag must fail before a release is created.

After publication, confirm the release points to the intended commit, the JAR name matches the version, and the release notes describe known verification limits. Inspect failed workflow logs and correct the cause before retrying; preserve published tag and artifact identity when deciding how to recover.
